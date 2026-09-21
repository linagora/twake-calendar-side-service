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

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.google.common.base.Preconditions;

public record AddressBookReportXmlResponse(byte[] xml) {

    private static final XMLInputFactory XML_INPUT_FACTORY;

    static {
        XML_INPUT_FACTORY = XMLInputFactory.newFactory();
        XML_INPUT_FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XML_INPUT_FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    public record ContactObject(URI href, String cardData) {
        public ContactObject {
            Preconditions.checkNotNull(href, "href must not be null");
            Preconditions.checkNotNull(cardData, "cardData must not be null");
        }
    }

    public AddressBookReportXmlResponse {
        Preconditions.checkNotNull(xml, "xml must not be null");
    }

    public List<ContactObject> extractContactObjects() {
        XMLStreamReader reader = null;
        try {
            reader = XML_INPUT_FACTORY.createXMLStreamReader(new ByteArrayInputStream(xml));
            return readContactObjects(reader);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse CardDAV multistatus XML", e);
        } finally {
            close(reader);
        }
    }

    /**
     * Counts the contacts of the multistatus response: unlike {@link #extractContactObjects()} it does not
     * require the contact data to be part of the response.
     */
    public long countContacts() {
        XMLStreamReader reader = null;
        try {
            reader = XML_INPUT_FACTORY.createXMLStreamReader(new ByteArrayInputStream(xml));
            return countContacts(reader);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse CardDAV multistatus XML", e);
        } finally {
            close(reader);
        }
    }

    private long countContacts(XMLStreamReader reader) throws XMLStreamException {
        long count = 0;
        while (reader.hasNext()) {
            if (reader.next() == XMLStreamConstants.START_ELEMENT && isDavHref(reader.getName())) {
                count++;
            }
        }
        return count;
    }

    private List<ContactObject> readContactObjects(XMLStreamReader reader) throws XMLStreamException {
        List<ContactObject> items = new ArrayList<>();
        URI currentHref = null;
        String currentCardData = null;

        while (reader.hasNext()) {
            if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                QName name = reader.getName();

                if (isDavHref(name)) {
                    currentHref = URI.create(reader.getElementText());
                } else if (isAddressData(name)) {
                    currentCardData = reader.getElementText();
                }
            }

            if (currentHref != null && currentCardData != null) {
                items.add(new ContactObject(currentHref, currentCardData));
                currentHref = null;
                currentCardData = null;
            }
        }

        return items;
    }

    private static void close(XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException xmlStreamException) {
            throw new IllegalStateException("Failed to close the CardDAV multistatus XML reader", xmlStreamException);
        }
    }

    private static boolean isDavHref(QName name) {
        return "href".equals(name.getLocalPart())
            && "DAV:".equals(name.getNamespaceURI());
    }

    private static boolean isAddressData(QName name) {
        return "address-data".equals(name.getLocalPart())
            && "urn:ietf:params:xml:ns:carddav".equals(name.getNamespaceURI());
    }
}
