package com.bettercontent.prestige;

import java.util.List;

final class PrestigeLimits {
    static final int MAX_BIOMES = 512;
    static final int MAX_BIOME_FILE_LINES = 1024;

    private PrestigeLimits() {}

    static void requireSize(String label, List<?> values, int maximum) {
        requireCount(label, values.size(), maximum);
    }

    static void requireCount(String label, int count, int maximum) {
        if (count < 0 || count > maximum) throw new IllegalArgumentException(label + " exceeds limit " + maximum);
    }
}
