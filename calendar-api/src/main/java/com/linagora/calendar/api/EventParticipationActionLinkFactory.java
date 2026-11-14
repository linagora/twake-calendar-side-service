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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.james.core.MailAddress;

import com.google.common.base.Preconditions;

import reactor.core.publisher.Mono;

public class EventParticipationActionLinkFactory {

    public record ActionLinks(URI yes,
                              URI no,
                              URI maybe) {
    }

    private final ParticipationTokenSigner participationTokenSigner;
    private final DomainUrl spaExcalUrl;

    @Inject
    @Singleton
    public EventParticipationActionLinkFactory(@Named("participationActionLinks") JwtSigner jwtSigner,
                                               JwtVerifier jwtVerifier,
                                               @Named("spaExcalUrl") DomainUrl spaExcalUrl) {
        this(new ParticipationTokenSigner.Default(jwtSigner, jwtVerifier), spaExcalUrl);
    }

    public EventParticipationActionLinkFactory(ParticipationTokenSigner participationTokenSigner,
                                               URL spaExcalUrl) {
        this(participationTokenSigner, DomainUrl.of(spaExcalUrl));
    }

    public EventParticipationActionLinkFactory(ParticipationTokenSigner participationTokenSigner,
                                               DomainUrl spaExcalUrl) {
        this.participationTokenSigner = participationTokenSigner;
        this.spaExcalUrl = spaExcalUrl;
    }

    public Mono<ActionLinks> generateLinks(MailAddress organizer, MailAddress attendee, String eventUid, String calendarURI) {
        Preconditions.checkArgument(organizer != null, "organizer must not be null");
        Preconditions.checkArgument(attendee != null, "attendee must not be null");
        Preconditions.checkArgument(StringUtils.isNotEmpty(eventUid), "eventUid must not be empty");
        Preconditions.checkArgument(StringUtils.isNotEmpty(calendarURI), "calendarURI must not be empty");

        Participation acceptedParticipation = new Participation(organizer, attendee, eventUid, calendarURI, Participation.ParticipantAction.ACCEPTED);
        Participation rejectedParticipation = acceptedParticipation.withAction(Participation.ParticipantAction.REJECTED);
        Participation maybeParticipation = acceptedParticipation.withAction(Participation.ParticipantAction.TENTATIVE);

        return Mono.zip(participationTokenSigner
                    .signAsJwt(acceptedParticipation),
                participationTokenSigner
                    .signAsJwt(rejectedParticipation),
                participationTokenSigner
                    .signAsJwt(maybeParticipation))
            .map(tokens -> {
                String excalBaseUrl = Strings.CS.removeEnd(spaExcalUrl.resolve(organizer.getDomain()).toString(), "/");
                URI yesLink = participationActionLink(excalBaseUrl, tokens.getT1());
                URI noLink = participationActionLink(excalBaseUrl, tokens.getT2());
                URI maybeLink = participationActionLink(excalBaseUrl, tokens.getT3());
                return new ActionLinks(yesLink, noLink, maybeLink);
            });
    }

    private static URI participationActionLink(String excalBaseUrl, String jwt) {
        return URI.create(excalBaseUrl + "/excal/?jwt=" + jwt);
    }
}
