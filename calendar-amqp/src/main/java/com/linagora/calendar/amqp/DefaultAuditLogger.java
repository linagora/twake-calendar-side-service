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

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class DefaultAuditLogger implements AuditLogger {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String format(String body, String exchangeName) {
        String uid = extractUid(body);
        return uid.isPresent()
            ? exchangeName + " " + uid.get()
            : exchangeName + " received";
    }

    private static Optional<String> extractUid(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root.has("uid")) {
                return Optional.ofNullable(root.get("uid").asText(null));
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}