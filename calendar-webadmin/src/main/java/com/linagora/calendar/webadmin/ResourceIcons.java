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

package com.linagora.calendar.webadmin;

import java.util.Set;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;

/**
 * Icons a resource can be given: the ones served by the REST API under {@code /images/icon/{icon}.svg}
 * (see {@code calendar-rest-api/src/main/resources/icons/resources}).
 */
public final class ResourceIcons {
    public static final Set<String> SUPPORTED = ImmutableSortedSet.of(
        "access-point", "battery", "briefcase", "calculator", "calendar-month", "camera", "car", "caravan",
        "cellphone", "coffee", "credit-card", "description", "deskphone", "fax", "guitar-acoustic", "home",
        "kettle", "laptop", "layers", "meeting-room", "microphone", "motorbike", "office", "parking",
        "people-alt", "phone", "phone-iphone", "projector", "radio", "remote", "soccer", "sofa", "tablet",
        "train", "tv", "umbrella", "video", "video-camera-back", "view-comfy-alt", "wifi");

    public static void validate(String icon) {
        Preconditions.checkArgument(SUPPORTED.contains(icon),
            "Field 'icon' must be one of %s, got '%s'", SUPPORTED, icon);
    }

    private ResourceIcons() {
    }
}
