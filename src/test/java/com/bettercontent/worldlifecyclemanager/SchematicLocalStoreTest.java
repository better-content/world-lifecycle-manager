package com.bettercontent.worldlifecyclemanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SchematicLocalStoreTest {
    @TempDir Path game;

    @Test
    void discoversOnlyBoundedTopLevelCreateSchematicsInStableOrder() throws Exception {
        Path root = game.resolve("schematics");
        Files.createDirectories(root.resolve("nested"));
        Files.write(root.resolve("zeta.nbt"), new byte[]{1, 2});
        Files.write(root.resolve("Alpha.nbt"), new byte[]{3, 4, 5});
        Files.write(root.resolve("ignored.txt"), new byte[]{6, 7});
        Files.write(root.resolve("empty.nbt"), new byte[]{1});
        Files.write(root.resolve("nested/hidden.nbt"), new byte[]{8, 9});
        Files.createSymbolicLink(root.resolve("linked.nbt"), root.resolve("zeta.nbt"));

        assertEquals(java.util.List.of(
                new SchematicLocalStore.Entry("Alpha.nbt", 3),
                new SchematicLocalStore.Entry("zeta.nbt", 2)), SchematicLocalStore.list(game));
        assertArrayEquals(new byte[]{3, 4, 5}, SchematicLocalStore.read(game, "Alpha.nbt"));
        assertThrows(IllegalArgumentException.class, () -> SchematicLocalStore.read(game, "../zeta.nbt"));
        assertThrows(IllegalArgumentException.class, () -> SchematicLocalStore.read(game, "linked.nbt"));
    }

    @Test
    void emptyDirectoryIsAValidEmptyCatalog() throws Exception {
        assertEquals(java.util.List.of(), SchematicLocalStore.list(game));
    }

    @Test
    void validCatalogAndDirectoryScansAreBounded() throws Exception {
        Path root = game.resolve("schematics");
        Files.createDirectories(root);
        for (int index = 0; index <= SchematicLocalStore.MAX_LOCAL; index++) {
            Files.write(root.resolve("plan-" + index + ".nbt"), new byte[]{1, 2});
        }
        assertThrows(IllegalStateException.class, () -> SchematicLocalStore.list(game));

        try (var paths = Files.list(root)) {
            for (Path path : paths.toList()) Files.delete(path);
        }
        for (int index = 0; index <= SchematicLibrary.MAX_DIRECTORY_SCAN; index++) {
            Files.write(root.resolve("ignored-" + index + ".txt"), new byte[]{1, 2});
        }
        assertThrows(IllegalStateException.class, () -> SchematicLocalStore.list(game));
    }
}
