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

import static com.linagora.calendar.dav.CalDavClient.ICS_EXTENSION;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Preconditions;

public record CalendarReportXmlResponse(byte[] xml) {

    private static final XMLInputFactory XML_INPUT_FACTORY;

    static {
        XML_INPUT_FACTORY = XMLInputFactory.newFactory();
        XML_INPUT_FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XML_INPUT_FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    public CalendarReportXmlResponse {
        Preconditions.checkNotNull(xml, "xml must not be null");
    }

    public record CalendarObject(URI href, String calendarData) {
        public CalendarObject {
            Preconditions.checkNotNull(href, "href must not be null");
            Preconditions.checkNotNull(calendarData, "calendarData must not be null");
        }

        public byte[] calendarDataAsBytes() {
            return calendarData.getBytes(StandardCharsets.UTF_8);
        }

        public String eventPathId() {
            return StringUtils.substringAfterLast(href.getPath(), "/")
                .replace(ICS_EXTENSION,"");
        }
    }

    public List<CalendarObject> extractCalendarObjects() {
        XMLStreamReader reader = null;
        try {
            reader = XML_INPUT_FACTORY.createXMLStreamReader(new ByteArrayInputStream(xml));
            List<CalendarObject> items = new ArrayList<>();

            URI currentHref = null;
            String currentCalendarData = null;

            while (reader.hasNext()) {
                int event = reader.next();

                if (event == XMLStreamConstants.START_ELEMENT) {
                    QName name = reader.getName();

                    if (isDavHref(name)) {
                        currentHref = URI.create(reader.getElementText());
                    } else if (isCalendarData(name)) {
                        currentCalendarData = reader.getElementText();
                    }
                }

                if (currentHref != null && currentCalendarData != null) {
                    items.add(new CalendarObject(currentHref, currentCalendarData));
                    currentHref = null;
                    currentCalendarData = null;
                }
            }

            return items;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse CalDAV multistatus XML", e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException xmlStreamException) {
                    throw new IllegalStateException("Failed to parse CalDAV multistatus XML", xmlStreamException);
                }
            }
        }
    }

    private static boolean isDavHref(QName name) {
        return "href".equals(name.getLocalPart())
            && "DAV:".equals(name.getNamespaceURI());
    }

    private static boolean isCalendarData(QName name) {
        return "calendar-data".equals(name.getLocalPart())
            && "urn:ietf:params:xml:ns:caldav".equals(name.getNamespaceURI());
    }
}
