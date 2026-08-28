package com.bettercontent.worldlifecyclemanager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class SchematicDownloadStore {
    public enum Status { SAVED, PRESENT }
    public record Result(Status status, Path path) {}
    private SchematicDownloadStore() {}

    public static Result save(Path gameDirectory, String author, String name, String expectedHash, byte[] data) throws Exception {
        String digest = hash(data);
        if (!digest.equals(expectedHash)) throw new IOException("download hash mismatch");
        String safeAuthor = author.replaceAll("[^A-Za-z0-9_]", "_");
        String safeName = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (!safeName.endsWith(".nbt")) safeName += ".nbt";
        Path directory = gameDirectory.resolve("schematics").normalize();
        Files.createDirectories(directory);
        Path primary = directory.resolve(safeName).normalize();
        if (!primary.startsWith(directory)) throw new IOException("download path escaped schematic directory");
        if (!Files.exists(primary)) return write(primary, data);
        if (hash(Files.readAllBytes(primary)).equals(digest)) return new Result(Status.PRESENT, primary);
        String stem = safeName.substring(0, safeName.length() - 4);
        for (int length : new int[]{8, 12, 16, 64}) {
            Path alternate = directory.resolve(stem + "-" + safeAuthor + "-" + digest.substring(0, length) + ".nbt");
            if (!Files.exists(alternate)) return write(alternate, data);
            if (hash(Files.readAllBytes(alternate)).equals(digest)) return new Result(Status.PRESENT, alternate);
        }
        throw new IOException("all deterministic hash-suffixed schematic names are occupied");
    }

    public static boolean contains(Path gameDirectory, String author, String name, String expectedHash) {
        try {
            String safeAuthor = author.replaceAll("[^A-Za-z0-9_]", "_");
            String safeName = name.replaceAll("[^A-Za-z0-9._ -]", "_");
            if (!safeName.endsWith(".nbt")) safeName += ".nbt";
            Path directory = gameDirectory.resolve("schematics").normalize();
            String stem = safeName.substring(0, safeName.length() - 4);
            Path primary = directory.resolve(safeName);
            if (Files.isRegularFile(primary) && hash(Files.readAllBytes(primary)).equals(expectedHash)) return true;
            for (int length : new int[]{8, 12, 16, 64}) {
                Path alternate = directory.resolve(stem + "-" + safeAuthor + "-" + expectedHash.substring(0, length) + ".nbt");
                if (Files.isRegularFile(alternate) && hash(Files.readAllBytes(alternate)).equals(expectedHash)) return true;
            }
            return false;
        } catch (Exception failure) { return false; }
    }

    public static String hash(byte[] data) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
    private static Result write(Path target, byte[] data) throws IOException {
        Files.write(target, data, StandardOpenOption.CREATE_NEW);
        return new Result(Status.SAVED, target);
    }
}
