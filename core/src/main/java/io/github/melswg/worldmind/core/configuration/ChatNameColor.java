package io.github.melswg.worldmind.core.configuration;

import java.util.Arrays;
import java.util.Optional;

/** Portable vanilla palette choice for the Worldmind name in delivered chat. */
public enum ChatNameColor {
    BLACK("black"),
    DARK_BLUE("dark_blue"),
    DARK_GREEN("dark_green"),
    DARK_AQUA("dark_aqua"),
    DARK_RED("dark_red"),
    DARK_PURPLE("dark_purple"),
    GOLD("gold"),
    GRAY("gray"),
    DARK_GRAY("dark_gray"),
    BLUE("blue"),
    GREEN("green"),
    AQUA("aqua"),
    RED("red"),
    LIGHT_PURPLE("light_purple"),
    YELLOW("yellow"),
    WHITE("white");

    private final String profileValue;

    ChatNameColor(String profileValue) {
        this.profileValue = profileValue;
    }

    /** Exact v1 profile spelling; values are deliberately case-sensitive. */
    public String profileValue() {
        return profileValue;
    }

    public static Optional<ChatNameColor> fromProfileValue(String value) {
        return Arrays.stream(values()).filter(color -> color.profileValue.equals(value)).findFirst();
    }
}
