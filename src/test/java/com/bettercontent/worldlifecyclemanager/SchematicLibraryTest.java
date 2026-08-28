package com.bettercontent.worldlifecyclemanager;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.IntTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchematicLibraryTest {
    @TempDir Path serverRoot;

    @Test
    void publicationIsOptInAttributedDeduplicatedAndRemovable() throws Exception {
        byte[] upload = compressedStructure(0);

        assertEquals(java.util.List.of(), SchematicLibrary.list(serverRoot));

        var first = SchematicLibrary.publish(serverRoot, "Builder", "house.nbt", upload, 0);
        var second = SchematicLibrary.publish(serverRoot, "Builder", "house.nbt", upload, 1);
        assertEquals(first.id(), second.id());
        try (var objects = Files.list(serverRoot.resolve(".world_lifecycle_manager/schematics/objects"))) {
            assertEquals(1, objects.count());
        }
        byte[] download = SchematicLibrary.download(serverRoot, first.id());
        assertArrayEquals(SchematicLibrary.sanitize(upload, null), download);
        CompoundTag sanitized = NbtIo.readCompressed(new java.io.ByteArrayInputStream(download));
        assertEquals(0, sanitized.getList("entities", net.minecraft.nbt.Tag.TAG_COMPOUND).size());
        assertEquals(false, sanitized.contains("fixture"));
        assertFalse(sanitized.getList("palette", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .getCompound(0).contains("unsafe"), "unknown palette payload crossed the catalog boundary");
        assertFalse(sanitized.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .getCompound(0).contains("nbt"), "unapproved block-entity NBT crossed the catalog boundary");
        SchematicLibrary.remove(serverRoot, first.id());
        assertEquals(java.util.List.of(), SchematicLibrary.list(serverRoot));
    }

    @Test
    void publicationRejectsTraversalAndMalformedPayloads() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> SchematicLibrary.publish(serverRoot, "Builder", "../broken.nbt", compressedStructure(0), 0));
        assertThrows(IllegalArgumentException.class,
                () -> SchematicLibrary.publish(serverRoot, "Builder", "broken.nbt", "not gzip".getBytes(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> SchematicLibrary.publish(serverRoot, "Builder", "broken.nbt", new byte[(int) SchematicLibrary.MAX_BYTES + 1], 0));
    }

    @Test
    void catalogsRejectExcessEntriesBeforeUnboundedProcessing() throws Exception {
        Path entries = serverRoot.resolve(".world_lifecycle_manager/schematics/entries");
        Files.createDirectories(entries);
        for (int i = 0; i <= SchematicLibrary.MAX_PUBLISHED; i++) {
            Files.writeString(entries.resolve("entry-" + i + ".tsv"), "fixture");
        }
        assertThrows(IllegalStateException.class, () -> SchematicLibrary.list(serverRoot));
    }

    @Test
    void catalogsBoundScansEvenWhenEntriesAreIgnored() throws Exception {
        Path entries = serverRoot.resolve(".world_lifecycle_manager/schematics/entries");
        Files.createDirectories(entries);
        for (int i = 0; i <= SchematicLibrary.MAX_DIRECTORY_SCAN; i++) {
            Files.createDirectory(entries.resolve("ignored-" + i));
        }
        assertThrows(IllegalStateException.class, () -> SchematicLibrary.list(serverRoot));
    }

    @Test
    void publicationLimitAllowsReplacementButRejectsNewEntry() throws Exception {
        byte[] first = compressedStructure(1);
        var published = SchematicLibrary.publish(serverRoot, "Builder", "first.nbt", first, 0);
        Path entries = serverRoot.resolve(".world_lifecycle_manager/schematics/entries");
        for (int i = 1; i < SchematicLibrary.MAX_PUBLISHED; i++) {
            Files.writeString(entries.resolve("placeholder-" + i + ".tsv"), "reserved\n");
        }
        assertEquals(published.id(), SchematicLibrary.publish(serverRoot, "Builder", "first.nbt", first, 1).id());

        assertThrows(IllegalStateException.class, () -> SchematicLibrary.publish(
                serverRoot, "Builder", "second.nbt", compressedStructure(2), 1));
    }

    @Test
    void validInterruptedObjectPublicationIsRecovered() throws Exception {
        byte[] upload = compressedStructure(3);
        byte[] sanitized = SchematicLibrary.sanitize(upload, null);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sanitized));
        Path objects = serverRoot.resolve(".world_lifecycle_manager/schematics/objects");
        Files.createDirectories(objects);
        Files.write(objects.resolve(digest + ".nbt.partial"), sanitized);
        var entry = SchematicLibrary.publish(serverRoot, "Builder", "recovery.nbt", upload, 0);
        assertEquals(digest, entry.sha256());
        assertArrayEquals(sanitized, Files.readAllBytes(objects.resolve(digest + ".nbt")));
    }

    @Test
    void decompressedNbtBudgetIsEnforced() throws Exception {
        CompoundTag structure = structure(4);
        structure.putByteArray("oversized", new byte[(int) SchematicLibrary.MAX_DECOMPRESSED_BYTES + 1]);
        ByteArrayOutputStream upload = new ByteArrayOutputStream();
        NbtIo.writeCompressed(structure, upload);
        assertThrows(IllegalArgumentException.class, () -> SchematicLibrary.publish(
                serverRoot, "Builder", "bomb.nbt", upload.toByteArray(), 0));
    }

    private static byte[] compressedStructure(int marker) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(structure(marker), output);
        return output.toByteArray();
    }

    private static CompoundTag structure(int marker) {
        CompoundTag structure = new CompoundTag();
        structure.put("size", integers(1, 1, 1));
        ListTag palette = new ListTag();
        CompoundTag stone = new CompoundTag();
        stone.putString("Name", marker % 2 == 0 ? "minecraft:stone" : "minecraft:dirt");
        stone.putString("unsafe", "must-not-cross");
        palette.add(stone);
        structure.put("palette", palette);
        ListTag blocks = new ListTag();
        CompoundTag block = new CompoundTag();
        block.put("pos", integers(0, 0, 0));
        block.putInt("state", 0);
        CompoundTag unsafe = new CompoundTag();
        unsafe.putString("Items", "must-not-cross");
        block.put("nbt", unsafe);
        blocks.add(block);
        structure.put("blocks", blocks);
        ListTag entities = new ListTag();
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:item");
        entities.add(entity);
        structure.put("entities", entities);
        structure.putInt("fixture", marker);
        return structure;
    }

    private static ListTag integers(int... values) {
        ListTag result = new ListTag();
        for (int value : values) result.add(IntTag.valueOf(value));
        return result;
    }
}
