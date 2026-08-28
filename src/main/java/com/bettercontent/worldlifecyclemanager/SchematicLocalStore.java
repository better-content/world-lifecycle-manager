package com.bettercontent.worldlifecyclemanager;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class SchematicLocalStore {
    static final int MAX_LOCAL = 256;

    record Entry(String name, long size) {}

    private SchematicLocalStore() {}

    static List<Entry> list(Path gameDirectory) throws IOException {
        Path root = root(gameDirectory);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return List.of();
        List<Entry> result = new ArrayList<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(root)) {
            int scanned = 0;
            for (Path path : paths) {
                if (++scanned > SchematicLibrary.MAX_DIRECTORY_SCAN) throw new IllegalStateException(
                        "local schematic directory exceeds " + SchematicLibrary.MAX_DIRECTORY_SCAN + " entries");
                String name = path.getFileName().toString();
                if (!SchematicLibrary.safeFileName(name)
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) continue;
                long size = Files.size(path);
                if (size < 2 || size > SchematicLibrary.MAX_BYTES) continue;
                if (result.size() >= MAX_LOCAL) throw new IllegalStateException(
                        "local schematic catalog exceeds " + MAX_LOCAL + " entries");
                result.add(new Entry(name, size));
            }
        }
        result.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::name));
        return List.copyOf(result);
    }

    static byte[] read(Path gameDirectory, String name) throws IOException {
        if (!SchematicLibrary.safeFileName(name)) throw new IllegalArgumentException("unsafe local schematic filename");
        Path root = root(gameDirectory);
        Path source = root.resolve(name).normalize();
        if (!source.startsWith(root) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(source)) throw new IllegalArgumentException("local schematic is not a regular file");
        long size = Files.size(source);
        if (size < 2 || size > SchematicLibrary.MAX_BYTES) {
            throw new IllegalArgumentException("local schematic is outside the size limit");
        }
        byte[] data = Files.readAllBytes(source);
        if (data.length != size) throw new IOException("local schematic changed while reading");
        return data;
    }

    private static Path root(Path gameDirectory) {
        return gameDirectory.toAbsolutePath().normalize().resolve("schematics").normalize();
    }
}
