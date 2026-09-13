package com.logyard4j.logyard.runtime.tools;

import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Case-insensitive element and attribute access for Log4j2 configuration documents.
 *
 * <p>Log4j2 accepts both the canonical CamelCase spelling and lower-case variants of the
 * element and attribute names it defines, so every lookup here compares without regard to
 * case. Parsing itself is delegated to {@link LogbackXml}, which keeps the same doctype,
 * external entity, and XInclude rejection for both migrations.</p>
 */
final class Log4j2Xml {
    private Log4j2Xml() {
    }

    /** Parses without doctypes, external entities, or XInclude. */
    static Element parse(byte[] bytes) throws IOException {
        return LogbackXml.parse(bytes);
    }

    static boolean named(Element element, String name) {
        return element.getTagName().equalsIgnoreCase(name);
    }

    /** Returns every child element, whatever it is called: Log4j2 encodes the type in the tag. */
    static List<Element> elements(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Element element : elements(parent)) {
            if (named(element, name)) {
                result.add(element);
            }
        }
        return result;
    }

    static Element child(Element parent, String name) {
        List<Element> children = children(parent, name);
        return children.isEmpty() ? null : children.getFirst();
    }

    static String attribute(Element element, String name) {
        String value = rawAttribute(element, name);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Returns the XML-decoded attribute without trimming literal content. */
    static String rawAttribute(Element element, String name) {
        NamedNodeMap attributes = element.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Node attribute = attributes.item(index);
            if (attribute.getNodeName().equalsIgnoreCase(name)) {
                return attribute.getNodeValue();
            }
        }
        return null;
    }

    /** Returns the direct text of an element, which Log4j2's verbose form uses for values. */
    static String text(Element element) {
        if (element == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        NodeList nodes = element.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(node.getNodeValue());
            }
        }
        String trimmed = text.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Returns an attribute, falling back to the element text of a same-named child. */
    static String value(Element parent, String name) {
        String attribute = attribute(parent, name);
        return attribute != null ? attribute : text(child(parent, name));
    }
}
