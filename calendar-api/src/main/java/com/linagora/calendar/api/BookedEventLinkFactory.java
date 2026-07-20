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
import java.net.URL;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.Strings;

import com.linagora.calendar.api.BookedEventTokenSigner.BookedEvent;

import reactor.core.publisher.Mono;

/**
 * Builds the link to the excal page displaying a confirmed reservation:
 * {@code {spaExcalUrl}/booking/confirmed/{jwt}}.
 */
public class BookedEventLinkFactory {

    private static final String BOOKING_CONFIRMED_PATH = "/booking/confirmed/";

    private final BookedEventTokenSigner bookedEventTokenSigner;
    private final String excalBaseUrl;

    @Inject
    @Singleton
    public BookedEventLinkFactory(@Named("participationActionLinks") JwtSigner jwtSigner,
                                  JwtVerifier jwtVerifier,
                                  @Named("spaExcalUrl") URL spaExcalUrl) {
        this(new BookedEventTokenSigner.Default(jwtSigner, jwtVerifier), spaExcalUrl);
    }

    public BookedEventLinkFactory(BookedEventTokenSigner bookedEventTokenSigner, URL spaExcalUrl) {
        this.bookedEventTokenSigner = bookedEventTokenSigner;
        this.excalBaseUrl = Strings.CS.removeEnd(spaExcalUrl.toString(), "/");
    }

    public Mono<URI> generateLink(BookedEvent bookedEvent) {
        return bookedEventTokenSigner.signAsJwt(bookedEvent)
            .map(jwt -> URI.create(excalBaseUrl + BOOKING_CONFIRMED_PATH + jwt));
    }
}
