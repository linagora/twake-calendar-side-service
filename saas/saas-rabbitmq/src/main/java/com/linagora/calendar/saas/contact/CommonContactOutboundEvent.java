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

package com.linagora.calendar.saas.contact;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Preconditions;
import com.linagora.calendar.dav.ContactUid;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.AddressBookDeletedEvent;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.AddressBookSyncEndEvent;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.AddressBookSyncStartEvent;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.ContactChangedEvent;

public sealed interface CommonContactOutboundEvent permits ContactChangedEvent, AddressBookDeletedEvent,
    AddressBookSyncStartEvent, AddressBookSyncEndEvent {
    ObjectWriter JSON_WRITER = new ObjectMapper().writer();

    Audience audience();

    Action action();

    URI path();

    default byte[] serialize() throws JsonProcessingException {
        return JSON_WRITER.writeValueAsBytes(this);
    }

    record ContactChangedEvent(Audience audience,
                               Action action,
                               URI path,
                               @JsonIgnore ContactUid uid,
                               long revision,
                               String etag,
                               @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode payload,
                               @JsonIgnore Optional<String> syncId) implements CommonContactOutboundEvent {
        public static class Builder {
            private Audience audience;
            private Action action;
            private URI path;
            private ContactUid uid;
            private Long revision;
            private String etag;
            private JsonNode payload;
            private Optional<String> syncId = Optional.empty();

            public Builder audience(Audience audience) {
                this.audience = audience;
                return this;
            }

            public Builder action(Action action) {
                this.action = action;
                return this;
            }

            public Builder path(URI path) {
                this.path = path;
                return this;
            }

            public Builder uid(ContactUid uid) {
                this.uid = uid;
                return this;
            }

            public Builder revision(long revision) {
                this.revision = revision;
                return this;
            }

            public Builder etag(String etag) {
                this.etag = etag;
                return this;
            }

            public Builder payload(JsonNode payload) {
                this.payload = payload;
                return this;
            }

            public Builder syncId(String syncId) {
                this.syncId = Optional.of(syncId);
                return this;
            }

            public ContactChangedEvent build() {
                Preconditions.checkNotNull(revision, "'revision' must not be null");
                return new ContactChangedEvent(audience, action, path, uid, revision, etag, payload, syncId);
            }
        }

        public static Builder builder() {
            return new Builder();
        }

        public ContactChangedEvent {
            Preconditions.checkNotNull(syncId, "'syncId' must not be null");
        }

        public ContactChangedEvent withSyncId(String syncId) {
            return new ContactChangedEvent(audience, action, path, uid, revision, etag, payload, Optional.of(syncId));
        }

        @JsonGetter("uid")
        public String uidAsString() {
            return uid.value();
        }

        @JsonGetter("syncId")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String syncIdAsString() {
            return syncId.orElse(null);
        }
    }

    record AddressBookDeletedEvent(Audience audience, URI path) implements CommonContactOutboundEvent {
        @Override
        @JsonGetter("action")
        public Action action() {
            return Action.DELETE;
        }
    }

    /**
     * Opens the republication of an address book: the {@code ADD} events carrying the same {@code syncId} follow.
     */
    record AddressBookSyncStartEvent(Audience audience, URI path, String syncId, long revision) implements CommonContactOutboundEvent {
        public AddressBookSyncStartEvent {
            Preconditions.checkNotNull(audience, "'audience' must not be null");
            Preconditions.checkNotNull(path, "'path' must not be null");
            Preconditions.checkArgument(StringUtils.isNotBlank(syncId), "'syncId' must not be blank");
        }

        @Override
        @JsonGetter("action")
        public Action action() {
            return Action.SYNC_START;
        }
    }

    /**
     * Closes the republication of an address book. {@code complete} tells whether every contact of the address book was
     * republished, {@code count} how many {@code ADD} events were sent.
     */
    record AddressBookSyncEndEvent(Audience audience, URI path, String syncId, long revision, boolean complete,
                                   long count) implements CommonContactOutboundEvent {
        public static class Builder {
            private Audience audience;
            private URI path;
            private String syncId;
            private Long revision;
            private Boolean complete;
            private Long count;

            public Builder audience(Audience audience) {
                this.audience = audience;
                return this;
            }

            public Builder path(URI path) {
                this.path = path;
                return this;
            }

            public Builder syncId(String syncId) {
                this.syncId = syncId;
                return this;
            }

            public Builder revision(long revision) {
                this.revision = revision;
                return this;
            }

            public Builder complete(boolean complete) {
                this.complete = complete;
                return this;
            }

            public Builder count(long count) {
                this.count = count;
                return this;
            }

            public AddressBookSyncEndEvent build() {
                Preconditions.checkNotNull(revision, "'revision' must not be null");
                Preconditions.checkNotNull(complete, "'complete' must not be null");
                Preconditions.checkNotNull(count, "'count' must not be null");
                return new AddressBookSyncEndEvent(audience, path, syncId, revision, complete, count);
            }
        }

        public static Builder builder() {
            return new Builder();
        }

        public AddressBookSyncEndEvent {
            Preconditions.checkNotNull(audience, "'audience' must not be null");
            Preconditions.checkNotNull(path, "'path' must not be null");
            Preconditions.checkArgument(StringUtils.isNotBlank(syncId), "'syncId' must not be blank");
            Preconditions.checkArgument(count >= 0, "'count' must not be negative");
        }

        @Override
        @JsonGetter("action")
        public Action action() {
            return Action.SYNC_END;
        }
    }

    enum Action {
        ADD, UPDATE, DELETE, SYNC_START, SYNC_END
    }

    @JsonSerialize(using = AudienceSerializer.class)
    sealed interface Audience permits Audience.User, Audience.Domain, Audience.Unknown {

        record User(Username username) implements Audience {
        }

        record Domain(org.apache.james.core.Domain domain) implements Audience {
        }

        record Unknown() implements Audience {
        }
    }

    class AudienceSerializer extends JsonSerializer<Audience> {
        @Override
        public void serialize(Audience audience, JsonGenerator generator, SerializerProvider serializers) throws IOException {
            generator.writeStartObject();
            switch (audience) {
                case Audience.User user -> generator.writeStringField("user", user.username().asString());
                case Audience.Domain domain -> generator.writeStringField("domain", domain.domain().asString());
                case Audience.Unknown ignored -> {
                }
            }
            generator.writeEndObject();
        }
    }
}
