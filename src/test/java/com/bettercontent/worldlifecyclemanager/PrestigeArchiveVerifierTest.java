package com.bettercontent.worldlifecyclemanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
