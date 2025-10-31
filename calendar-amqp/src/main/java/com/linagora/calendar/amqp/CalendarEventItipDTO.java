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

package com.linagora.calendar.amqp;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CalendarEventItipDTO(@JsonProperty("sender") String sender,
                                   @JsonProperty("recipient") String recipient,
                                   @JsonProperty("message") String message,
                                   @JsonProperty("method") String method,
                                   @JsonProperty("significantChange") boolean significantChange,
                                   @JsonProperty("hasChange") boolean hasChange,
                                   @JsonProperty("uid") String uid,
                                   @JsonProperty("component") String component) {
}