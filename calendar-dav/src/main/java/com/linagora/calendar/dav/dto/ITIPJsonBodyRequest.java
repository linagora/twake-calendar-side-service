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

package com.linagora.calendar.dav.dto;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ITIPJsonBodyRequest(String ical,
                                  String sender,
                                  String recipient,
                                  String replyTo,
                                  String uid,
                                  String dtstamp,
                                  String method,
                                  String sequence,
                                  @JsonProperty("recurrence-id") String recurrenceId) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        public static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
        private String ical;
        private String sender;
        private String recipient;
        private String replyTo;
        private String uid;
        private String dtstamp;
        private String method;
        private String sequence;
        private String recurrenceId;

        public Builder ical(String ical) {
            this.ical = ical;
            return this;
        }

        public Builder sender(String sender) {
            this.sender = sender;
            return this;
        }

        public Builder recipient(String recipient) {
            this.recipient = recipient;
            return this;
        }

        public Builder replyTo(String replyTo) {
            this.replyTo = replyTo;
            return this;
        }

        public Builder uid(String uid) {
            this.uid = uid;
            return this;
        }

        public Builder dtstamp(Instant dtstamp) {
            this.dtstamp = TIME_FORMATTER.format(dtstamp);
            return this;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder sequence(String sequence) {
            this.sequence = sequence;
            return this;
        }

        public Builder recurrenceId(String recurrenceId) {
            this.recurrenceId = recurrenceId;
            return this;
        }

        public ITIPJsonBodyRequest build() {
            Objects.requireNonNull(ical, "ical must not be null");
            Objects.requireNonNull(sender, "sender must not be null");
            Objects.requireNonNull(recipient, "recipient must not be null");
            Objects.requireNonNull(uid, "uid must not be null");
            Objects.requireNonNull(method, "method must not be null");
            Objects.requireNonNull(dtstamp, "dtstamp must not be null");

            if (Objects.isNull(replyTo)) {
                replyTo = sender;
            }
            if (Objects.isNull(sequence)) {
                sequence = "0";
            }

            return new ITIPJsonBodyRequest(
                ical,
                sender,
                recipient,
                replyTo,
                uid,
                dtstamp,
                method,
                sequence,
                recurrenceId
            );
        }
    }
}
