package com.bettercontent.worldlifecyclemanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PrestigeContractsTest {
    @TempDir Path temp;

    @Test void v5ContractsRoundTripExactly() throws Exception {
        Path lineagePath = temp.resolve("lineage-v5.tsv");
        var lineage = new PrestigeContracts.Lineage("lineage-abc", 3, 3);
        PrestigeContracts.writeLineage(lineagePath, lineage);
        assertEquals(lineage, PrestigeContracts.readLineage(lineagePath));
        var singleplayer = new PrestigeContracts.SingleplayerBinding("lineage-abc", 3);
        Path singleplayerPath = temp.resolve("singleplayer-lineage-v1.tsv");
        PrestigeContracts.writeSingleplayerBinding(singleplayerPath, singleplayer);
        assertEquals(singleplayer, PrestigeContracts.readSingleplayerBinding(singleplayerPath));
        var staged = new PrestigeContracts.Staged("lineage-abc", 3,
                List.of("minecraft:plains", "minecraft:forest", "minecraft:meadow"), "Builder", "world");
        Path stagedPath = temp.resolve("control/staged-request-v5.tsv");
        PrestigeContracts.writeStaged(stagedPath, staged);
        assertEquals(staged, PrestigeContracts.readStaged(stagedPath));
        var reset = new PrestigeContracts.Reset("lineage-abc", 3, "transaction-abc", "world", 1L,
                List.of("minecraft:plains", "minecraft:forest"));
        Path resetPath = temp.resolve("control/reset-request-v5.tsv");
        PrestigeContracts.writeReset(resetPath, reset);
        assertEquals(reset, PrestigeContracts.readReset(resetPath));
        assertTrue(Files.notExists(resetPath.resolveSibling("reset-request-v5.tsv.partial")));
    }

    @Test void preferencesRejectDuplicatesGapsAndOverflow() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> PrestigeContracts.validateBiomes(List.of()));
        assertThrows(IllegalArgumentException.class, () -> PrestigeContracts.validateBiomes(
                List.of("minecraft:plains", "minecraft:plains")));
        assertThrows(IllegalArgumentException.class, () -> PrestigeContracts.validateBiomes(
                List.of("minecraft:plains", "minecraft:forest", "minecraft:meadow", "minecraft:taiga")));
        Path gapped = temp.resolve("gapped.tsv");
        Files.writeString(gapped, PrestigeContracts.DRAFT_MAGIC + "\nlineage\tlineage-abc\ngeneration\t0\n"
                + "biome_1\tminecraft:plains\nbiome_2\t-\nbiome_3\tminecraft:forest\nauthor\tBuilder\nworld\tworld\n");
        assertThrows(IllegalArgumentException.class, () -> PrestigeContracts.readDraft(gapped));
    }

    @Test void successorAttemptRangeIsOneThroughEight() throws Exception {
        Path successor = temp.resolve("successor.tsv");
        writeSuccessor(successor, 8);
        assertEquals(8, PrestigeContracts.readSuccessor(successor).attempt());
        writeSuccessor(successor, 9);
        assertThrows(IllegalArgumentException.class, () -> PrestigeContracts.readSuccessor(successor));
        writeSuccessor(successor, 0);
        assertThrows(IllegalArgumentException.class, () -> PrestigeContracts.readSuccessor(successor));
    }

    @Test void healthResolvedBiomeMustMatchPreferenceAndActualSpawn() throws Exception {
        var successor = new PrestigeContracts.Successor("lineage-abc", 0, 1, "transaction-abc", 7,
                List.of("minecraft:plains", "minecraft:forest"), 1);
        assertDoesNotThrow(() -> PrestigeContracts.writeHealth(temp.resolve("health.tsv"), successor, 7,
                "minecraft:forest", "minecraft:forest", "world", true, true));
        assertThrows(IllegalArgumentException.class, () -> PrestigeContracts.writeHealth(temp.resolve("bad.tsv"), successor, 7,
                "minecraft:meadow", "minecraft:meadow", "world", true, true));
        assertThrows(IllegalArgumentException.class, () -> PrestigeContracts.writeHealth(temp.resolve("bad2.tsv"), successor, 7,
                "minecraft:forest", "minecraft:plains", "world", true, true));
        assertDoesNotThrow(() -> PrestigeContracts.writeHealth(temp.resolve("miss.tsv"), successor, 7,
                "-", "minecraft:plains", "world", true, false));
    }

    @Test void oldContractMagicIsRejected() throws Exception {
        Path old = temp.resolve("old-v4.tsv");
        Files.writeString(old, "BC_PRESTIGE_LINEAGE_V4\nlineage\tlineage-abc\ntotal_prestiges\t0\ngeneration\t0\n");
        assertThrows(IllegalArgumentException.class, () -> PrestigeContracts.readLineage(old));
    }

    @Test void singleplayerBindingRejectsNegativeGeneration() {
        assertThrows(IllegalArgumentException.class, () -> PrestigeContracts.writeSingleplayerBinding(
                temp.resolve("binding.tsv"), new PrestigeContracts.SingleplayerBinding("lineage-abc", -1)));
    }

    @Test void cliBiomeArgumentsPreservePreferenceOrder() {
        assertEquals(List.of("minecraft:plains", "minecraft:forest", "minecraft:meadow"),
                PrestigeCoordinator.parseBiomeArguments(" minecraft:plains  minecraft:forest minecraft:meadow "));
        assertThrows(IllegalArgumentException.class, () -> PrestigeCoordinator.parseBiomeArguments(""));
        assertThrows(IllegalArgumentException.class, () -> PrestigeCoordinator.parseBiomeArguments(
                "minecraft:plains minecraft:forest minecraft:meadow minecraft:taiga"));
    }

    private static void writeSuccessor(Path path, int attempt) throws Exception {
        Files.writeString(path, PrestigeContracts.SUCCESSOR_MAGIC + "\nlineage\tlineage-abc\nbase_generation\t0\n"
                + "target_generation\t1\ntransaction\ttransaction-abc\nsuccessor_seed\t7\n"
                + "biome_1\tminecraft:plains\nbiome_2\t-\nbiome_3\t-\nattempt\t" + attempt + "\n");
    }
}
