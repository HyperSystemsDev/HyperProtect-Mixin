package com.hyperprotect.mixin.msg;

import com.hypixel.hytale.server.core.Message;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Token-based chat message formatter.
 *
 * Parses {@code &}-code formatting into Hytale {@link Message} objects using a
 * two-pass approach: tokenize the input string, then assemble Message segments.
 *
 * Supported format codes:
 * <ul>
 *   <li>{@code &0}-{@code &f} — Minecraft color shortcodes</li>
 *   <li>{@code &#RRGGBB} or {@code &#RGB} — Hex colors</li>
 *   <li>{@code &red}, {@code &blue}, etc. — Named colors (see {@link ColorPalette})</li>
 *   <li>{@code &l} — Bold</li>
 *   <li>{@code &o} — Italic</li>
 *   <li>{@code &m} — Monospace</li>
 *   <li>{@code &r} — Reset all formatting</li>
 * </ul>
 */
public final class ChatFormatter {

    private ChatFormatter() {}

    // Token types — sealed interface with records
    sealed interface Token permits TextSegment, ColorShift, StyleShift, FormatReset {}
    record TextSegment(String text) implements Token {}
    record ColorShift(Color color) implements Token {}
    record StyleShift(char style) implements Token {} // 'l'=bold, 'o'=italic, 'm'=monospace
    record FormatReset() implements Token {}

    /**
     * Formats a string with {@code &}-codes into a Hytale {@link Message}.
     */
    public static Message format(String input) {
        if (input == null || input.isEmpty()) {
            return Message.raw("");
        }
        if (!input.contains("&")) {
            return Message.raw(input);
        }
        List<Token> tokens = tokenize(input);
        return assemble(tokens);
    }

    /**
     * Pass 1: Tokenize the input into a list of typed tokens.
     * Uses pure string operations (startsWith, charAt, indexOf) — no regex.
     */
    static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int len = input.length();
        int i = 0;

        while (i < len) {
            if (input.charAt(i) != '&' || i + 1 >= len) {
                text.append(input.charAt(i));
                i++;
                continue;
            }

            char next = input.charAt(i + 1);

            // Hex color: &#RRGGBB or &#RGB
            if (next == '#' && i + 2 < len) {
                int hexLen = countHexChars(input, i + 2);
                if (hexLen == 6 || hexLen == 3) {
                    flushText(tokens, text);
                    Color c = parseHex(input, i + 2, hexLen);
                    tokens.add(new ColorShift(c));
                    i += 2 + hexLen;
                    continue;
                }
            }

            // MC shortcode: &0-&9, &a-&f
            Color shortColor = ColorPalette.byCode(next);
            if (shortColor != null) {
                flushText(tokens, text);
                tokens.add(new ColorShift(shortColor));
                i += 2;
                continue;
            }

            // Formatting codes
            char lower = Character.toLowerCase(next);
            if (lower == 'l' || lower == 'o' || lower == 'm') {
                flushText(tokens, text);
                tokens.add(new StyleShift(lower));
                i += 2;
                continue;
            }
            if (lower == 'r') {
                flushText(tokens, text);
                tokens.add(new FormatReset());
                i += 2;
                continue;
            }

            // Named color: &red, &dark_blue, etc.
            int nameEnd = findNameEnd(input, i + 1);
            if (nameEnd > i + 1) {
                String name = input.substring(i + 1, nameEnd);
                Color namedColor = ColorPalette.byName(name);
                if (namedColor != null) {
                    flushText(tokens, text);
                    tokens.add(new ColorShift(namedColor));
                    i = nameEnd;
                    continue;
                }
            }

            // Not a recognized code — emit the '&' as literal text
            text.append('&');
            i++;
        }

        flushText(tokens, text);
        return tokens;
    }

    /**
     * Pass 2: Assemble tokens into a composed Message with formatting.
     */
    static Message assemble(List<Token> tokens) {
        boolean bold = false;
        boolean italic = false;
        boolean monospace = false;
        Color currentColor = null;
        Message result = null;

        for (Token token : tokens) {
            switch (token) {
                case TextSegment seg -> {
                    if (!seg.text().isEmpty()) {
                        Message segment = Message.raw(seg.text());
                        if (bold) segment = segment.bold(true);
                        if (italic) segment = segment.italic(true);
                        if (monospace) segment = segment.monospace(true);
                        if (currentColor != null) segment = segment.color(currentColor);
                        result = (result == null) ? segment : Message.join(result, segment);
                    }
                }
                case ColorShift shift -> currentColor = shift.color();
                case StyleShift shift -> {
                    switch (shift.style()) {
                        case 'l' -> bold = true;
                        case 'o' -> italic = true;
                        case 'm' -> monospace = true;
                    }
                }
                case FormatReset ignored -> {
                    bold = false;
                    italic = false;
                    monospace = false;
                    currentColor = null;
                }
            }
        }

        return result != null ? result : Message.raw("");
    }

    private static void flushText(List<Token> tokens, StringBuilder text) {
        if (!text.isEmpty()) {
            tokens.add(new TextSegment(text.toString()));
            text.setLength(0);
        }
    }

    /**
     * Counts consecutive hex characters starting at {@code start}.
     */
    private static int countHexChars(String s, int start) {
        int count = 0;
        for (int i = start; i < s.length() && count < 6; i++) {
            int d = Character.digit(s.charAt(i), 16);
            if (d < 0) break;
            count++;
        }
        return count;
    }

    /**
     * Parses hex color using Character.digit + bit shifting.
     */
    private static Color parseHex(String s, int start, int hexLen) {
        if (hexLen == 3) {
            int r = Character.digit(s.charAt(start), 16);
            int g = Character.digit(s.charAt(start + 1), 16);
            int b = Character.digit(s.charAt(start + 2), 16);
            return new Color((r << 4) | r, (g << 4) | g, (b << 4) | b);
        }
        int rgb = 0;
        for (int i = 0; i < 6; i++) {
            rgb = (rgb << 4) | Character.digit(s.charAt(start + i), 16);
        }
        return new Color(rgb);
    }

    /**
     * Finds the end of a named color starting at {@code start}.
     * Accepts letters and underscores.
     */
    private static int findNameEnd(String s, int start) {
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_') {
                i++;
            } else {
                break;
            }
        }
        return i;
    }
}
