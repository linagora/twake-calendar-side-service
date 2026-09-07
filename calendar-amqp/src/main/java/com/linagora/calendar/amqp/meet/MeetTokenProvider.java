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

package com.linagora.calendar.amqp.meet;

import reactor.core.publisher.Mono;

/**
 * Source of the bearer token used to call Meet's external API on behalf
 * of a given user.
 *
 * <p>Kept as an interface so the authentication chain can evolve without
 * touching the callers: {@link MeetApplicationCredentialsTokenProvider} is
 * the current implementation — the side service holds Meet application
 * credentials and asserts the user identity by {@code scope=email}. ADR 056
 * discusses instead exchanging the user's own SSO token towards the Meet
 * audience; whichever way that discussion settles, only this implementation
 * is swapped.
 */
public interface MeetTokenProvider {

    /**
     * Resolve a bearer token that Meet will accept as acting on behalf of
     * {@code userEmail}.
     */
    Mono<String> fetchToken(String userEmail);
}
