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

import net.fortuna.ical4j.model.Encodable;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.ParameterList;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyFactory;
import net.fortuna.ical4j.model.parameter.Value;
import net.fortuna.ical4j.model.property.XProperty;
import net.fortuna.ical4j.validate.ValidationResult;

/**
 * An extension or unknown property whose VALUE is not TEXT, keeping its value as received.
 *
 * <p>ical4j decodes the value of an {@link XProperty} when reading it, but does not encode it back when writing it if
 * its VALUE is not TEXT: a backslash-n becomes a real line break, and the rendered calendar another structure. This
 * property is not {@link Encodable}, thus ical4j neither decodes nor encodes its value, RFC 5545 defining these
 * escapes for TEXT values only.
 */
public class NonTextExtensionProperty extends Property {

    /**
     * Creates the properties no other factory supports, in place of the ical4j fallback, thus it comes last: an
     * {@link XProperty} for a TEXT value, a {@link NonTextExtensionProperty} otherwise.
     *
     * <p>ical4j gives the property name to {@link #supports(String)} only, right before creating the property with
     * the same factory: an instance serves one property.
     */
    public static class Factory implements PropertyFactory<Property> {
        private static final long serialVersionUID = 1L;

        private String name;

        public Factory() {
        }

        private Factory(String name) {
            this.name = name;
        }

        @Override
        public boolean supports(String name) {
            this.name = name;
            return true;
        }

        @Override
        public Property createProperty(ParameterList parameters, String value) {
            if (isText(parameters)) {
                return new XProperty(name, parameters, value);
            }
            return new NonTextExtensionProperty(name, parameters, value);
        }

        @Override
        public Property createProperty() {
            return new XProperty(name);
        }

        private static boolean isText(ParameterList parameters) {
            return parameters.<Parameter>getParameter(Parameter.VALUE)
                .map(Value.TEXT::equals)
                .orElse(true);
        }
    }

    private static final long serialVersionUID = 1L;

    private String value;

    public NonTextExtensionProperty(String name, ParameterList parameters, String value) {
        super(name, parameters);
        this.value = value;
    }

    @Override
    public String getValue() {
        return value;
    }

    @Override
    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public ValidationResult validate() {
        return ValidationResult.EMPTY;
    }

    @Override
    protected PropertyFactory<Property> newFactory() {
        return new Factory(getName());
    }
}
