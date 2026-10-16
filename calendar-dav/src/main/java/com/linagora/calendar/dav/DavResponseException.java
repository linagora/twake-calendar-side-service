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

package com.linagora.calendar.dav;

import java.io.StringReader;
import java.util.Optional;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.lang3.StringUtils;

/**
 * Raised when the DAV server answers an unexpected HTTP status. Carries the status code and the
 * raw response body as data, so that callers can react to the DAV refusal without exposing the body.
 */
public class DavResponseException extends DavClientException {
    private static final String SABRE_NAMESPACE = "http://sabredav.org/ns";
    private static final String SABRE_MESSAGE_ELEMENT = "message";
    private static final XMLInputFactory XML_INPUT_FACTORY = createXmlInputFactory();

    private static XMLInputFactory createXmlInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        return factory;
    }

    private final int statusCode;
    private final String summary;
    private final String responseBody;

    /**
     * @param statusCode the HTTP status answered by the DAV server
     * @param action what was attempted, e.g. {@code deleting calendar '/calendars/abc/def.json'}
     * @param responseBody the raw body answered by the DAV server
     */
    public DavResponseException(int statusCode, String action, String responseBody) {
        super(summary(statusCode, action) + "\n" + responseBody);
        this.statusCode = statusCode;
        this.summary = summary(statusCode, action);
        this.responseBody = responseBody;
    }

    private static String summary(int statusCode, String action) {
        return "Unexpected status code: %d when %s".formatted(statusCode, action);
    }

    public int statusCode() {
        return statusCode;
    }

    /**
     * The error description without the DAV response body.
     */
    public String summary() {
        return summary;
    }

    public String responseBody() {
        return responseBody;
    }

    /**
     * The human readable {@code <s:message>} of a Sabre error document, if any.
     */
    public Optional<String> davMessage() {
        if (StringUtils.isBlank(responseBody)) {
            return Optional.empty();
        }
        try {
            return readSabreMessage(XML_INPUT_FACTORY.createXMLStreamReader(new StringReader(responseBody)))
                .map(String::trim)
                .filter(StringUtils::isNotBlank);
        } catch (XMLStreamException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> readSabreMessage(XMLStreamReader reader) throws XMLStreamException {
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT
                    && SABRE_NAMESPACE.equals(reader.getNamespaceURI())
                    && SABRE_MESSAGE_ELEMENT.equals(reader.getLocalName())) {
                    return Optional.of(reader.getElementText());
                }
            }
            return Optional.empty();
        } finally {
            reader.close();
        }
    }
}
