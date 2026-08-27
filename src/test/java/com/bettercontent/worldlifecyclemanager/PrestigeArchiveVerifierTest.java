package com.bettercontent.worldlifecyclemanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PrestigeArchiveVerifierTest {
    @TempDir Path temp;

    @Test void verifiesTheCanonicalSupervisorArchiveManifestName() throws Exception {
        assertEquals("world-lifecycle-manager-archive-manifest-v1.tsv", PrestigeArchiveVerifier.MANIFEST_NAME);
        byte[] level = "level".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(level));
        byte[] manifest = ("BC_PRESTIGE_ARCHIVE_V1\n"
                + "lineage\tlineage-test\n"
                + "transaction\ttransaction-test\n"
                + "file_count\t1\n"
                + "file\tbGV2ZWwuZGF0\t" + level.length + "\t" + digest + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Path archive = temp.resolve("prestige.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(PrestigeArchiveVerifier.MANIFEST_NAME));
            zip.write(manifest);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("world/level.dat"));
            zip.write(level);
            zip.closeEntry();
        }

        assertDoesNotThrow(() -> PrestigeArchiveVerifier.main(new String[] {
                "verify", archive.toString(), "lineage-test", "transaction-test"
        }));
    }

    @Test void generatorUsesTheSameOrderingContractAsTheVerifier() throws Exception {
        Path world = temp.resolve("world");
        Files.createDirectories(world.resolve("nested"));
        for (String relative : List.of("level.dat", "a.dat", "A.dat", "_.dat", "nested/z.dat", "é.dat")) {
            Path file = world.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, relative, StandardCharsets.UTF_8);
        }
        Path manifest = temp.resolve("manifest.tsv");

        PrestigeArchiveVerifier.generateManifest(world, manifest, "lineage-test", "transaction-test");

        assertDoesNotThrow(() -> PrestigeArchiveVerifier.validateManifest(Files.readAllBytes(manifest)));
        List<String> decoded = Files.readAllLines(manifest, StandardCharsets.UTF_8).subList(4, 10).stream()
                .map(line -> new String(java.util.Base64.getUrlDecoder().decode(line.split("\\t")[1]), StandardCharsets.UTF_8))
                .toList();
        assertEquals(List.of("A.dat", "_.dat", "a.dat", "level.dat", "nested/z.dat", "é.dat"), decoded);
    }

    @Test void verifierRejectsLocaleSortedInsteadOfJavaSortedPaths() throws Exception {
        byte[] level = "level".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(level));
        byte[] manifest = ("BC_PRESTIGE_ARCHIVE_V1\n"
                + "lineage\tlineage-test\n"
                + "transaction\ttransaction-test\n"
                + "file_count\t2\n"
                + "file\tYS5kYXQ\t" + level.length + "\t" + digest + "\n"
                + "file\tQS5kYXQ\t" + level.length + "\t" + digest + "\n")
                .getBytes(StandardCharsets.UTF_8);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PrestigeArchiveVerifier.validateManifest(manifest));
        assertEquals("manifest paths are not sorted", error.getMessage());
    }

    @Test void generatorRejectsSymbolicLinks() throws Exception {
        Path world = temp.resolve("linked-world");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "level", StandardCharsets.UTF_8);
        Files.createSymbolicLink(world.resolve("linked.dat"), world.resolve("level.dat"));

        assertThrows(IllegalArgumentException.class, () -> PrestigeArchiveVerifier.generateManifest(
                world, temp.resolve("linked.tsv"), "lineage-test", "transaction-test"));
    }
}
