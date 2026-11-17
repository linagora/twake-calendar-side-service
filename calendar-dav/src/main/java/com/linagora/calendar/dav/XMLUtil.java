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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.http.message.BasicLineParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.google.common.collect.ImmutableList;
import com.linagora.calendar.dav.dto.CalendarMultiGetEntry;

public class XMLUtil {

    public static class DavNamespaceContext implements NamespaceContext {
        @Override
        public String getNamespaceURI(String prefix) {
            if ("d".equals(prefix)) {
                return "DAV:";
            }
            return javax.xml.XMLConstants.NULL_NS_URI;
        }

        @Override
        public String getPrefix(String namespaceURI) {
            return null;
        }

        @Override
        public java.util.Iterator<String> getPrefixes(String namespaceURI) {
            return null;
        }
    }

    private static final DocumentBuilderFactory DOCUMENT_BUILDER_FACTORY;

    static {
        try {
            DOCUMENT_BUILDER_FACTORY = DocumentBuilderFactory.newInstance();
            DOCUMENT_BUILDER_FACTORY.setNamespaceAware(true); // Enable namespace awareness

            //REDHAT
            //https://www.blackhat.com/docs/us-15/materials/us-15-Wang-FileCry-The-New-Age-Of-XXE-java-wp.pdf
            DOCUMENT_BUILDER_FACTORY.setAttribute(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            DOCUMENT_BUILDER_FACTORY.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            DOCUMENT_BUILDER_FACTORY.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            //OWASP
            //https://cheatsheetseries.owasp.org/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.html
            DOCUMENT_BUILDER_FACTORY.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DOCUMENT_BUILDER_FACTORY.setFeature("http://xml.org/sax/features/external-general-entities", false);
            DOCUMENT_BUILDER_FACTORY.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            // Disable external DTDs as well
            DOCUMENT_BUILDER_FACTORY.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            // and these as well, per Timothy Morgan's 2014 paper: "XML Schema, DTD, and Entity Attacks"
            DOCUMENT_BUILDER_FACTORY.setXIncludeAware(false);
            DOCUMENT_BUILDER_FACTORY.setExpandEntityReferences(false);
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<String> extractEventIdsFromXml(byte[] xml) throws ParserConfigurationException, IOException, SAXException, XPathExpressionException {
        Document doc = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder()
            .parse(new java.io.ByteArrayInputStream(xml));
        XPath xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(new DavNamespaceContext());
        String expression = "/d:multistatus/d:response/d:href";
        NodeList nodes = (NodeList) xpath.evaluate(expression, doc, XPathConstants.NODESET);
        ImmutableList.Builder<String> eventIds = new ImmutableList.Builder<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            String href = nodes.item(i).getTextContent();
            if (href.endsWith(".ics")) {
                String filename = href.substring(href.lastIndexOf('/') + 1, href.length() - 4);
                eventIds.add(filename);
            }
        }
        return eventIds.build();
    }

    static List<CalendarMultiGetEntry> extractCalendarMultiGetEntries(byte[] xml) throws ParserConfigurationException, IOException, SAXException {
        return children(parseMultistatus(xml), "DAV:", "response")
            .map(XMLUtil::calendarMultiGetEntry)
            .toList();
    }

    public static Element parseMultistatus(byte[] xml) throws ParserConfigurationException, IOException, SAXException {
        Element root = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder().parse(new ByteArrayInputStream(xml)).getDocumentElement();
        if (!"DAV:".equals(root.getNamespaceURI()) || !"multistatus".equals(root.getLocalName())) {
            throw new DavClientException("Missing DAV:multistatus in REPORT response");
        }
        return root;
    }

    private static CalendarMultiGetEntry calendarMultiGetEntry(Element response) {
        URI href = URI.create(children(response, "DAV:", "href").findFirst()
            .orElseThrow(() -> new DavClientException("Missing DAV:href in calendar-multiget response"))
            .getTextContent().strip());
        int status = statusCode(response);
        if (status != 0) {
            return new CalendarMultiGetEntry(href, status, "");
        }
        return children(response, "DAV:", "propstat")
            .flatMap(propstat -> children(propstat, "DAV:", "prop")
                .flatMap(prop -> children(prop, "urn:ietf:params:xml:ns:caldav", "calendar-data"))
                .map(data -> new CalendarMultiGetEntry(href, statusCode(propstat), data.getTextContent())))
            .findFirst()
            .orElseGet(() -> new CalendarMultiGetEntry(href, 0, ""));
    }

    private static int statusCode(Element element) {
        return children(element, "DAV:", "status").findFirst()
            .map(status -> BasicLineParser.parseStatusLine(status.getTextContent().strip(), null).getStatusCode())
            .orElse(0);
    }

    private static Stream<Element> children(Element parent, String namespace, String name) {
        return Stream.iterate(parent.getFirstChild(), Objects::nonNull, Node::getNextSibling)
            .filter(node -> namespace.equals(node.getNamespaceURI()) && name.equals(node.getLocalName()))
            .map(Element.class::cast);
    }

    /**
     * Parses a {@code PROPFIND} multistatus response requesting {@code DAV:current-user-privilege-set}
     * and tells whether the current (impersonated) principal is granted a write-like privilege
     * ({@code DAV:write}, {@code DAV:write-content} or {@code DAV:bind}) on the resource.
     */
    public static boolean hasWritePrivilege(byte[] xml) throws ParserConfigurationException, IOException, SAXException, XPathExpressionException {
        Document doc = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder()
            .parse(new java.io.ByteArrayInputStream(xml));
        XPath xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(new DavNamespaceContext());
        String expression = "//*[local-name()='current-user-privilege-set']/*[local-name()='privilege']"
            + "/*[local-name()='write' or local-name()='write-content' or local-name()='bind']";
        NodeList nodes = (NodeList) xpath.evaluate(expression, doc, XPathConstants.NODESET);
        return nodes.getLength() > 0;
    }
}
