package com.cobre.accounts.adapters.in.file;

import com.cobre.accounts.application.port.in.EventFile;
import com.cobre.accounts.application.port.in.FileStatus;
import com.cobre.accounts.application.port.in.ReadEvent;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads one uploaded file in any of the three forms of spec §6.2 and hands each event to
 * {@link CbmmEventTranslator}.
 *
 * <ul>
 *   <li>First non-blank character {@code [}: JSON array, read element by element. If the array is
 *       not valid JSON as a whole, the file is {@code UNREADABLE}.
 *   <li>First non-blank character {@code {}: a single object if nothing but blanks follows it;
 *       NDJSON if more content follows. If the first object cannot be read, the file is NDJSON when
 *       some line, alone, is a complete JSON object; otherwise it is a truncated object and the file
 *       is {@code UNREADABLE}.
 *   <li>Anything else, including an empty file or invalid UTF-8: {@code UNREADABLE}.
 * </ul>
 *
 * <p>An array element or NDJSON line that is not a JSON object is {@code MALFORMED_JSON}. A number
 * the parser cannot read is invalid JSON: longer than Jackson's default limit of 1000 characters
 * (a {@code JacksonException}) or with an exponent beyond BigDecimal's int scale, such as
 * {@code 1e99999999999999999999999} (a plain {@code NumberFormatException}, caught explicitly so it
 * never escapes the reader, D8). It makes an array or object {@code UNREADABLE} and an NDJSON line
 * {@code MALFORMED_JSON}.
 */
public final class EventFileReader {

    private static final char BOM = '﻿';

    private final CbmmEventTranslator translator;
    private final JsonMapper mapper = JsonMapper.builder()
            // Floats as BigDecimal parsed from the JSON text, never through double (D11).
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            // On by default in Jackson 3: it would reject the next array element when streaming.
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    /**
     * Only for a whole NDJSON line: two objects on one line are not one event. Not used while
     * streaming an array, where the next element would count as trailing content.
     */
    private final ObjectReader lineReader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public EventFileReader(CbmmEventTranslator translator) {
        this.translator = Objects.requireNonNull(translator, "translator");
    }

    public EventFile read(String fileName, byte[] content) {
        Objects.requireNonNull(fileName, "fileName");
        String text;
        try {
            text = decode(content);
        } catch (CharacterCodingException e) {
            return EventFile.unreadable(fileName);
        }
        int start = firstNonBlank(text);
        if (start == text.length()) {
            return EventFile.unreadable(fileName);
        }
        return switch (text.charAt(start)) {
            case '[' -> readArray(fileName, text);
            case '{' -> readObjectOrNdjson(fileName, text);
            default -> EventFile.unreadable(fileName);
        };
    }

    private EventFile readArray(String fileName, String text) {
        List<ReadEvent> events = new ArrayList<>();
        try (JsonParser parser = mapper.createParser(text)) {
            parser.nextToken(); // START_ARRAY
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
                if (token == null) {
                    return EventFile.unreadable(fileName);
                }
                // The element's source text, for payload_hash (§9): offsets are chars of `text`.
                int start = (int) parser.currentTokenLocation().getCharOffset();
                JsonNode element = mapper.readTree(parser);
                int end = (int) parser.currentTokenLocation().getCharOffset() + 1;
                events.add(translator.translate(fileName, events.size(), element, text.substring(start, end)));
            }
            if (parser.nextToken() != null) {
                return EventFile.unreadable(fileName);
            }
        } catch (JacksonException | NumberFormatException e) {
            return EventFile.unreadable(fileName);
        }
        return new EventFile(fileName, FileStatus.PROCESSED, events);
    }

    private EventFile readObjectOrNdjson(String fileName, String text) {
        JsonNode first;
        boolean moreContent;
        try (JsonParser parser = mapper.createParser(text)) {
            parser.nextToken();
            first = mapper.readTree(parser);
            moreContent = hasMoreContent(parser);
        } catch (JacksonException | NumberFormatException e) {
            return someLineIsAnObject(text) ? readNdjson(fileName, text) : EventFile.unreadable(fileName);
        }
        if (moreContent) {
            return readNdjson(fileName, text);
        }
        return new EventFile(fileName, FileStatus.PROCESSED, List.of(translator.translate(fileName, 0, first, text)));
    }

    private static boolean hasMoreContent(JsonParser parser) {
        try {
            return parser.nextToken() != null;
        } catch (JacksonException | NumberFormatException e) {
            return true; // garbage after the first object is still more content
        }
    }

    private EventFile readNdjson(String fileName, String text) {
        List<ReadEvent> events = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            int position = events.size();
            JsonNode node = parseLine(line);
            events.add(node == null || !node.isObject()
                    ? translator.malformed(fileName, position)
                    : translator.translate(fileName, position, node, line));
        }
        return new EventFile(fileName, FileStatus.PROCESSED, events);
    }

    private boolean someLineIsAnObject(String text) {
        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("{")) {
                JsonNode node = parseLine(trimmed);
                if (node != null && node.isObject()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The line as one JSON value, or null if it is not valid JSON. */
    private JsonNode parseLine(String line) {
        try {
            return lineReader.readTree(line);
        } catch (JacksonException | NumberFormatException e) {
            return null;
        }
    }

    private static String decode(byte[] content) throws CharacterCodingException {
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
        return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    }

    private static int firstNonBlank(String text) {
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }
}
