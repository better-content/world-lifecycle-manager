package com.bettercontent.worldlifecyclemanager;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PrestigePerksTest {
    private static final List<PrestigePerks.Perk> COMPLETE_ORDER = List.of(
            PrestigePerks.Perk.EXPANDED_ATTUNEMENT, PrestigePerks.Perk.FRONTIER_ATTUNEMENT,
            PrestigePerks.Perk.SAFE_ARRIVAL, PrestigePerks.Perk.SETTLED_ARRIVAL,
            PrestigePerks.Perk.FALLBACK_ATTUNEMENT, PrestigePerks.Perk.FOURTH_HORIZON,
            PrestigePerks.Perk.CLASS_WAYFINDER, PrestigePerks.Perk.CLASS_FIELD_COOK,
            PrestigePerks.Perk.CLASS_RAIL_SCOUT, PrestigePerks.Perk.CLASS_FLOOD_RUNNER,
            PrestigePerks.Perk.CLASS_MARKET_RUNNER, PrestigePerks.Perk.CLASS_TRAIL_WRANGLER,
            PrestigePerks.Perk.EMBARK_BUDGET_I, PrestigePerks.Perk.EMBARK_BUDGET_II,
            PrestigePerks.Perk.EMBARK_BUDGET_III, PrestigePerks.Perk.EMBARK_BUDGET_IV,
            PrestigePerks.Perk.SCHEMATICANNON_START);
    private static final EnumSet<PrestigePerks.Perk> ORIGINAL = EnumSet.of(
            PrestigePerks.Perk.EXPANDED_ATTUNEMENT, PrestigePerks.Perk.FRONTIER_ATTUNEMENT,
            PrestigePerks.Perk.SAFE_ARRIVAL, PrestigePerks.Perk.SETTLED_ARRIVAL,
            PrestigePerks.Perk.FALLBACK_ATTUNEMENT, PrestigePerks.Perk.FOURTH_HORIZON);
    private static final EnumSet<PrestigePerks.Perk> CLASSES = EnumSet.of(
            PrestigePerks.Perk.CLASS_WAYFINDER, PrestigePerks.Perk.CLASS_FIELD_COOK,
            PrestigePerks.Perk.CLASS_RAIL_SCOUT, PrestigePerks.Perk.CLASS_FLOOD_RUNNER,
            PrestigePerks.Perk.CLASS_MARKET_RUNNER, PrestigePerks.Perk.CLASS_TRAIL_WRANGLER);

    @Test void onePointPerUpcomingGenerationCapsAtSeventeen() {
        assertEquals(1, build(0, EnumSet.noneOf(PrestigePerks.Perk.class)).budget());
        assertEquals(7, build(6, ORIGINAL).budget());
        assertEquals(17, build(99, EnumSet.allOf(PrestigePerks.Perk.class)).budget());
    }

    @Test void everyPaidPerkAllocatesAndRefundsThroughAValidSeventeenGenerationPath() {
        assertEquals(EnumSet.allOf(PrestigePerks.Perk.class), EnumSet.copyOf(COMPLETE_ORDER));
        EnumSet<PrestigePerks.Perk> selected = EnumSet.noneOf(PrestigePerks.Perk.class);
        for (int index = 0; index < COMPLETE_ORDER.size(); index++) {
            PrestigePerks.Perk perk = COMPLETE_ORDER.get(index);
            assertTrue(selected.add(perk), () -> "duplicate allocation proof for " + perk.id());
            int budget = index + 1;
            assertDoesNotThrow(() -> PrestigePerks.validatePaidSet(selected, budget),
                    () -> "allocation path rejected " + perk.id());
        }
        for (int index = COMPLETE_ORDER.size() - 1; index >= 0; index--) {
            PrestigePerks.Perk perk = COMPLETE_ORDER.get(index);
            assertTrue(selected.remove(perk), () -> "missing refund proof for " + perk.id());
            assertDoesNotThrow(() -> PrestigePerks.validatePaidSet(selected, PrestigePerks.MAX_POINTS),
                    () -> "refund path rejected " + perk.id());
        }
        assertTrue(selected.isEmpty());
    }

    @Test void originalBranchesRequireTheirRoots() {
        assertInvalid(EnumSet.of(PrestigePerks.Perk.FRONTIER_ATTUNEMENT));
        assertInvalid(EnumSet.of(PrestigePerks.Perk.SETTLED_ARRIVAL));
        assertInvalid(EnumSet.of(PrestigePerks.Perk.FOURTH_HORIZON));
        assertDoesNotThrow(() -> PrestigePerks.validatePaidSet(ORIGINAL, 6));
    }

    @Test void classesRequireAllSixOriginalPerks() {
        EnumSet<PrestigePerks.Perk> selected = ORIGINAL.clone();
        selected.remove(PrestigePerks.Perk.FOURTH_HORIZON);
        selected.add(PrestigePerks.Perk.CLASS_WAYFINDER);
        assertInvalid(selected);
        selected.add(PrestigePerks.Perk.FOURTH_HORIZON);
        assertDoesNotThrow(() -> PrestigePerks.validatePaidSet(selected, 7));
    }

    @Test void embarkAndCapstoneRequireTheCompleteGraph() {
        EnumSet<PrestigePerks.Perk> selected = ORIGINAL.clone();
        selected.addAll(CLASSES);
        assertInvalid(with(selected, PrestigePerks.Perk.EMBARK_BUDGET_II));
        selected.add(PrestigePerks.Perk.EMBARK_BUDGET_I);
        selected.add(PrestigePerks.Perk.EMBARK_BUDGET_II);
        selected.add(PrestigePerks.Perk.EMBARK_BUDGET_III);
        selected.add(PrestigePerks.Perk.EMBARK_BUDGET_IV);
        assertInvalid(without(with(selected, PrestigePerks.Perk.SCHEMATICANNON_START), PrestigePerks.Perk.SAFE_ARRIVAL));
        selected.add(PrestigePerks.Perk.SCHEMATICANNON_START);
        assertEquals(17, selected.size());
        assertDoesNotThrow(() -> PrestigePerks.validatePaidSet(selected, 17));
    }

    @Test void onboardingMilestonesAreExact() {
        var none = PrestigePerks.onboardingPolicy(EnumSet.noneOf(PrestigePerks.Perk.class));
        assertEquals(PrestigePerks.OnboardingMode.SPAWN_ONLY, none.mode());

        EnumSet<PrestigePerks.Perk> paid = ORIGINAL.clone();
        paid.add(PrestigePerks.Perk.CLASS_WAYFINDER);
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

    @Test void fourthHorizonAloneControlsAttemptFour() {
        assertEquals(3, build(0, EnumSet.noneOf(PrestigePerks.Perk.class)).successorAttempts());
        assertEquals(4, build(1, EnumSet.of(PrestigePerks.Perk.FALLBACK_ATTUNEMENT,
                PrestigePerks.Perk.FOURTH_HORIZON)).successorAttempts());
    }

    @Test void villageAndFallbackSettingsRequireTheirPerks() {
        var village = new PrestigePerks.Build("lineage-test", 1, EnumSet.of(PrestigePerks.Perk.SAFE_ARRIVAL),
                PrestigePerks.Landing.VILLAGE, "");
        assertThrows(IllegalArgumentException.class, () -> PrestigePerks.validateShape(village));
        var fallback = new PrestigePerks.Build("lineage-test", 1, EnumSet.noneOf(PrestigePerks.Perk.class),
                PrestigePerks.Landing.BIOME, "minecraft:plains");
        assertThrows(IllegalArgumentException.class, () -> PrestigePerks.validateShape(fallback));
    }

    private static void assertEmbark(EnumSet<PrestigePerks.Perk> paid, int budget, boolean cannon) {
        var policy = PrestigePerks.onboardingPolicy(paid);
        assertEquals(PrestigePerks.OnboardingMode.EMBARK, policy.mode());
        assertEquals(budget, policy.embarkBudget());
        assertEquals(cannon, policy.starterSchematicannon());
        assertTrue(policy.unlockedClassIds().isEmpty());
    }

    private static void assertInvalid(EnumSet<PrestigePerks.Perk> perks) {
        assertThrows(IllegalArgumentException.class, () -> PrestigePerks.validatePaidSet(perks, 17));
    }

    private static EnumSet<PrestigePerks.Perk> with(EnumSet<PrestigePerks.Perk> source, PrestigePerks.Perk perk) {
        EnumSet<PrestigePerks.Perk> copy = source.clone(); copy.add(perk); return copy;
    }
    private static EnumSet<PrestigePerks.Perk> without(EnumSet<PrestigePerks.Perk> source, PrestigePerks.Perk perk) {
        EnumSet<PrestigePerks.Perk> copy = source.clone(); copy.remove(perk); return copy;
    }
    private static PrestigePerks.Build build(long generation, EnumSet<PrestigePerks.Perk> perks) {
        return new PrestigePerks.Build("lineage-test", generation, perks, PrestigePerks.Landing.BIOME, "");
    }
}
