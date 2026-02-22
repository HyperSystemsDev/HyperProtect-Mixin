package com.hyperprotect.mixin.msg;

import java.awt.Color;
import java.util.Map;

/**
 * Named color constants for chat formatting.
 * Standard Minecraft color codes plus extended named colors.
 */
public final class ColorPalette {

    private ColorPalette() {}

    // Standard Minecraft chat colors (exact MC hex values)
    public static final Color BLACK         = new Color(0, 0, 0);         // &0
    public static final Color DARK_BLUE     = new Color(0, 0, 170);       // &1
    public static final Color DARK_GREEN    = new Color(0, 170, 0);       // &2
    public static final Color DARK_AQUA     = new Color(0, 170, 170);     // &3
    public static final Color DARK_RED      = new Color(170, 0, 0);       // &4
    public static final Color DARK_PURPLE   = new Color(170, 0, 170);     // &5
    public static final Color GOLD          = new Color(255, 170, 0);     // &6
    public static final Color GRAY          = new Color(170, 170, 170);   // &7
    public static final Color DARK_GRAY     = new Color(85, 85, 85);      // &8
    public static final Color BLUE          = new Color(85, 85, 255);     // &9
    public static final Color GREEN         = new Color(85, 255, 85);     // &a
    public static final Color AQUA          = new Color(85, 255, 255);    // &b
    public static final Color RED           = new Color(255, 85, 85);     // &c
    public static final Color LIGHT_PURPLE  = new Color(255, 85, 255);    // &d
    public static final Color YELLOW        = new Color(255, 255, 85);    // &e
    public static final Color WHITE         = new Color(255, 255, 255);   // &f

    // Extended colors (not part of MC &-codes, available by name only)
    public static final Color ORANGE        = new Color(255, 165, 0);
    public static final Color PINK          = new Color(255, 182, 193);
    public static final Color CYAN          = new Color(0, 255, 255);
    public static final Color BROWN         = new Color(139, 69, 19);
    public static final Color LIME          = new Color(50, 205, 50);
    public static final Color MAGENTA       = new Color(255, 0, 255);

    /**
     * Minecraft &-code to Color mapping (0-9, a-f).
     */
    static final Color[] SHORT_CODES = {
        BLACK,        // 0
        DARK_BLUE,    // 1
        DARK_GREEN,   // 2
        DARK_AQUA,    // 3
        DARK_RED,     // 4
        DARK_PURPLE,  // 5
        GOLD,         // 6
        GRAY,         // 7
        DARK_GRAY,    // 8
        BLUE,         // 9
        GREEN,        // a (10)
        AQUA,         // b (11)
        RED,          // c (12)
        LIGHT_PURPLE, // d (13)
        YELLOW,       // e (14)
        WHITE         // f (15)
    };

    /**
     * Immutable lookup table from lowercase name to Color.
     */
    static final Map<String, Color> BY_NAME = Map.ofEntries(
            Map.entry("black", BLACK),
            Map.entry("dark_blue", DARK_BLUE),
            Map.entry("dark_green", DARK_GREEN),
            Map.entry("dark_aqua", DARK_AQUA),
            Map.entry("dark_red", DARK_RED),
            Map.entry("dark_purple", DARK_PURPLE),
            Map.entry("gold", GOLD),
            Map.entry("gray", GRAY),
            Map.entry("dark_gray", DARK_GRAY),
            Map.entry("blue", BLUE),
            Map.entry("green", GREEN),
            Map.entry("aqua", AQUA),
            Map.entry("red", RED),
            Map.entry("light_purple", LIGHT_PURPLE),
            Map.entry("yellow", YELLOW),
            Map.entry("white", WHITE),
            Map.entry("orange", ORANGE),
            Map.entry("pink", PINK),
            Map.entry("cyan", CYAN),
            Map.entry("brown", BROWN),
            Map.entry("lime", LIME),
            Map.entry("magenta", MAGENTA)
    );

    /**
     * Looks up a color by name (case-insensitive).
     * @return the Color, or null if not found
     */
    public static Color byName(String name) {
        return BY_NAME.get(name.toLowerCase());
    }

    /**
     * Looks up a color by Minecraft short code character (0-9, a-f).
     * @return the Color, or null if not a valid code
     */
    public static Color byCode(char code) {
        int idx;
        if (code >= '0' && code <= '9') {
            idx = code - '0';
        } else if (code >= 'a' && code <= 'f') {
            idx = 10 + (code - 'a');
        } else if (code >= 'A' && code <= 'F') {
            idx = 10 + (code - 'A');
        } else {
            return null;
        }
        return SHORT_CODES[idx];
    }
}
