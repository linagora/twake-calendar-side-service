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


package com.linagora.calendar.webadmin;

import static org.apache.james.webadmin.Constants.SEPARATOR;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.mail.internet.AddressException;

import org.apache.james.core.MailAddress;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.tasks.TaskFromRequestRegistry;
import org.apache.james.webadmin.tasks.TaskRegistrationKey;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.apache.james.webadmin.utils.Responses;
import org.eclipse.jetty.http.HttpStatus;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.unsent.UnsentMailRepository;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.SendingTrial;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMail;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMailId;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMailQuery;
import com.linagora.calendar.webadmin.service.UnsentMailResendService;
import com.linagora.calendar.webadmin.task.UnsentMailResendTask;

import spark.Request;
import spark.Response;
import spark.Route;
import spark.Service;

public class UnsentMailRoutes implements Routes {
    public static final String BASE_PATH = "/unsentMails";
    public static final TaskRegistrationKey RESEND = TaskRegistrationKey.of("resend");

    private static final String UNSENT_MAIL_ID_PARAM = ":unsentMailId";
    private static final String UNSENT_MAIL_PATH = BASE_PATH + SEPARATOR + UNSENT_MAIL_ID_PARAM;
    private static final String ACTION_PARAMETER = "action";
    private static final String SENDER_PARAMETER = "sender";
    private static final String RECIPIENT_PARAMETER = "recipient";
    private static final String LIMIT_PARAMETER = "limit";

    public record UnsentMailIdResponse(String id) {
        static UnsentMailIdResponse from(UnsentMailId id) {
            return new UnsentMailIdResponse(id.value());
        }
    }

    public record SendingTrialResponse(Instant date, String errorMessage) {
        static SendingTrialResponse from(SendingTrial trial) {
            return new SendingTrialResponse(trial.date(), trial.errorMessage());
        }
    }

    public record UnsentMailResponse(String id,
                                     String mailFrom,
                                     List<String> rcptTo,
                                     String body,
                                     Instant createdAt,
                                     List<SendingTrialResponse> sendingTrials) {
        static UnsentMailResponse from(UnsentMail unsentMail) {
            return new UnsentMailResponse(unsentMail.id().value(),
                unsentMail.mailFrom().map(MailAddress::asString).orElse(null),
                unsentMail.rcptTo().stream().map(MailAddress::asString).toList(),
                new String(unsentMail.mimeMessage(), StandardCharsets.UTF_8),
                unsentMail.createdAt(),
                unsentMail.sendingTrials().stream().map(SendingTrialResponse::from).toList());
        }
    }

    private final UnsentMailRepository repository;
    private final UnsentMailResendService resendService;
    private final TaskManager taskManager;
    private final JsonTransformer jsonTransformer;

    @Inject
    public UnsentMailRoutes(UnsentMailRepository repository,
                            UnsentMailResendService resendService,
                            TaskManager taskManager,
                            JsonTransformer jsonTransformer) {
        this.repository = repository;
        this.resendService = resendService;
        this.taskManager = taskManager;
        this.jsonTransformer = jsonTransformer;
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    @Override
    public void define(Service service) {
        service.get(BASE_PATH, this::list, jsonTransformer);
        service.get(UNSENT_MAIL_PATH, this::get, jsonTransformer);
        service.delete(UNSENT_MAIL_PATH, this::delete);
        service.delete(BASE_PATH, this::deleteAll);
        service.post(BASE_PATH, resendAllRoute(), jsonTransformer);
        service.post(UNSENT_MAIL_PATH, resendOneRoute(), jsonTransformer);
    }

    private List<UnsentMailIdResponse> list(Request request, Response response) {
        List<UnsentMailIdResponse> ids = repository.list(extractQuery(request))
            .map(UnsentMailIdResponse::from)
            .collectList()
            .block();

        response.status(HttpStatus.OK_200);
        return ids;
    }

    private UnsentMailResponse get(Request request, Response response) {
        UnsentMailId id = new UnsentMailId(request.params(UNSENT_MAIL_ID_PARAM));

        return repository.read(id)
            .map(UnsentMailResponse::from)
            .blockOptional()
            .orElseThrow(() -> ErrorResponder.builder()
                .statusCode(HttpStatus.NOT_FOUND_404)
                .type(ErrorResponder.ErrorType.NOT_FOUND)
                .message("Unsent mail '%s' does not exist".formatted(id.value()))
                .haltError());
    }

    private Object delete(Request request, Response response) {
        repository.delete(new UnsentMailId(request.params(UNSENT_MAIL_ID_PARAM))).block();
        return Responses.returnNoContent(response);
    }

    private Object deleteAll(Request request, Response response) {
        repository.deleteAll().block();
        return Responses.returnNoContent(response);
    }

    private Route resendAllRoute() {
        return TaskFromRequestRegistry.builder()
            .parameterName(ACTION_PARAMETER)
            .register(RESEND, request -> new UnsentMailResendTask(resendService, extractQuery(request)))
            .buildAsRoute(taskManager);
    }

    private Route resendOneRoute() {
        return TaskFromRequestRegistry.builder()
            .parameterName(ACTION_PARAMETER)
            .register(RESEND, request -> new UnsentMailResendTask(resendService,
                new UnsentMailId(request.params(UNSENT_MAIL_ID_PARAM))))
            .buildAsRoute(taskManager);
    }

    private UnsentMailQuery extractQuery(Request request) {
        return UnsentMailQuery.builder()
            .sender(extractMailAddress(request, SENDER_PARAMETER))
            .recipient(extractMailAddress(request, RECIPIENT_PARAMETER))
            .limit(extractLimit(request))
            .build();
    }

    private Optional<MailAddress> extractMailAddress(Request request, String parameterName) {
        return Optional.ofNullable(request.queryParams(parameterName))
            .map(value -> {
                try {
                    return new MailAddress(value);
                } catch (AddressException e) {
                    throw ErrorResponder.builder()
                        .statusCode(HttpStatus.BAD_REQUEST_400)
                        .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                        .message("Invalid mail address supplied for query parameter '%s'".formatted(parameterName))
                        .cause(e)
                        .haltError();
                }
            });
    }

    private Optional<Integer> extractLimit(Request request) {
        return Optional.ofNullable(request.queryParams(LIMIT_PARAMETER))
            .map(value -> {
                try {
                    int limit = Integer.parseInt(value);
                    Preconditions.checkArgument(limit > 0, "Query parameter '%s' must be strictly positive", LIMIT_PARAMETER);
                    return limit;
                } catch (IllegalArgumentException e) {
                    throw ErrorResponder.builder()
                        .statusCode(HttpStatus.BAD_REQUEST_400)
                        .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                        .message("Invalid value supplied for query parameter '%s', expecting a strictly positive integer".formatted(LIMIT_PARAMETER))
                        .cause(e)
                        .haltError();
                }
            });
    }
}
