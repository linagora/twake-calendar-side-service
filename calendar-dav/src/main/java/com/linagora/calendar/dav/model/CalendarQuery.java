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

package com.linagora.calendar.dav.model;

import static net.fortuna.ical4j.model.Parameter.PARTSTAT;

import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;

import org.apache.james.core.Username;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.parameter.PartStat;

public record CalendarQuery(List<PropFilter> propFilters) {

    public static CalendarQuery ofFilters(PropFilter... propFilters) {
        return new CalendarQuery(List.of(propFilters));
    }

    private static final XMLOutputFactory XML_OUTPUT_FACTORY = XMLOutputFactory.newInstance();

    public interface PropFilter {

        String name();
    }

    public record TimeRangePropFilter(String name, Instant end) implements PropFilter {

        public static TimeRangePropFilter dtStampBefore(Instant end) {
            return new TimeRangePropFilter(Property.DTSTAMP, end);
        }

        public static TimeRangePropFilter lastModifiedBefore(Instant end) {
            return new TimeRangePropFilter(Property.LAST_MODIFIED, end);
        }

        public static TimeRangePropFilter dtStartBefore(Instant end) {
            return new TimeRangePropFilter(Property.DTSTART, end);
        }
    }

    public record AttendeePropFilter(Username attendee, PartStat partStat) implements PropFilter {

        public static AttendeePropFilter declined(Username username) {
            return new AttendeePropFilter(username, PartStat.DECLINED);
        }

        @Override
        public String name() {
            return Property.ATTENDEE;
        }
    }

    interface PropFilterXmlWriter<T extends PropFilter> {
        void write(T filter, XMLStreamWriter w) throws Exception;
    }

    interface CalDavXml {
        String PREFIX_CALDAV = "C";
        String PREFIX_DAV = "D";

        String ELEMENT_CALENDAR_QUERY = "calendar-query";
        String ELEMENT_PROP = "prop";
        String ELEMENT_CALENDAR_DATA = "calendar-data";
        String ELEMENT_FILTER = "filter";
        String ELEMENT_COMP_FILTER = "comp-filter";
        String ELEMENT_PROP_FILTER = "prop-filter";
        String ELEMENT_PARAM_FILTER = "param-filter";

        String ATTR_NAME = "name";
        String ATTR_END = "end";

        String CALDAV_NS = "urn:ietf:params:xml:ns:caldav";
        String DAV_NS = "DAV:";
    }

    static final class TimeRangePropFilterXmlWriter implements PropFilterXmlWriter<TimeRangePropFilter> {
        static final String ELEMENT_TIME_RANGE = "time-range";
        static final DateTimeFormatter CALDAV_UTC_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC);

        @Override
        public void write(TimeRangePropFilter timeRangeFilter, XMLStreamWriter w) throws Exception {
            w.writeStartElement(CalDavXml.PREFIX_CALDAV, CalDavXml.ELEMENT_PROP_FILTER, CalDavXml.CALDAV_NS);
            w.writeAttribute(CalDavXml.ATTR_NAME, timeRangeFilter.name());

            w.writeEmptyElement(CalDavXml.PREFIX_CALDAV, ELEMENT_TIME_RANGE, CalDavXml.CALDAV_NS);
            w.writeAttribute(CalDavXml.ATTR_END, CALDAV_UTC_FORMAT.format(timeRangeFilter.end()));

            w.writeEndElement();
        }
    }

    static final class AttendeePropFilterXmlWriter implements PropFilterXmlWriter<AttendeePropFilter> {
        private static final String ELEMENT_TEXT_MATCH = "text-match";
        private static final String ATTR_COLLATION = "collation";
        private static final String VALUE_COLLATION_ASCII_CASEMAP = "i;ascii-casemap";
        private static final String MAIL_TO_PREFIX = "mailto:";

        @Override
        public void write(AttendeePropFilter filter, XMLStreamWriter w) throws Exception {
            w.writeStartElement(CalDavXml.PREFIX_CALDAV, CalDavXml.ELEMENT_PROP_FILTER, CalDavXml.CALDAV_NS);
            w.writeAttribute(CalDavXml.ATTR_NAME, filter.name());

            w.writeStartElement(CalDavXml.PREFIX_CALDAV, ELEMENT_TEXT_MATCH, CalDavXml.CALDAV_NS);
            w.writeAttribute(ATTR_COLLATION, VALUE_COLLATION_ASCII_CASEMAP);
            w.writeCharacters(MAIL_TO_PREFIX + filter.attendee().asString());
            w.writeEndElement();

            w.writeStartElement(CalDavXml.PREFIX_CALDAV, CalDavXml.ELEMENT_PARAM_FILTER, CalDavXml.CALDAV_NS);
            w.writeAttribute(CalDavXml.ATTR_NAME, PARTSTAT);

            w.writeStartElement(CalDavXml.PREFIX_CALDAV, ELEMENT_TEXT_MATCH, CalDavXml.CALDAV_NS);
            w.writeAttribute(ATTR_COLLATION, VALUE_COLLATION_ASCII_CASEMAP);
            w.writeCharacters(filter.partStat().getValue());
            w.writeEndElement();

            w.writeEndElement(); // param-filter
            w.writeEndElement(); // prop-filter
        }
    }

    public String toCalendarQueryReport() throws Exception {
        StringWriter stringWriter = new StringWriter();
        XMLStreamWriter writer = XML_OUTPUT_FACTORY.createXMLStreamWriter(stringWriter);

        try {
            writeCalendarQueryRoot(writer);
            writePropSection(writer);
            writeFilterSection(writer);

            writer.writeEndElement();
            writer.writeEndDocument();
        } finally {
            writer.close();
        }

        return stringWriter.toString();
    }

    private void writeCalendarQueryRoot(XMLStreamWriter writer) throws Exception {
        writer.setPrefix(CalDavXml.PREFIX_CALDAV, CalDavXml.CALDAV_NS);
        writer.setPrefix(CalDavXml.PREFIX_DAV, CalDavXml.DAV_NS);

        writer.writeStartElement(CalDavXml.PREFIX_CALDAV, CalDavXml.ELEMENT_CALENDAR_QUERY, CalDavXml.CALDAV_NS);
        writer.writeNamespace(CalDavXml.PREFIX_CALDAV, CalDavXml.CALDAV_NS);
        writer.writeNamespace(CalDavXml.PREFIX_DAV, CalDavXml.DAV_NS);
    }

    private void writePropSection(XMLStreamWriter writer) throws Exception {
        writer.writeStartElement(CalDavXml.PREFIX_DAV, CalDavXml.ELEMENT_PROP, CalDavXml.DAV_NS);
        writer.writeEmptyElement(CalDavXml.PREFIX_CALDAV, CalDavXml.ELEMENT_CALENDAR_DATA, CalDavXml.CALDAV_NS);
        writer.writeEndElement();
    }

    private void writeFilterSection(XMLStreamWriter writer) throws Exception {
        writer.writeStartElement(CalDavXml.PREFIX_CALDAV, CalDavXml.ELEMENT_FILTER, CalDavXml.CALDAV_NS);

        writeVCalendarFilter(writer);

        writer.writeEndElement(); // filter
    }

    private void writeVCalendarFilter(XMLStreamWriter writer) throws Exception {
        writer.writeStartElement(CalDavXml.PREFIX_CALDAV, CalDavXml.ELEMENT_COMP_FILTER, CalDavXml.CALDAV_NS);
        writer.writeAttribute(CalDavXml.ATTR_NAME, Calendar.VCALENDAR);

        writeVEventFilter(writer);

        writer.writeEndElement(); // VCALENDAR comp-filter
    }

    private void writeVEventFilter(XMLStreamWriter writer) throws Exception {
        writer.writeStartElement(CalDavXml.PREFIX_CALDAV, CalDavXml.ELEMENT_COMP_FILTER, CalDavXml.CALDAV_NS);
        writer.writeAttribute(CalDavXml.ATTR_NAME, Component.VEVENT);

        for (PropFilter propFilter : propFilters) {
            switch (propFilter) {
                case TimeRangePropFilter timeRange -> new TimeRangePropFilterXmlWriter().write(timeRange, writer);
                case AttendeePropFilter attendee -> new AttendeePropFilterXmlWriter().write(attendee, writer);
                default -> throw new IllegalArgumentException("Unknown PropFilter type: " + propFilter.getClass());
            }
        }

        writer.writeEndElement(); // VEVENT comp-filter
    }
}
