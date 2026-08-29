package com.bettercontent.worldlifecyclemanager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Durable profile-level lineage storage bound to one independently-created single-player save. */
final class SingleplayerLineageStore {
    private static final String BINDING_FILE = "singleplayer-lineage-v1.tsv";

    record Context(Path stateRoot, PrestigeContracts.Lineage lineage) {}

    private SingleplayerLineageStore() {}

    static synchronized Context open(Path globalStateRoot, Path worldRoot) throws IOException {
        Path normalizedGlobal = globalStateRoot.toAbsolutePath().normalize();
        Path normalizedWorld = worldRoot.toAbsolutePath().normalize();
        ensureSafeDirectory(normalizedGlobal);
        ensureSafeDirectory(normalizedGlobal.resolve("singleplayer"));
        ensureSafeDirectory(normalizedGlobal.resolve("singleplayer/lineages"));
        Path worldData = normalizedWorld.resolve("data");
        ensureSafeDirectory(worldData);
        Path bindingRoot = worldData.resolve("world_lifecycle_manager");
        ensureSafeDirectory(bindingRoot);
        Path bindingPath = bindingRoot.resolve(BINDING_FILE);
        if (Files.exists(bindingPath, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(bindingPath, "single-player lineage binding");
            return loadBound(normalizedGlobal, PrestigeContracts.readSingleplayerBinding(bindingPath));
        }

        String lineageId = PrestigeContracts.newLineageId();
        PrestigeContracts.Lineage lineage = new PrestigeContracts.Lineage(lineageId, 0, 0);
        Path stateRoot = lineageStateRoot(normalizedGlobal, lineageId);
        ensureSafeDirectory(stateRoot);
        Path lineagePath = stateRoot.resolve("lineage-v5.tsv");
        if (Files.exists(lineagePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("new single-player lineage state already exists");
        }
        PrestigeContracts.writeLineage(lineagePath, lineage);
        PrestigeContracts.writeSingleplayerBinding(bindingPath,
                new PrestigeContracts.SingleplayerBinding(lineageId, lineage.generation()));
        return new Context(stateRoot, lineage);
    }

    private static Context loadBound(Path globalStateRoot, PrestigeContracts.SingleplayerBinding binding)
            throws IOException {
        Path stateRoot = lineageStateRoot(globalStateRoot, binding.lineageId());
        requireSafeDirectory(stateRoot);
        Path lineagePath = stateRoot.resolve("lineage-v5.tsv");
        requireRegularFile(lineagePath, "single-player lineage state");
        PrestigeContracts.Lineage lineage = PrestigeContracts.readLineage(lineagePath);
        if (!lineage.lineageId().equals(binding.lineageId()) || lineage.generation() != binding.generation()) {
            throw new IllegalStateException("single-player world binding does not match durable lineage state");
        }
        return new Context(stateRoot, lineage);
    }

    private static Path lineageStateRoot(Path globalStateRoot, String lineageId) {
        PrestigeContracts.validateId("lineage ID", lineageId);
        Path lineages = globalStateRoot.resolve("singleplayer/lineages").normalize();
        Path result = lineages.resolve(lineageId).normalize();
        if (!result.startsWith(lineages)) throw new IllegalArgumentException("single-player lineage path escaped state root");
        return result;
    }

    private static void ensureSafeDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                throw new IOException("single-player lineage state root is unsafe");
            }
            return;
        }
        Files.createDirectories(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("single-player lineage state root is unsafe");
        }
    }

    private static void requireSafeDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("single-player lineage state root is missing or unsafe");
        }
    }

    private static void requireRegularFile(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException(label + " is not a regular file");
        }
    }
}
