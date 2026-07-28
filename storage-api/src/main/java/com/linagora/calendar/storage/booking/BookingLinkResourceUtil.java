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

package com.linagora.calendar.storage.booking;

import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.util.ValuePatch;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.model.ResourceId;

/**
 * Maps the booking link resources between the API - a flat array of resource id strings - and the domain.
 *
 * <pre>{@code ["68a1b2c3d4e5f60718293a4b", "68a1b2c3d4e5f60718293a4c"]}</pre>
 */
public class BookingLinkResourceUtil {

    public static final int MAX_RESOURCES = 20;

    public static List<ResourceId> parse(Optional<List<String>> raw) {
        return raw.map(BookingLinkResourceUtil::parse)
            .orElse(List.of());
    }

    /**
     * Parses the 'resources' of a patch request, the caller being responsible for telling an absent field
     * (keep) from a present one; an empty array is read as a removal.
     */
    public static ValuePatch<List<ResourceId>> parsePatch(Optional<List<String>> raw) {
        return raw.map(BookingLinkResourceUtil::parse)
            .filter(resources -> !resources.isEmpty())
            .map(ValuePatch::modifyTo)
            .orElseGet(ValuePatch::remove);
    }

    public static List<ResourceId> parse(List<String> raw) {
        List<ResourceId> resources = raw.stream()
            .map(BookingLinkResourceUtil::parseResourceId)
            .distinct()
            .toList();
        Preconditions.checkArgument(resources.size() <= MAX_RESOURCES,
            "'resources' must not contain more than %s entries", MAX_RESOURCES);
        return resources;
    }

    public static List<String> serialize(List<ResourceId> resources) {
        return resources.stream()
            .map(ResourceId::value)
            .toList();
    }

    private static ResourceId parseResourceId(String value) {
        String trimmed = StringUtils.trimToEmpty(value);
        Preconditions.checkArgument(!trimmed.isEmpty(), "'resources' entries must not be blank");
        return new ResourceId(trimmed);
    }

    private BookingLinkResourceUtil() {
    }
}
