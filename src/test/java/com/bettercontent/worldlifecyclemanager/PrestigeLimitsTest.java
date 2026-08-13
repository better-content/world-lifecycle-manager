package com.bettercontent.worldlifecyclemanager;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PrestigeLimitsTest {
    @Test
    void acceptsBoundaryAndRejectsOversizedOrNegativeCounts() {
        assertDoesNotThrow(() -> PrestigeLimits.requireCount("biomes", PrestigeLimits.MAX_BIOMES,
                PrestigeLimits.MAX_BIOMES));
        assertDoesNotThrow(() -> PrestigeLimits.requireSize("biomes",
                Collections.nCopies(PrestigeLimits.MAX_BIOMES, "fixture"), PrestigeLimits.MAX_BIOMES));
        assertThrows(IllegalArgumentException.class, () -> PrestigeLimits.requireCount("biomes",
                PrestigeLimits.MAX_BIOMES + 1, PrestigeLimits.MAX_BIOMES));
        assertThrows(IllegalArgumentException.class, () -> PrestigeLimits.requireCount("biomes", -1,
                PrestigeLimits.MAX_BIOMES));
    }
}
