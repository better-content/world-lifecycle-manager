package com.bettercontent.worldlifecyclemanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class SchematicDownloadStoreTest {
    @TempDir Path game;

    @Test void savesIdempotentlyAndPreservesAConflictingLocalFile() throws Exception {
        byte[] first = "lineage-plan".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = SchematicDownloadStore.hash(first);
        var saved = SchematicDownloadStore.save(game, "Builder", "workshop.nbt", hash, first);
        assertEquals(SchematicDownloadStore.Status.SAVED, saved.status());
        assertEquals(game.resolve("schematics/workshop.nbt"), saved.path());
        assertEquals(SchematicDownloadStore.Status.PRESENT,
                SchematicDownloadStore.save(game, "Builder", "workshop.nbt", hash, first).status());
        Files.writeString(saved.path(), "local-edit");
        var alternate = SchematicDownloadStore.save(game, "Builder", "workshop.nbt", hash, first);
        assertEquals(SchematicDownloadStore.Status.SAVED, alternate.status());
        assertTrue(alternate.path().getFileName().toString().contains("Builder"));
        assertTrue(alternate.path().getFileName().toString().contains(hash.substring(0, 8)));
        assertEquals("local-edit", Files.readString(saved.path()));
        assertTrue(SchematicDownloadStore.contains(game, "Builder", "workshop.nbt", hash));
    }

    @Test void rejectsCorruptPayloadAndSanitizesNames() throws Exception {
        assertThrows(Exception.class, () -> SchematicDownloadStore.save(game, "../bad", "../bad.nbt",
                SchematicDownloadStore.hash("good".getBytes()), "bad".getBytes()));
    }
}
