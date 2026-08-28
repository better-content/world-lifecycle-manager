package com.bettercontent.worldlifecyclemanager;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PrestigeServiceStateTest {
    private static final List<String> BIOMES = List.of("minecraft:plains", "minecraft:forest");

    @Test void stagedGenerationOneSnapshotDoesNotDependOnAnObsoleteDraft() {
        var lineage = new PrestigeContracts.Lineage("lineage-test", 1, 1);
        var staged = new PrestigeContracts.Staged("lineage-test", 1, BIOMES, "Builder", "world");
        var stagedBuild = new PrestigePerks.Build("lineage-test", 1,
                EnumSet.of(PrestigePerks.Perk.BIOME_SELECTION), BIOMES);
        var obsoleteDraft = new PrestigeContracts.Draft("lineage-test", 0, BIOMES, "Builder", "world");

        assertThrows(IllegalStateException.class, () -> PrestigeService.validateDraftSelection(
                lineage, "world", obsoleteDraft,
                new PrestigePerks.Build("lineage-test", 0, EnumSet.noneOf(PrestigePerks.Perk.class), BIOMES)));
        assertDoesNotThrow(() -> PrestigeService.validateStagedSelection(lineage, "world", staged, stagedBuild));
    }

    @Test void stagedSnapshotMustMatchCurrentGenerationAndBiomes() {
        var lineage = new PrestigeContracts.Lineage("lineage-test", 2, 2);
        var build = new PrestigePerks.Build("lineage-test", 2,
                EnumSet.of(PrestigePerks.Perk.BIOME_SELECTION), BIOMES);
        assertThrows(IllegalStateException.class, () -> PrestigeService.validateStagedSelection(lineage, "world",
                new PrestigeContracts.Staged("lineage-test", 1, BIOMES, "Builder", "world"), build));
        assertThrows(IllegalStateException.class, () -> PrestigeService.validateStagedSelection(lineage, "world",
                new PrestigeContracts.Staged("lineage-test", 2, List.of("minecraft:plains"), "Builder", "world"), build));
    }
}
