package com.bettercontent.worldlifecyclemanager;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public final class LineagePlayerDataStore {
    static final int MAX_COMPRESSED_BYTES = 64 * 1024;
    private static final String DIRECTORY = "lineage-player-data-v1";
    private static final int SCHEMA = 1;

    private LineagePlayerDataStore() {}

    public static CompoundTag read(Path stateRoot, String lineageId, ResourceLocation key, UUID playerId) throws IOException {
        Path path = path(stateRoot, key, playerId);
        if (!Files.isRegularFile(path)) return new CompoundTag();
        long size = Files.size(path);
        if (size <= 0 || size > MAX_COMPRESSED_BYTES) throw new IOException("lineage player data exceeds bounded size");
        CompoundTag root;
        try (var input = Files.newInputStream(path)) {
            root = NbtIo.readCompressed(input);
        }
        if (root.getInt("schema") != SCHEMA) throw new IOException("unsupported lineage player data schema");
        if (!lineageId.equals(root.getString("lineage"))) throw new IOException("lineage player data belongs to another lineage");
        if (!playerId.toString().equals(root.getString("player"))) throw new IOException("lineage player data belongs to another player");
        return root.getCompound("payload").copy();
    }

    public static void write(Path stateRoot, String lineageId, ResourceLocation key, UUID playerId, CompoundTag payload) throws IOException {
        validateLineage(lineageId);
        Path path = path(stateRoot, key, playerId);
        var root = new CompoundTag();
        root.putInt("schema", SCHEMA);
        root.putString("lineage", lineageId);
        root.putString("player", playerId.toString());
        root.put("payload", payload.copy());
        byte[] bytes;
        try (var output = new ByteArrayOutputStream()) {
            NbtIo.writeCompressed(root, output);
            bytes = output.toByteArray();
        }
        if (bytes.length > MAX_COMPRESSED_BYTES) throw new IOException("lineage player data exceeds bounded size");
        Files.createDirectories(path.getParent());
        Path partial = path.resolveSibling(path.getFileName() + ".partial");
        Files.write(partial, bytes);
        try {
            Files.move(partial, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(partial, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static CompoundTag decode(byte[] bytes) throws IOException {
        if (bytes.length <= 0 || bytes.length > MAX_COMPRESSED_BYTES) throw new IOException("lineage player data exceeds bounded size");
        return NbtIo.readCompressed(new ByteArrayInputStream(bytes));
    }

    private static Path path(Path stateRoot, ResourceLocation key, UUID playerId) {
        String safeKey = (key.getNamespace() + "_" + key.getPath()).replace('/', '_');
        return stateRoot.resolve(DIRECTORY).resolve(safeKey).resolve(playerId + ".nbt");
    }

    private static void validateLineage(String lineageId) {
        if (lineageId == null || !lineageId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid lineage ID");
        }
    }
}
