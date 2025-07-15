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
import java.util.function.Function;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;

import reactor.core.publisher.Mono;

public class EventParticipationActionLinkFactory {

    public record ActionLinks(URI yes,
                              URI no,
                              URI maybe) {
    }

    private final ParticipationTokenSigner participationTokenSigner;
    private final Function<String, URI> buildParticipationActionLinkFunction;

    @Inject
    @Singleton
    public EventParticipationActionLinkFactory(ParticipationTokenSigner participationTokenSigner,
                                               @Named("spaExcalUrl") URL spaExcalUrl) {
        this.participationTokenSigner = participationTokenSigner;
        this.buildParticipationActionLinkFunction = jwt -> URI.create(StringUtils.removeEnd(spaExcalUrl.toString(), "/") + "/calendar/#/calendar/participation/?jwt=" + jwt);
    }

    public Mono<ActionLinks> generateLinks(MailAddress organizer, MailAddress attendee, String eventUid, String calendarURI) {
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
                URI yesLink = buildParticipationActionLinkFunction.apply(tokens.getT1());
                URI noLink = buildParticipationActionLinkFunction.apply(tokens.getT2());
                URI maybeLink = buildParticipationActionLinkFunction.apply(tokens.getT3());
                return new ActionLinks(yesLink, noLink, maybeLink);
            });
    }
}
