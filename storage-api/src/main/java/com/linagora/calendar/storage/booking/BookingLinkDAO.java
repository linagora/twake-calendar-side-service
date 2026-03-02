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

package com.linagora.calendar.storage.booking;

import org.apache.james.core.Username;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface BookingLinkDAO {
    Mono<BookingLink> insert(Username username, BookingLinkInsertRequest request);

    Mono<BookingLink> findByPublicId(Username username, BookingLinkPublicId publicId);

    Flux<BookingLink> findByUsername(Username username);

    Mono<BookingLink> update(Username username, BookingLinkPublicId publicId, BookingLinkPatchRequest request);

    Mono<BookingLinkPublicId> resetPublicId(Username username, BookingLinkPublicId publicId);

    Mono<Void> delete(Username username, BookingLinkPublicId publicId);
}
