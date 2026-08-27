package com.bettercontent.worldlifecyclemanager;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class LineagePlayerDataStoreTest {
    @TempDir Path root;
    private final ResourceLocation threads = new ResourceLocation("better_content_fixes", "threads");
    private final UUID player = UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @Test void missingStateHasEmptyDefaultAndRoundTrips() throws Exception {
        assertTrue(LineagePlayerDataStore.read(root, "lineage-a", threads, player).isEmpty());
        var payload = new CompoundTag(); payload.putString("card", "world_remembers");
        LineagePlayerDataStore.write(root, "lineage-a", threads, player, payload);
        assertEquals("world_remembers", LineagePlayerDataStore.read(root, "lineage-a", threads, player).getString("card"));
    }

    @Test void playersAndNamespacesAreIsolated() throws Exception {
        var payload = new CompoundTag(); payload.putInt("phase", 2);
        LineagePlayerDataStore.write(root, "lineage-a", threads, player, payload);
        assertTrue(LineagePlayerDataStore.read(root, "lineage-a", threads, UUID.randomUUID()).isEmpty());
        assertTrue(LineagePlayerDataStore.read(root, "lineage-a", new ResourceLocation("test", "other"), player).isEmpty());
    }

    @Test void refusesAnotherLineageAndOversizedFiles() throws Exception {
        var payload = new CompoundTag(); payload.putBoolean("known", true);
        LineagePlayerDataStore.write(root, "lineage-a", threads, player, payload);
        assertThrows(IOException.class, () -> LineagePlayerDataStore.read(root, "lineage-b", threads, player));
        Path file = Files.walk(root).filter(Files::isRegularFile).findFirst().orElseThrow();
        Files.write(file, new byte[LineagePlayerDataStore.MAX_COMPRESSED_BYTES + 1]);
        assertThrows(IOException.class, () -> LineagePlayerDataStore.read(root, "lineage-a", threads, player));
    }

    @Test void rejectsUnsafeLineageIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> LineagePlayerDataStore.write(root, "../other", threads, player, new CompoundTag()));
    }
}
