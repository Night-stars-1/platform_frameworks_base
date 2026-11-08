/*
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.island;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, strictly bounded JSON reader for untrusted notification payloads.
 *
 * <p>Deliberately not {@code org.json}: the payload arrives from an arbitrary third party app and
 * is parsed inside SystemUI, so the parser itself must be a hard bound rather than a convenience.
 * Every limit below is enforced before the corresponding structure is allocated, the depth limit
 * caps recursion, and the value budget caps total memory. Anything beyond a limit is a
 * {@link ParseException} - callers degrade to a normalized ordinary-notification model instead of
 * crashing SystemUI.
 *
 * <p>The parser is pure Java (no Android dependency) so the bounded-reader behaviour is testable on
 * a plain JVM.
 */
public final class IslandJson {

    /** Maximum accepted payload size in UTF-16 code units (64 KiB). */
    public static final int MAX_INPUT_CHARS = 64 * 1024;
    /** Maximum container nesting depth. */
    public static final int MAX_DEPTH = 16;
    /** Maximum members of a single object or elements of a single array. */
    public static final int MAX_ELEMENTS = 256;
    /** Maximum length of a single decoded string. */
    public static final int MAX_STRING_CHARS = 8 * 1024;
    /** Maximum number of values (scalars + containers) in the whole document. */
    public static final int MAX_VALUES = 4096;

    private IslandJson() {
    }

    /** Raised for any payload that exceeds a bound or is not well formed. */
    public static final class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }

    /** Parsed JSON value; containers are immutable. */
    public static final class Value {
        public enum Kind { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

        private final Kind mKind;
        private final Map<String, Value> mMembers;
        private final List<Value> mElements;
        private final String mString;
        private final double mNumber;
        private final boolean mBoolean;

        private Value(Kind kind, Map<String, Value> members, List<Value> elements, String string,
                double number, boolean bool) {
            mKind = kind;
            mMembers = members;
            mElements = elements;
            mString = string;
            mNumber = number;
            mBoolean = bool;
        }

        static Value object(Map<String, Value> members) {
            return new Value(Kind.OBJECT, Collections.unmodifiableMap(members), null, null, 0, false);
        }

        static Value array(List<Value> elements) {
            return new Value(Kind.ARRAY, null, Collections.unmodifiableList(elements), null, 0, false);
        }

        static Value string(String value) {
            return new Value(Kind.STRING, null, null, value, 0, false);
        }

        static Value number(double value) {
            return new Value(Kind.NUMBER, null, null, null, value, false);
        }

        static Value bool(boolean value) {
            return new Value(Kind.BOOLEAN, null, null, null, 0, value);
        }

        static Value nul() {
            return new Value(Kind.NULL, null, null, null, 0, false);
        }

        public Kind kind() {
            return mKind;
        }

        public boolean isObject() {
            return mKind == Kind.OBJECT;
        }

        public boolean isArray() {
            return mKind == Kind.ARRAY;
        }

        public boolean isString() {
            return mKind == Kind.STRING;
        }

        public boolean isNumber() {
            return mKind == Kind.NUMBER;
        }

        /** Member lookup; returns null for missing members or non-objects. */
        public Value get(String key) {
            return mMembers == null || key == null ? null : mMembers.get(key);
        }

        /** True when this is an object containing {@code key}. */
        public boolean has(String key) {
            return get(key) != null;
        }

        /** Element lookup; returns null when out of range or not an array. */
        public Value get(int index) {
            if (mElements == null || index < 0 || index >= mElements.size()) {
                return null;
            }
            return mElements.get(index);
        }

        public int size() {
            if (mElements != null) {
                return mElements.size();
            }
            return mMembers == null ? 0 : mMembers.size();
        }

        /** Element list; empty when not an array. */
        public List<Value> elements() {
            return mElements == null ? Collections.emptyList() : mElements;
        }

        /** Decoded string, or {@code fallback} when this value is not a string. */
        public String asString(String fallback) {
            return mKind == Kind.STRING ? mString : fallback;
        }

        /** Decoded string, or null when this value is not a string. */
        public String asStringOrNull() {
            return mKind == Kind.STRING ? mString : null;
        }

        /** Integer view, clamped to {@code int} range; {@code fallback} when not numeric. */
        public int asInt(int fallback) {
            if (mKind == Kind.NUMBER) {
                if (mNumber >= Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
                if (mNumber <= Integer.MIN_VALUE) {
                    return Integer.MIN_VALUE;
                }
                if (Double.isNaN(mNumber)) {
                    return fallback;
                }
                return (int) mNumber;
            }
            if (mKind == Kind.BOOLEAN) {
                return mBoolean ? 1 : 0;
            }
            return fallback;
        }

        /** Long view, clamped to {@code long} range; {@code fallback} when not numeric. */
        public long asLong(long fallback) {
            if (mKind == Kind.NUMBER) {
                if (Double.isNaN(mNumber)) {
                    return fallback;
                }
                if (mNumber >= Long.MAX_VALUE) {
                    return Long.MAX_VALUE;
                }
                if (mNumber <= Long.MIN_VALUE) {
                    return Long.MIN_VALUE;
                }
                return (long) mNumber;
            }
            return fallback;
        }

        /** Boolean view; accepts JSON booleans and the strings {@code true}/{@code false}. */
        public boolean asBoolean(boolean fallback) {
            if (mKind == Kind.BOOLEAN) {
                return mBoolean;
            }
            if (mKind == Kind.STRING) {
                if ("true".equalsIgnoreCase(mString)) {
                    return true;
                }
                if ("false".equalsIgnoreCase(mString)) {
                    return false;
                }
            }
            return fallback;
        }

        /** True when the value is null, an empty string, an empty container or an empty boolean-ish. */
        public boolean isEmpty() {
            switch (mKind) {
                case NULL:
                    return true;
                case STRING:
                    return mString.isEmpty();
                case ARRAY:
                case OBJECT:
                    return size() == 0;
                default:
                    return false;
            }
        }

        @Override
        public String toString() {
            return mKind + (mKind == Kind.STRING ? "(" + mString + ")" : "");
        }
    }

    /**
     * Parses {@code text} as a single JSON value.
     *
     * @throws ParseException when the input is malformed, oversized, too deep, too wide or has
     *     trailing content
     */
    public static Value parse(String text) throws ParseException {
        if (text == null) {
            throw new ParseException("null payload");
        }
        if (text.length() > MAX_INPUT_CHARS) {
            throw new ParseException("payload exceeds " + MAX_INPUT_CHARS + " chars");
        }
        Cursor cursor = new Cursor(text);
        cursor.skipWhitespace();
        Value value = cursor.readValue(0);
        cursor.skipWhitespace();
        if (!cursor.atEnd()) {
            throw new ParseException("trailing content at " + cursor.position());
        }
        return value;
    }

    private static final class Cursor {
        private final String mText;
        private int mPos;
        private int mValues;

        Cursor(String text) {
            mText = text;
        }

        int position() {
            return mPos;
        }

        boolean atEnd() {
            return mPos >= mText.length();
        }

        void skipWhitespace() {
            while (mPos < mText.length()) {
                char c = mText.charAt(mPos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    mPos++;
                } else {
                    break;
                }
            }
        }

        private char peek() throws ParseException {
            if (mPos >= mText.length()) {
                throw new ParseException("unexpected end of input");
            }
            return mText.charAt(mPos);
        }

        private void expect(char expected) throws ParseException {
            if (mPos >= mText.length() || mText.charAt(mPos) != expected) {
                throw new ParseException("expected '" + expected + "' at " + mPos);
            }
            mPos++;
        }

        private void countValue() throws ParseException {
            if (++mValues > MAX_VALUES) {
                throw new ParseException("value budget exceeded");
            }
        }

        Value readValue(int depth) throws ParseException {
            if (depth > MAX_DEPTH) {
                throw new ParseException("nesting deeper than " + MAX_DEPTH);
            }
            countValue();
            char c = peek();
            switch (c) {
                case '{':
                    return readObject(depth);
                case '[':
                    return readArray(depth);
                case '"':
                    return Value.string(readString());
                case 't':
                    readLiteral("true");
                    return Value.bool(true);
                case 'f':
                    readLiteral("false");
                    return Value.bool(false);
                case 'n':
                    readLiteral("null");
                    return Value.nul();
                default:
                    return Value.number(readNumber());
            }
        }

        private void readLiteral(String literal) throws ParseException {
            if (!mText.startsWith(literal, mPos)) {
                throw new ParseException("invalid literal at " + mPos);
            }
            mPos += literal.length();
        }

        private Value readObject(int depth) throws ParseException {
            expect('{');
            Map<String, Value> members = new LinkedHashMap<>();
            skipWhitespace();
            if (mPos < mText.length() && mText.charAt(mPos) == '}') {
                mPos++;
                return Value.object(members);
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new ParseException("object key must be a string at " + mPos);
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Value value = readValue(depth + 1);
                if (members.size() >= MAX_ELEMENTS && !members.containsKey(key)) {
                    throw new ParseException("object wider than " + MAX_ELEMENTS);
                }
                members.put(key, value);
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    mPos++;
                    continue;
                }
                if (next == '}') {
                    mPos++;
                    return Value.object(members);
                }
                throw new ParseException("expected ',' or '}' at " + mPos);
            }
        }

        private Value readArray(int depth) throws ParseException {
            expect('[');
            List<Value> elements = new ArrayList<>();
            skipWhitespace();
            if (mPos < mText.length() && mText.charAt(mPos) == ']') {
                mPos++;
                return Value.array(elements);
            }
            while (true) {
                skipWhitespace();
                if (elements.size() >= MAX_ELEMENTS) {
                    throw new ParseException("array longer than " + MAX_ELEMENTS);
                }
                elements.add(readValue(depth + 1));
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    mPos++;
                    continue;
                }
                if (next == ']') {
                    mPos++;
                    return Value.array(elements);
                }
                throw new ParseException("expected ',' or ']' at " + mPos);
            }
        }

        private String readString() throws ParseException {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (mPos >= mText.length()) {
                    throw new ParseException("unterminated string");
                }
                char c = mText.charAt(mPos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                } else {
                    if (mPos >= mText.length()) {
                        throw new ParseException("unterminated escape");
                    }
                    char esc = mText.charAt(mPos++);
                    switch (esc) {
                        case '"': out.append('"'); break;
                        case '\\': out.append('\\'); break;
                        case '/': out.append('/'); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'u':
                            if (mPos + 4 > mText.length()) {
                                throw new ParseException("truncated \\u escape");
                            }
                            int code = 0;
                            for (int i = 0; i < 4; i++) {
                                int digit = Character.digit(mText.charAt(mPos + i), 16);
                                if (digit < 0) {
                                    throw new ParseException("bad \\u escape at " + mPos);
                                }
                                code = (code << 4) | digit;
                            }
                            mPos += 4;
                            out.append((char) code);
                            break;
                        default:
                            throw new ParseException("bad escape at " + mPos);
                    }
                }
                if (out.length() > MAX_STRING_CHARS) {
                    throw new ParseException("string longer than " + MAX_STRING_CHARS);
                }
            }
        }

        private double readNumber() throws ParseException {
            int start = mPos;
            if (mPos < mText.length() && (mText.charAt(mPos) == '-' || mText.charAt(mPos) == '+')) {
                mPos++;
            }
            boolean digits = false;
            while (mPos < mText.length() && isNumberChar(mText.charAt(mPos))) {
                if (mText.charAt(mPos) >= '0' && mText.charAt(mPos) <= '9') {
                    digits = true;
                }
                mPos++;
            }
            if (!digits) {
                throw new ParseException("invalid number at " + start);
            }
            String raw = mText.substring(start, mPos);
            try {
                double value = Double.parseDouble(raw);
                if (Double.isNaN(value) || Double.isInfinite(value)) {
                    throw new ParseException("non-finite number at " + start);
                }
                return value;
            } catch (NumberFormatException e) {
                throw new ParseException("invalid number at " + start);
            }
        }

        private static boolean isNumberChar(char c) {
            return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                    || c == '+' || c == '-';
        }
    }
}
