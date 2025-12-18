/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  https://www.gnu.org/licenses/agpl-3.0.en.html                   *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                                                   *
 ********************************************************************/

package com.linagora.calendar.webadmin.service;

import static com.linagora.calendar.webadmin.CalendarUserTaskRoutes.LdapUsersImportRequestToTask.TASK_NAME;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.task.Task;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.google.common.base.MoreObjects;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ldap.LdapUser;
import com.linagora.calendar.storage.ldap.LdapUserDAO;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class LdapUsersImportService {

    public static class Context {
        public record Snapshot(long processedUserCount, long failedUserCount) {
            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this)
                    .add("processedUserCount", processedUserCount)
                    .add("failedUserCount", failedUserCount)
                    .toString();
            }
        }

        private final AtomicLong processedUserCount;
        private final AtomicLong failedUserCount;

        public Context() {
            processedUserCount = new AtomicLong();
            failedUserCount = new AtomicLong();
        }

        void incrementProcessedUser() {
            processedUserCount.incrementAndGet();
        }

        void incrementFailedUser() {
            failedUserCount.incrementAndGet();
        }

        public Context.Snapshot snapshot() {
            return new Context.Snapshot(
                processedUserCount.get(),
                failedUserCount.get());
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(LdapUsersImportService.class);

    private final OpenPaaSUserDAO userDAO;
    private final LdapUserDAO ldapUserDAO;

    @Inject
    public LdapUsersImportService(OpenPaaSUserDAO userDAO, LdapUserDAO ldapUserDAO) {
        this.userDAO = userDAO;
        this.ldapUserDAO = ldapUserDAO;
    }

    public Mono<Task.Result> importUsers(Context context, int usersPerSecond) {
        return Flux.fromIterable(Throwing.supplier(ldapUserDAO::getAllUsers).get())
            .transform(ReactorUtils.<LdapUser, Task.Result>throttle()
                .elements(usersPerSecond)
                .per(Duration.ofSeconds(1))
                .forOperation(ldapUser -> importUser(context, ldapUser)))
            .reduce(Task.Result.COMPLETED, Task::combine)
            .map(result -> {
                LOGGER.info("{} task result: {}. Detail:\n{}", TASK_NAME.asString(), result.toString(), context.snapshot());
                return result;
            }).onErrorResume(e -> {
                LOGGER.error("Task {} is incomplete", TASK_NAME.asString(), e);
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    private Mono<Task.Result> importUser(Context context, LdapUser ldapUser) {
        return ldapUser.mail()
            .map(Username::fromMailAddress)
            .map(user -> importUser(context, user, ldapUser))
            .orElseGet(() -> {
                LOGGER.info("Skipping import of user without mail {}", ldapUser.uid());
                return Mono.just(Task.Result.COMPLETED);
            });
    }

    private Mono<Task.Result> importUser(Context context, Username username, LdapUser ldapUser) {
       return userDAO.retrieve(username)
           .flatMap(storedUser -> {
               if (Objects.equals(storedUser.firstname(), getFirstName(ldapUser)) &&
                   Objects.equals(storedUser.lastname(), ldapUser.sn())) {
                   return Mono.just(Task.Result.COMPLETED);
               }
               return userDAO.update(storedUser.id(), username, getFirstName(ldapUser), ldapUser.sn())
                   .then(Mono.just(Task.Result.COMPLETED));
           })
           .doOnNext(completed -> context.incrementProcessedUser())
           .switchIfEmpty(userDAO.add(username, getFirstName(ldapUser), ldapUser.sn())
               .then(Mono.fromCallable(() -> {
                   context.incrementProcessedUser();
                   return Task.Result.COMPLETED;
               })))
           .onErrorResume(e -> {
                LOGGER.error("Error importing ldap user {}", ldapUser.cn(), e);
                context.incrementFailedUser();
                return Mono.just(Task.Result.PARTIAL);
           });
    }

    private String getFirstName(LdapUser ldapUser) {
        // cn is full name, we try to extract first name from it by removing last name (sn)
        return ldapUser.cn().replace(ldapUser.sn(), "").trim();
    }
}
