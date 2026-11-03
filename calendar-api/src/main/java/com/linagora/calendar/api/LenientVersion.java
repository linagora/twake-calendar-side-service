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

import static net.fortuna.ical4j.model.property.immutable.ImmutableVersion.VERSION_2_0;

import net.fortuna.ical4j.model.Content;
import net.fortuna.ical4j.model.ParameterList;
import net.fortuna.ical4j.model.PropertyFactory;
import net.fortuna.ical4j.model.property.Version;

/**
 * A VERSION property that splits a {@code min;max} value at its semicolon.
 *
 * <p>ical4j's {@link Version} drops the last character of the minimum version and keeps the semicolon in the maximum
 * version: {@code 1.0;2.0} becomes {@code 1.;;2.0}, loses one more character at each parse or copy, and finally makes
 * the copy throw a {@link StringIndexOutOfBoundsException} (eg on {@code ;;B}).
 */
public class LenientVersion extends Version {

    public static class Factory extends Content.Factory implements PropertyFactory<Version> {
        private static final long serialVersionUID = 1L;

        public Factory() {
            super(VERSION);
        }

        @Override
        public Version createProperty(ParameterList parameters, String value) {
            if (parameters.getAll().isEmpty() && VALUE_2_0.equals(value)) {
                return VERSION_2_0;
            }
            return new LenientVersion(parameters, value);
        }

        @Override
        public Version createProperty() {
            return new LenientVersion(new ParameterList(), "");
        }
    }

    private static final long serialVersionUID = 1L;
    private static final char MIN_MAX_SEPARATOR = ';';

    public LenientVersion(ParameterList parameters, String value) {
        super(parameters, null, value);
        setValue(value);
    }

    @Override
    public void setValue(String value) {
        int separatorIndex = value.indexOf(MIN_MAX_SEPARATOR);
        if (separatorIndex >= 0) {
            setMinVersion(value.substring(0, separatorIndex));
            setMaxVersion(value.substring(separatorIndex + 1));
        } else {
            setMinVersion(null);
            setMaxVersion(value);
        }
    }

    @Override
    protected PropertyFactory<Version> newFactory() {
        return new Factory();
    }
}
