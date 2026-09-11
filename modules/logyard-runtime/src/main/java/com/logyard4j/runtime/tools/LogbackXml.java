package com.logyard4j.runtime.tools;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Hardened XML access for Logback configuration documents. */
final class LogbackXml {
    private LogbackXml() {
    }

    /** Parses without doctypes, external entities, or XInclude. */
    static Element parse(byte[] bytes) throws IOException {
        if (bytes.length > MigrationProperties.MAX_CHARACTERS) {
            throw new IllegalArgumentException("migration input exceeds 1MiB");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", 128);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(bytes))
                    .getDocumentElement();
        } catch (ParserConfigurationException impossible) {
            throw new IllegalStateException("XML parser features are unavailable", impossible);
        } catch (SAXException invalid) {
            throw new IllegalArgumentException("input is not a plain XML document: " + invalid.getMessage(), invalid);
        }
    }

    static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element element && element.getTagName().equals(name)) {
                result.add(element);
            }
        }
        return result;
    }

    static Element child(Element parent, String name) {
        List<Element> children = children(parent, name);
        return children.isEmpty() ? null : children.getFirst();
    }

    static String childText(Element parent, String name) {
        Element child = child(parent, name);
        if (child == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        NodeList nodes = child.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(node.getNodeValue());
            }
        }
        String trimmed = text.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String attribute(Element element, String name) {
        String value = element.getAttribute(name).trim();
        return value.isEmpty() ? null : value;
    }
}
