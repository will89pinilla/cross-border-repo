package com.cobre.accounts.adapters.in.file;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.io.JsonStringEncoder;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code payload_hash} of spec §9: SHA-256 in hex over the canonical JSON of one event. Keys are
 * sorted recursively, there are no blanks, strings are re-escaped by Jackson and each number keeps
 * the text it had in the file ({@code 10.50}, {@code 10.5} and {@code 1.05e1} differ). The file form
 * (array, object, NDJSON) and its blanks do not change the hash.
 *
 * <p>Works on the event's source text, not on the tree: the tree would turn {@code 1e2} into
 * {@code 1E+2}. A repeated key keeps its last value, as the tree does.
 */
final class PayloadHash {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final JsonStringEncoder ENCODER = JsonStringEncoder.getInstance();

    private PayloadHash() {}

    /** @param eventText the source text of one JSON object that the reader already parsed */
    static String of(String eventText) {
        StringBuilder canonical = new StringBuilder(eventText.length());
        try (JsonParser parser = MAPPER.createParser(eventText)) {
            parser.nextToken();
            write(parser, canonical);
        }
        return sha256Hex(canonical);
    }

    private static void write(JsonParser parser, StringBuilder out) {
        JsonToken token = parser.currentToken();
        switch (token) {
            case START_OBJECT -> {
                Map<String, String> fields = new TreeMap<>();
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    String name = parser.currentName();
                    parser.nextToken();
                    StringBuilder value = new StringBuilder();
                    write(parser, value);
                    fields.put(name, value.toString());
                }
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, String> field : fields.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    quote(field.getKey(), out);
                    out.append(':').append(field.getValue());
                }
                out.append('}');
            }
            case START_ARRAY -> {
                out.append('[');
                boolean first = true;
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(parser, out);
                }
                out.append(']');
            }
            case VALUE_STRING -> quote(parser.getString(), out);
            // The number as written in the text, never re-formatted (spec §9).
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> out.append(parser.getText());
            case VALUE_TRUE -> out.append("true");
            case VALUE_FALSE -> out.append("false");
            case VALUE_NULL -> out.append("null");
            default -> throw new IllegalStateException("Unexpected token " + token + " in an event already parsed");
        }
    }

    private static void quote(String value, StringBuilder out) {
        out.append('"');
        ENCODER.quoteAsString(value, out);
        out.append('"');
    }

    private static String sha256Hex(CharSequence canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
