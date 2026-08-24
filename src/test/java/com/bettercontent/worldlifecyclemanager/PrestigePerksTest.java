package com.bettercontent.worldlifecyclemanager;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PrestigePerksTest {
    @Test void upcomingGenerationProvidesOnePointAndCapsAtSix() {
        assertEquals(1, build(0, EnumSet.noneOf(PrestigePerks.Perk.class)).budget());
        assertEquals(6, build(99, EnumSet.allOf(PrestigePerks.Perk.class)).budget());
    }

    @Test void capstonesRequireTheirBranchRoots() {
        assertThrows(IllegalArgumentException.class, () -> PrestigePerks.validateShape(build(1,
                EnumSet.of(PrestigePerks.Perk.FRONTIER_ATTUNEMENT))));
        assertDoesNotThrow(() -> PrestigePerks.validateShape(build(1,
                EnumSet.of(PrestigePerks.Perk.EXPANDED_ATTUNEMENT, PrestigePerks.Perk.FRONTIER_ATTUNEMENT))));
    }

    @Test void villageAndFallbackSettingsRequireTheirPerks() {
        var village = new PrestigePerks.Build("lineage-test", 1, EnumSet.of(PrestigePerks.Perk.SAFE_ARRIVAL),
                PrestigePerks.Landing.VILLAGE, "");
        assertThrows(IllegalArgumentException.class, () -> PrestigePerks.validateShape(village));
        var fallback = new PrestigePerks.Build("lineage-test", 1, EnumSet.noneOf(PrestigePerks.Perk.class),
                PrestigePerks.Landing.BIOME, "minecraft:plains");
        assertThrows(IllegalArgumentException.class, () -> PrestigePerks.validateShape(fallback));
    }

    private static PrestigePerks.Build build(long generation, EnumSet<PrestigePerks.Perk> perks) {
        return new PrestigePerks.Build("lineage-test", generation, perks, PrestigePerks.Landing.BIOME, "");
    }
}
