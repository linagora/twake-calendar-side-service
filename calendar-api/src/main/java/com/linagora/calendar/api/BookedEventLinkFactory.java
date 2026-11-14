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

package com.linagora.calendar.api;

import java.net.URI;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.Strings;
import org.apache.james.core.Username;

import com.linagora.calendar.api.BookedEventTokenSigner.BookedEvent;

import reactor.core.publisher.Mono;

/**
 * Builds the link to the excal page displaying a confirmed reservation:
 * {@code {spaExcalUrl}/booking/confirmed/{jwt}}, {@code spaExcalUrl} being resolved against the domain of the
 * booking link owner.
 */
public class BookedEventLinkFactory {

    private static final String BOOKING_CONFIRMED_PATH = "/booking/confirmed/";

    private final BookedEventTokenSigner bookedEventTokenSigner;
    private final DomainUrl spaExcalUrl;

    @Inject
    @Singleton
    public BookedEventLinkFactory(@Named("participationActionLinks") JwtSigner jwtSigner,
                                  JwtVerifier jwtVerifier,
                                  @Named("spaExcalUrl") DomainUrl spaExcalUrl) {
        this(new BookedEventTokenSigner.Default(jwtSigner, jwtVerifier), spaExcalUrl);
    }

    public BookedEventLinkFactory(BookedEventTokenSigner bookedEventTokenSigner, DomainUrl spaExcalUrl) {
        this.bookedEventTokenSigner = bookedEventTokenSigner;
        this.spaExcalUrl = spaExcalUrl;
    }

    public Mono<URI> generateLink(BookedEvent bookedEvent, Username owner) {
        return bookedEventTokenSigner.signAsJwt(bookedEvent)
            .map(jwt -> URI.create(Strings.CS.removeEnd(spaExcalUrl.resolve(owner).toString(), "/") + BOOKING_CONFIRMED_PATH + jwt));
    }
}
