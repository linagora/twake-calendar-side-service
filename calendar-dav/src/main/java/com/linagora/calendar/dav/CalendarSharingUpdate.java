/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                                                   *
 ********************************************************************/

package com.linagora.calendar.dav;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.Strings;
import org.apache.james.core.Username;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;

public record CalendarSharingUpdate(@JsonProperty(value = "share", required = true) Share share) {
    private static final String MAILTO_PREFIX = "mailto:";

    public static class InvalidDavRightException extends IllegalArgumentException {
        public InvalidDavRightException() {
            super("Exactly one of 'dav:read', 'dav:read-write', 'dav:administration' must be true");
        }
    }

    public CalendarSharingUpdate {
        Preconditions.checkArgument(share != null, "'share' field is required");
    }

    public static CalendarSharingUpdate grant(AddSharee... additions) {
        return builder().grantAll(List.of(additions)).build();
    }

    public static CalendarSharingUpdate grant(Username username, DavRight davRight) {
        return grant(AddSharee.of(username, davRight));
    }

    public static CalendarSharingUpdate revoke(Username... usernames) {
        return builder().revokeAll(Arrays.stream(usernames).map(RemoveSharee::of).toList()).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public record Share(@JsonProperty("set") List<AddSharee> set,
                        @JsonProperty("remove") List<RemoveSharee> remove) {
        public Share {
            set = List.copyOf(Objects.requireNonNullElse(set, List.of()));
            remove = List.copyOf(Objects.requireNonNullElse(remove, List.of()));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AddSharee(@JsonProperty("dav:href") String davHref,
                            @JsonIgnore DavRight davRight) {
        public AddSharee {
            Preconditions.checkArgument(Strings.CI.startsWith(davHref, MAILTO_PREFIX),
                "'dav:href' must be a '" + MAILTO_PREFIX + "' URI");
            Objects.requireNonNull(davRight, "davRight must not be null");
        }

        @JsonCreator
        public static AddSharee fromJson(@JsonProperty("dav:href") String davHref,
                                         @JsonProperty("dav:read") Boolean read,
                                         @JsonProperty("dav:read-write") Boolean readWrite,
                                         @JsonProperty("dav:administration") Boolean administration) {
            List<DavRight> enabledRights = new ArrayList<>();
            if (Boolean.TRUE.equals(read)) {
                enabledRights.add(DavRight.READ);
            }
            if (Boolean.TRUE.equals(readWrite)) {
                enabledRights.add(DavRight.READ_WRITE);
            }
            if (Boolean.TRUE.equals(administration)) {
                enabledRights.add(DavRight.ADMINISTRATION);
            }
            if (enabledRights.size() != 1) {
                throw new InvalidDavRightException();
            }
            return new AddSharee(davHref, enabledRights.getFirst());
        }

        public static AddSharee of(String davHref, DavRight davRight) {
            return new AddSharee(davHref, davRight);
        }

        public static AddSharee of(Username username, DavRight davRight) {
            return of(toDavHref(username), davRight);
        }

        @JsonProperty("dav:read")
        public Boolean read() {
            return davRight == DavRight.READ ? true : null;
        }

        @JsonProperty("dav:read-write")
        public Boolean readWrite() {
            return davRight == DavRight.READ_WRITE ? true : null;
        }

        @JsonProperty("dav:administration")
        public Boolean administration() {
            return davRight == DavRight.ADMINISTRATION ? true : null;
        }
    }

    public record RemoveSharee(@JsonProperty("dav:href") String davHref) {
        public RemoveSharee {
            Preconditions.checkArgument(Strings.CI.startsWith(davHref, MAILTO_PREFIX),
                "'dav:href' must be a '" + MAILTO_PREFIX + "' URI");
        }

        public static RemoveSharee of(Username username) {
            return new RemoveSharee(toDavHref(username));
        }
    }

    public static final class Builder {
        private final List<AddSharee> additions = new ArrayList<>();
        private final List<RemoveSharee> removals = new ArrayList<>();

        public Builder grant(Username username, DavRight davRight) {
            return grant(AddSharee.of(username, davRight));
        }

        public Builder grant(String davHref, DavRight davRight) {
            return grant(AddSharee.of(davHref, davRight));
        }

        public Builder grant(AddSharee sharee) {
            additions.add(sharee);
            return this;
        }

        public Builder grantAll(Collection<AddSharee> sharees) {
            additions.addAll(sharees);
            return this;
        }

        public Builder revoke(Username username) {
            return revoke(RemoveSharee.of(username));
        }

        public Builder revoke(String davHref) {
            return revoke(new RemoveSharee(davHref));
        }

        public Builder revoke(RemoveSharee sharee) {
            removals.add(sharee);
            return this;
        }

        public Builder revokeAll(Collection<RemoveSharee> sharees) {
            removals.addAll(sharees);
            return this;
        }

        public CalendarSharingUpdate build() {
            return new CalendarSharingUpdate(new Share(additions, removals));
        }

        private Builder() {
        }
    }

    private static String toDavHref(Username username) {
        return MAILTO_PREFIX + username.asString();
    }
}
