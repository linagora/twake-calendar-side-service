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
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.james.core.MailAddress;

import com.google.common.base.Preconditions;
import com.linagora.calendar.api.Participation.ParticipantAction;

import reactor.core.publisher.Mono;

public class EventParticipationActionLinkFactory {

    public record ActionLinks(URI yes,
                              URI no,
                              URI maybe) {
    }

    public static final String JWT_PARAM = "jwt";
    public static final String ACTION_PARAM = "action";

    private final ParticipationTokenSigner participationTokenSigner;
    private final String excalBaseUrl;

    @Inject
    @Singleton
    public EventParticipationActionLinkFactory(@Named("participationActionLinks") JwtSigner jwtSigner,
                                               JwtVerifier jwtVerifier,
                                               @Named("spaExcalUrl") URL spaExcalUrl) {
        this(new ParticipationTokenSigner.Default(jwtSigner, jwtVerifier), spaExcalUrl);
    }

    public EventParticipationActionLinkFactory(ParticipationTokenSigner participationTokenSigner,
                                               URL spaExcalUrl) {
        this.participationTokenSigner = participationTokenSigner;
        this.excalBaseUrl = Strings.CS.removeEnd(spaExcalUrl.toString(), "/") + "/excal/";
    }

    public Mono<ActionLinks> generateLinks(MailAddress organizer, MailAddress attendee, String eventUid, String calendarURI) {
        return generateLinks(organizer, attendee, eventUid, calendarURI, Optional.empty());
    }

    /**
     * @param recurrenceId when present, the links answer for this occurrence of the recurring event only
     */
    public Mono<ActionLinks> generateLinks(MailAddress organizer, MailAddress attendee, String eventUid, String calendarURI,
                                           Optional<String> recurrenceId) {
        Preconditions.checkArgument(organizer != null, "organizer must not be null");
        Preconditions.checkArgument(attendee != null, "attendee must not be null");
        Preconditions.checkArgument(StringUtils.isNotEmpty(eventUid), "eventUid must not be empty");
        Preconditions.checkArgument(StringUtils.isNotEmpty(calendarURI), "calendarURI must not be empty");
        Preconditions.checkArgument(recurrenceId != null, "recurrenceId must not be null");

        ParticipationGrant grant = new ParticipationGrant(organizer, attendee, eventUid, calendarURI, recurrenceId);

        return participationTokenSigner.signAsJwt(grant)
            .map(jwt -> new ActionLinks(
                buildLink(jwt, ParticipantAction.ACCEPTED),
                buildLink(jwt, ParticipantAction.REJECTED),
                buildLink(jwt, ParticipantAction.TENTATIVE)));
    }

    /**
     * All the links of an attendee share one token, which grants any answer: signing is RSA, and costly.
     */
    private URI buildLink(String jwt, ParticipantAction action) {
        return URI.create(excalBaseUrl + "?" + JWT_PARAM + "=" + jwt + "&" + ACTION_PARAM + "=" + action.name());
    }
}
