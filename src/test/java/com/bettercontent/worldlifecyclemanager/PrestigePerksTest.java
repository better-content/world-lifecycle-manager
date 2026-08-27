package com.bettercontent.worldlifecyclemanager;

import org.junit.jupiter.api.Test;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

final class PrestigePerksTest {
    private static final List<PrestigePerks.Perk> COMPLETE_ORDER = List.of(
            PrestigePerks.Perk.BIOME_SELECTION,
            PrestigePerks.Perk.CLASS_WAYFINDER, PrestigePerks.Perk.CLASS_FIELD_COOK,
            PrestigePerks.Perk.CLASS_RAIL_SCOUT, PrestigePerks.Perk.CLASS_FLOOD_RUNNER,
            PrestigePerks.Perk.CLASS_MARKET_RUNNER, PrestigePerks.Perk.CLASS_TRAIL_WRANGLER,
            PrestigePerks.Perk.EMBARK_BUDGET_I, PrestigePerks.Perk.EMBARK_BUDGET_II,
            PrestigePerks.Perk.EMBARK_BUDGET_III, PrestigePerks.Perk.EMBARK_BUDGET_IV,
            PrestigePerks.Perk.SCHEMATICANNON_START);
    private static final EnumSet<PrestigePerks.Perk> CLASSES = EnumSet.of(
            PrestigePerks.Perk.CLASS_WAYFINDER, PrestigePerks.Perk.CLASS_FIELD_COOK,
            PrestigePerks.Perk.CLASS_RAIL_SCOUT, PrestigePerks.Perk.CLASS_FLOOD_RUNNER,
            PrestigePerks.Perk.CLASS_MARKET_RUNNER, PrestigePerks.Perk.CLASS_TRAIL_WRANGLER);
    private static final Set<String> CLASS_IDS = Set.of("wayfinder", "field_cook", "rail_scout",
            "flood_runner", "market_runner", "trail_wrangler");

    @Test void onePointPerUpcomingGenerationCapsAtTwelve() {
        assertEquals(1, build(0, EnumSet.noneOf(PrestigePerks.Perk.class)).budget());
        assertEquals(7, build(6, EnumSet.of(PrestigePerks.Perk.BIOME_SELECTION)).budget());
        assertEquals(12, build(99, EnumSet.allOf(PrestigePerks.Perk.class)).budget());
    }

    @Test void completeTwelveGenerationAllocationAndRefundPathIsValid() {
        assertEquals(EnumSet.allOf(PrestigePerks.Perk.class), EnumSet.copyOf(COMPLETE_ORDER));
        EnumSet<PrestigePerks.Perk> selected = EnumSet.noneOf(PrestigePerks.Perk.class);
        for (int index = 0; index < COMPLETE_ORDER.size(); index++) {
            assertTrue(selected.add(COMPLETE_ORDER.get(index)));
            int budget = index + 1;
            assertDoesNotThrow(() -> PrestigePerks.validatePaidSet(selected, budget));
        }
        for (int index = COMPLETE_ORDER.size() - 1; index >= 0; index--) {
            assertTrue(selected.remove(COMPLETE_ORDER.get(index)));
            assertDoesNotThrow(() -> PrestigePerks.validatePaidSet(selected, PrestigePerks.MAX_POINTS));
        }
    }

    @Test void biomeSelectionIsTheMandatoryRoot() {
        assertThrows(IllegalArgumentException.class, () -> PrestigePerks.validatePaidSet(
                EnumSet.of(PrestigePerks.Perk.CLASS_WAYFINDER), 12));
        assertDoesNotThrow(() -> PrestigePerks.validatePaidSet(EnumSet.of(PrestigePerks.Perk.BIOME_SELECTION), 1));
    }

    @Test void embarkAndCapstoneRequireTheCompleteGraph() {
        EnumSet<PrestigePerks.Perk> selected = EnumSet.of(PrestigePerks.Perk.BIOME_SELECTION);
        selected.addAll(CLASSES);
        var invalid = selected.clone(); invalid.add(PrestigePerks.Perk.EMBARK_BUDGET_II);
        assertThrows(IllegalArgumentException.class, () -> PrestigePerks.validatePaidSet(invalid, 12));
        selected.addAll(EnumSet.of(PrestigePerks.Perk.EMBARK_BUDGET_I, PrestigePerks.Perk.EMBARK_BUDGET_II,
                PrestigePerks.Perk.EMBARK_BUDGET_III, PrestigePerks.Perk.EMBARK_BUDGET_IV,
                PrestigePerks.Perk.SCHEMATICANNON_START));
        assertEquals(12, selected.size());
        assertDoesNotThrow(() -> PrestigePerks.validatePaidSet(selected, 12));
    }

    @Test void onboardingMilestonesAreExact() {
        var spawn = PrestigePerks.onboardingPolicy(EnumSet.of(PrestigePerks.Perk.BIOME_SELECTION));
        assertEquals(PrestigePerks.OnboardingMode.SPAWN_ONLY, spawn.mode());
        EnumSet<PrestigePerks.Perk> paid = EnumSet.of(PrestigePerks.Perk.BIOME_SELECTION,
                PrestigePerks.Perk.CLASS_WAYFINDER);
        var classes = PrestigePerks.onboardingPolicy(paid);
        assertEquals(PrestigePerks.OnboardingMode.CLASS, classes.mode());
        assertEquals(Set.of("wayfinder"), classes.unlockedClassIds());
        paid.addAll(CLASSES);
        assertEmbark(paid, 6, false);
        paid.add(PrestigePerks.Perk.EMBARK_BUDGET_I); assertEmbark(paid, 9, false);
        paid.add(PrestigePerks.Perk.EMBARK_BUDGET_II); assertEmbark(paid, 12, false);
        paid.add(PrestigePerks.Perk.EMBARK_BUDGET_III); assertEmbark(paid, 15, false);
        paid.add(PrestigePerks.Perk.EMBARK_BUDGET_IV); assertEmbark(paid, 18, false);
        paid.add(PrestigePerks.Perk.SCHEMATICANNON_START); assertEmbark(paid, 18, true);
    }

    @Test void everyBuildHasEightSuccessorAttempts() {
        assertEquals(8, build(0, EnumSet.noneOf(PrestigePerks.Perk.class)).successorAttempts());
        assertEquals(8, build(11, EnumSet.allOf(PrestigePerks.Perk.class)).successorAttempts());
    }

    @Test void biomePreferencesAreUniqueBoundedAndOptionalUntilStage() {
        assertDoesNotThrow(() -> PrestigePerks.validateShape(build(0, EnumSet.noneOf(PrestigePerks.Perk.class))));
        assertDoesNotThrow(() -> PrestigePerks.validateShape(new PrestigePerks.Build("lineage-test", 0,
                EnumSet.of(PrestigePerks.Perk.BIOME_SELECTION), List.of("minecraft:plains", "minecraft:forest"))));
        assertThrows(IllegalArgumentException.class, () -> PrestigePerks.validateShape(new PrestigePerks.Build("lineage-test", 0,
                EnumSet.of(PrestigePerks.Perk.BIOME_SELECTION), List.of("minecraft:plains", "minecraft:plains"))));
    }

    @Test void biomeAllowlistStripsCommentsAndRejectsDuplicateRows() {
        assertEquals(List.of("minecraft:plains", "minecraft:forest"), PrestigePerks.parseBiomeLines("biomes",
                List.of(" # comment", " minecraft:plains ", "", "minecraft:forest")));
        assertThrows(IllegalArgumentException.class, () -> PrestigePerks.parseBiomeLines("biomes",
                List.of("minecraft:plains", " minecraft:plains ")));
        assertThrows(IllegalArgumentException.class, () -> PrestigePerks.parseBiomeLines("biomes", List.of("# none")));
    }

    private static void assertEmbark(EnumSet<PrestigePerks.Perk> paid, int budget, boolean cannon) {
        var policy = PrestigePerks.onboardingPolicy(paid);
        assertEquals(PrestigePerks.OnboardingMode.EMBARK, policy.mode());
        assertEquals(CLASS_IDS, policy.unlockedClassIds());
        assertEquals(budget, policy.embarkBudget());
        assertEquals(cannon, policy.starterSchematicannon());
    }

    private static PrestigePerks.Build build(long generation, EnumSet<PrestigePerks.Perk> perks) {
        return new PrestigePerks.Build("lineage-test", generation, perks, List.of());
    }
}
