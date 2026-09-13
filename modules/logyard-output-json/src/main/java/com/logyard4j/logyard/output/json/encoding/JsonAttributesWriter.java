package com.logyard4j.logyard.output.json.encoding;

import com.logyard4j.logyard.api.event.AttributeSet;

import java.util.HashSet;
import java.util.Set;

/** Applies attribute selection before any profile-specific placement or value projection. */
final class JsonAttributesWriter {
    private final JsonWriter json;
    private final JsonProfile profile;
    private final JsonAttributeTransform transform;
    private final JsonWriter labelJson;
    private final JsonBuffer labelBuffer;

    JsonAttributesWriter(JsonWriter json, JsonProfile profile) {
        this.json = json;
        this.profile = profile;
        transform = profile.attributes();
        labelBuffer = profile.ecs() ? new JsonBuffer(128, JsonOutputLimits.MAX_RECORD_CHARACTERS) : null;
        labelJson = labelBuffer == null ? null : new JsonWriter(labelBuffer);
    }

    void reset() {
        if (labelJson != null) labelJson.reset();
    }

    int retainedLabelCapacity() {
        return labelBuffer == null ? 0 : labelBuffer.capacity();
    }

    boolean write(boolean first, AttributeSet attributes) {
        if (transform.mode() == JsonAttributeTransform.Mode.DROP || !profile.emits("attributes")) {
            return first;
        }
        boolean nested = transform.mode() == JsonAttributeTransform.Mode.NESTED;
        if (nested) {
            first = field(first, profile.outputName("attributes"));
            json.beginObject();
        }
        boolean firstAttribute = nested || first;
        Set<String> names = transform.requiresCollisionCheck() ? new HashSet<>() : null;
        for (int index = 0; index < attributes.size(); index++) {
            String source = attributes.keyAt(index);
            if (!transform.includes(source)) {
                continue;
            }
            String output = transform.outputName(source);
            if (names != null && !names.add(output)) {
                throw new IllegalArgumentException("JSON attribute transforms produce duplicate field '" + output + "'");
            }
            Object value = attributes.valueAt(index);
            if (nested && profile.ecs() && EcsProjection.traceField(output, value) != null) {
                continue;
            }
            firstAttribute = field(firstAttribute, nested ? output : transform.prefix() + output);
            if (nested && profile.ecs()) {
                label(value);
            } else {
                json.value(value);
            }
        }
        if (!nested) {
            return firstAttribute;
        }
        json.endObject();
        return profile.ecs() ? traces(first, attributes) : first;
    }

    private boolean traces(boolean first, AttributeSet attributes) {
        for (int index = 0; index < attributes.size(); index++) {
            String source = attributes.keyAt(index);
            if (transform.includes(source)) {
                Object value = attributes.valueAt(index);
                String destination = EcsProjection.traceField(transform.outputName(source), value);
                if (destination != null) {
                    first = field(first, destination);
                    json.string((String) value);
                }
            }
        }
        return first;
    }

    private void label(Object value) {
        if (value instanceof String string) {
            json.string(string);
        } else {
            labelJson.reset();
            labelJson.value(value);
            json.string(labelBuffer.result());
            if (labelJson.traversalTruncated()) {
                json.markTraversalTruncated();
            }
        }
    }

    private boolean field(boolean first, String name) {
        if (!first) {
            json.comma();
        }
        json.name(name);
        return false;
    }
}
