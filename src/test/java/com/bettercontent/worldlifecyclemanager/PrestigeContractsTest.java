package com.bettercontent.worldlifecyclemanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrestigeContractsTest {
    @TempDir Path temp;

    @Test
    void v4ContractsRoundTripExactly() throws Exception {
        Path lineagePath = temp.resolve("lineage-v4.tsv");
        PrestigeContracts.Lineage lineage = new PrestigeContracts.Lineage("lineage-abc", 3, 3);
        PrestigeContracts.writeLineage(lineagePath, lineage);
        assertEquals(lineage, PrestigeContracts.readLineage(lineagePath));

        Path stagedPath = temp.resolve("control/staged-request-v4.tsv");
        PrestigeContracts.Staged staged = new PrestigeContracts.Staged(
                "lineage-abc", 3, "minecraft:plains", "Builder", "world");
        PrestigeContracts.writeStaged(stagedPath, staged);
        assertEquals(staged, PrestigeContracts.readStaged(stagedPath));

        Path resetPath = temp.resolve("control/reset-request-v4.tsv");
        PrestigeContracts.Reset reset = new PrestigeContracts.Reset(
                "lineage-abc", 3, "transaction-abc", "world", 1L, "minecraft:plains");
        PrestigeContracts.writeReset(resetPath, reset);
        assertEquals(reset, PrestigeContracts.readReset(resetPath));
        assertTrue(Files.notExists(resetPath.resolveSibling("reset-request-v4.tsv.partial")));
    }

    @Test
    void contractsRejectUnknownOrderBiomeAndCounters() throws Exception {
        Path malformed = temp.resolve("malformed.tsv");
        Files.writeString(malformed, PrestigeContracts.STAGED_MAGIC + "\n"
                + "biome\tminecraft:plains\nlineage\tlineage-abc\ngeneration\t0\nauthor\tBuilder\nworld\tworld\n");
        assertThrows(IllegalArgumentException.class, () -> PrestigeContracts.readStaged(malformed));
        assertThrows(IllegalArgumentException.class, () -> PrestigeContracts.validateBiome("../plains"));
        assertThrows(IllegalArgumentException.class, () -> PrestigeContracts.validateWorldName("../world"));
        assertThrows(IllegalArgumentException.class, () -> PrestigeContracts.writeLineage(
                temp.resolve("bad-lineage.tsv"), new PrestigeContracts.Lineage("lineage-abc", 1, 2)));
    }

    @Test
    void successorIdentityAndAttemptAreStrict() throws Exception {
        Path successor = temp.resolve("successor.tsv");
        Files.writeString(successor, PrestigeContracts.SUCCESSOR_MAGIC + "\n"
                + "lineage\tlineage-abc\nbase_generation\t0\ntarget_generation\t1\n"
                + "transaction\ttransaction-abc\nsuccessor_seed\t7\n"
                + "biome\tminecraft:plains\nattempt\t4\n");
        assertEquals(4, PrestigeContracts.readSuccessor(successor).attempt());
        Files.writeString(successor, PrestigeContracts.SUCCESSOR_MAGIC + "\n"
                + "lineage\tlineage-abc\nbase_generation\t0\ntarget_generation\t1\n"
                + "transaction\ttransaction-abc\nsuccessor_seed\t7\n"
                + "biome\tminecraft:plains\nattempt\t5\n");
        assertThrows(IllegalArgumentException.class, () -> PrestigeContracts.readSuccessor(successor));

        Path active = temp.resolve("active-successor.tsv");
        Files.writeString(active, PrestigeContracts.ACTIVE_SUCCESSOR_MAGIC + "\n"
                + "pid\t123\nstart_ticks\t456\nlineage\tlineage-abc\n"
                + "transaction\ttransaction-abc\nattempt\t2\n");
        assertEquals(new PrestigeContracts.ActiveSuccessor(123, 456, "lineage-abc", "transaction-abc", 2),
                PrestigeContracts.readActiveSuccessor(active));
        Files.writeString(active, PrestigeContracts.ACTIVE_SUCCESSOR_MAGIC + "\n"
                + "pid\t0\nstart_ticks\t456\nlineage\tlineage-abc\n"
                + "transaction\ttransaction-abc\nattempt\t2\n");
        assertThrows(IllegalArgumentException.class, () -> PrestigeContracts.readActiveSuccessor(active));
    }

    @Test
    void v4GenerationAndWorldBindingAreClosed() throws Exception {
        Path bindingPath = temp.resolve("world/data/world_lifecycle_manager/reset-binding-v4.tsv");
        PrestigeContracts.WorldBinding binding = new PrestigeContracts.WorldBinding(
                "lineage-abc", 7, "transaction-abc", "world", -42, "minecraft:plains");
        PrestigeContracts.writeWorldBinding(bindingPath, binding);
        assertEquals(binding, PrestigeContracts.readWorldBinding(bindingPath));

        Path old = temp.resolve("old-v3.tsv");
        Files.writeString(old, "BC_PRESTIGE_LINEAGE_V3\nlineage\tlineage-abc\ntotal_prestiges\t0\ngeneration\t0\n");
        assertThrows(IllegalArgumentException.class, () -> PrestigeContracts.readLineage(old));
    }
}
