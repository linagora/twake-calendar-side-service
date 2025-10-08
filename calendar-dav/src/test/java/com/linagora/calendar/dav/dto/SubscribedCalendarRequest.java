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


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public record SubscribedCalendarRequest(String id,
                                        @JsonProperty("calendarserver:source") Source source) {

    public static final ObjectMapper MAPPER = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.ALWAYS);

    @JsonProperty("acl")
    public List<Map<String, Object>> acl() {
        return source.acl();
    }

    @JsonProperty("invite")
    public List<Map<String, Object>> invite() {
        return source.invite();
    }

    @JsonProperty("apple:color")
    public String appleColor() {
        return source.color();
    }

    @JsonProperty("caldav:description")
    public String calDavDescription() {
        return "";
    }

    @JsonProperty("dav:name")
    public String davName() {
        return source.name();
    }

    public String serialize() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public record Source(String name,
                         String color,
                         @JsonIgnore String userId,
                         @JsonProperty("id") String calendarId,
                         List<Map<String, Object>> invite,
                         List<Map<String, Object>> acl,
                         Map<String, Object> rights,
                         boolean readOnly) {

        @JsonProperty("description")
        public String description() {
            return "";
        }

        @JsonProperty("calendarHomeId")
        public String calendarHomeId() {
            return userId;
        }

        @JsonProperty("href")
        public String href() {
            return "/calendars/%s/%s.json".formatted(userId, calendarId());
        }

        @JsonProperty("selected")
        public boolean selected() {
            return false;
        }

        @JsonProperty("type")
        public String type() {
            return "user";
        }
    }

    public static class Builder {
        private String id;
        private String name;
        private String color;
        private List<Map<String, Object>> invite;
        private List<Map<String, Object>> acl;
        private Map<String, Object> rights;
        private boolean readOnly;
        private String sourceUserId;
        private String sourceCalendarId;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder sourceUserId(String sourceId) {
            this.sourceUserId = sourceId;
            this.invite = buildInvite(sourceId);
            return this;
        }

        public Builder sourceCalendarId(String sourceCalendarId) {
            this.sourceCalendarId = sourceCalendarId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder color(String color) {
            this.color = color;
            return this;
        }

        public Builder readOnly(boolean readOnly) {
            this.readOnly = readOnly;
            this.acl = buildAcl(sourceUserId, readOnly);
            this.rights = buildRights(sourceUserId, readOnly);
            return this;
        }

        public static List<Map<String, Object>> buildAcl(String sourceId, boolean readOnly) {
            List<Map<String, Object>> acl = new ArrayList<>();

            acl.add(aclEntry("{DAV:}share", "principals/users/" + sourceId));
            acl.add(aclEntry("{DAV:}share", "principals/users/" + sourceId + "/calendar-proxy-write"));
            acl.add(aclEntry("{DAV:}write", "principals/users/" + sourceId));
            acl.add(aclEntry("{DAV:}write", "principals/users/" + sourceId + "/calendar-proxy-write"));
            acl.add(aclEntry("{DAV:}write-properties", "principals/users/" + sourceId));
            acl.add(aclEntry("{DAV:}write-properties", "principals/users/" + sourceId + "/calendar-proxy-write"));
            acl.add(aclEntry("{DAV:}read", "principals/users/" + sourceId));
            acl.add(aclEntry("{DAV:}read", "principals/users/" + sourceId + "/calendar-proxy-read"));
            acl.add(aclEntry("{DAV:}read", "principals/users/" + sourceId + "/calendar-proxy-write"));
            acl.add(aclEntry("{DAV:}read", "{DAV:}authenticated"));

            if (!readOnly) {
                acl.add(aclEntry("{DAV:}write", "{DAV:}authenticated"));
            }

            return acl;
        }

        private static Map<String, Object> aclEntry(String privilege, String principal) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("privilege", privilege);
            entry.put("principal", principal);
            entry.put("protected", true);
            return entry;
        }

        public static Map<String, Object> buildRights(String sourceId, boolean readOnly) {
            return Map.of(
                "_userEmails", Map.of(sourceId, "principals/users/" + sourceId),
                "_ownerId", sourceId,
                "_public", readOnly ? "{DAV:}read" : "{DAV:}write",
                "_sharee", Map.of(),
                "_type", "user"
            );
        }

        public static List<Map<String, Object>> buildInvite(String sourceId) {
            Map<String, Object> invite = new LinkedHashMap<>();
            invite.put("href", "principals/users/" + sourceId);
            invite.put("principal", "principals/users/" + sourceId);
            invite.put("properties", List.of());
            invite.put("access", 1);
            invite.put("comment", null);
            invite.put("inviteStatus", 2);

            return List.of(invite);
        }

        public SubscribedCalendarRequest build() {
            sourceCalendarId = sourceCalendarId == null ? sourceUserId : sourceCalendarId;
            Source source = new Source(name, color, sourceUserId, sourceCalendarId, invite, acl, rights, readOnly);
            return new SubscribedCalendarRequest(id, source);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
