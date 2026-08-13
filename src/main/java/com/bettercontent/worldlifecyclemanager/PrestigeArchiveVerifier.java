package com.bettercontent.worldlifecyclemanager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Standard-library-only verifier used by the shell supervisor and operator CLI. */
public final class PrestigeArchiveVerifier {
    private static final String MANIFEST_NAME = "world_lifecycle_manager-archive-manifest-v1.tsv";
    private static final String MANIFEST_MAGIC = "BC_PRESTIGE_ARCHIVE_V1";
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final long MAX_MANIFEST_BYTES = 64L * 1024L * 1024L;

    private record FileRow(String path, long size, String sha256) {}
    private record Manifest(String lineage, String transaction, List<FileRow> files, byte[] raw) {}

    private PrestigeArchiveVerifier() {}

    public static void main(String[] args) {
        try {
            if (args.length == 4 && args[0].equals("verify")) {
                verify(Path.of(args[1]), null, args[2], args[3]);
            } else if (args.length == 5 && args[0].equals("verify-against")) {
                verify(Path.of(args[1]), Path.of(args[2]), args[3], args[4]);
            } else {
                throw new IllegalArgumentException("usage: PrestigeArchiveVerifier verify ARCHIVE LINEAGE TRANSACTION | "
                        + "verify-against ARCHIVE EXTERNAL_MANIFEST LINEAGE TRANSACTION");
            }
        } catch (Exception error) {
            System.err.println("prestige archive verification failed: "
                    + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            System.exit(1);
        }
    }

    private static void verify(Path archiveRaw, Path externalRaw, String expectedLineage,
                               String expectedTransaction) throws Exception {
        validateId("lineage", expectedLineage);
        validateId("transaction", expectedTransaction);
        Path archive = archiveRaw.toAbsolutePath().normalize();
        requireRegular(archive, "archive");
        byte[] external = null;
        if (externalRaw != null) {
            Path path = externalRaw.toAbsolutePath().normalize();
            requireRegular(path, "external manifest");
            if (Files.size(path) > MAX_MANIFEST_BYTES) throw new IllegalArgumentException("external manifest is oversized");
            external = Files.readAllBytes(path);
        }

        Manifest manifest;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<? extends ZipEntry> entries = zip.stream().toList();
            Set<String> names = new HashSet<>();
            for (ZipEntry entry : entries) {
                if (!names.add(entry.getName())) throw new IllegalArgumentException("duplicate ZIP entry: " + entry.getName());
                if (entry.isDirectory()) throw new IllegalArgumentException("directory ZIP entry is forbidden: " + entry.getName());
                if (!entry.getName().equals(MANIFEST_NAME)) {
                    if (!entry.getName().startsWith("world/")) {
                        throw new IllegalArgumentException("unexpected ZIP entry root: " + entry.getName());
                    }
                    validateRelativePath(entry.getName().substring("world/".length()));
                }
            }
            ZipEntry manifestEntry = zip.getEntry(MANIFEST_NAME);
            if (manifestEntry == null) throw new IllegalArgumentException("archive manifest is missing");
            if (manifestEntry.getSize() < 0 || manifestEntry.getSize() > MAX_MANIFEST_BYTES) {
                throw new IllegalArgumentException("archive manifest is oversized");
            }
            byte[] embedded = readBounded(zip.getInputStream(manifestEntry), MAX_MANIFEST_BYTES);
            if (external != null && !MessageDigest.isEqual(embedded, external)) {
                throw new IllegalArgumentException("archive manifest differs from staged source manifest");
            }
            manifest = parseManifest(embedded);
            if (!manifest.lineage().equals(expectedLineage)) throw new IllegalArgumentException("archive lineage mismatch");
            if (!manifest.transaction().equals(expectedTransaction)) throw new IllegalArgumentException("archive transaction mismatch");

            Set<String> expectedNames = new HashSet<>();
            expectedNames.add(MANIFEST_NAME);
            for (FileRow file : manifest.files()) expectedNames.add("world/" + file.path());
            if (!names.equals(expectedNames)) throw new IllegalArgumentException("ZIP entries do not exactly match the manifest");
            for (FileRow file : manifest.files()) {
                ZipEntry entry = zip.getEntry("world/" + file.path());
                if (entry == null || entry.getSize() != file.size()) {
                    throw new IllegalArgumentException("ZIP size mismatch for " + file.path());
                }
                String digest = sha256(zip.getInputStream(entry));
                if (!digest.equals(file.sha256())) throw new IllegalArgumentException("SHA-256 mismatch for " + file.path());
            }
        }
        System.out.println("ok - prestige archive v1 verified files=" + manifest.files().size()
                + " sha256=" + sha256(archive) + " lineage=" + expectedLineage + " transaction=" + expectedTransaction);
    }

    private static Manifest parseManifest(byte[] raw) {
        String text = new String(raw, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(text.getBytes(StandardCharsets.UTF_8), raw)) {
            throw new IllegalArgumentException("archive manifest is not valid UTF-8");
        }
        String[] lines = text.split("\\n", -1);
        if (lines.length < 6 || !lines[lines.length - 1].isEmpty()) throw new IllegalArgumentException("archive manifest is truncated");
        if (!lines[0].equals(MANIFEST_MAGIC)) throw new IllegalArgumentException("unsupported archive manifest version");
        String lineage = field(lines[1], "lineage");
        String transaction = field(lines[2], "transaction");
        validateId("lineage", lineage);
        validateId("transaction", transaction);
        int count;
        try { count = Integer.parseInt(field(lines[3], "file_count")); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("manifest file_count is invalid", error); }
        if (count < 1 || lines.length != count + 5) throw new IllegalArgumentException("manifest file_count does not match rows");
        List<FileRow> files = new ArrayList<>(count);
        Set<String> seen = new HashSet<>();
        String previous = null;
        for (int index = 0; index < count; index++) {
            String[] parts = lines[index + 4].split("\\t", -1);
            if (parts.length != 4 || !parts[0].equals("file")) throw new IllegalArgumentException("invalid manifest file row");
            String path = decodePath(parts[1]);
            if (!seen.add(path)) throw new IllegalArgumentException("duplicate manifest path: " + path);
            if (previous != null && previous.compareTo(path) >= 0) throw new IllegalArgumentException("manifest paths are not sorted");
            previous = path;
            long size;
            try { size = Long.parseLong(parts[2]); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("invalid manifest size for " + path, error); }
            if (size < 0 || !SHA256.matcher(parts[3]).matches()) throw new IllegalArgumentException("invalid manifest metadata for " + path);
            files.add(new FileRow(path, size, parts[3]));
        }
        if (files.stream().noneMatch(file -> file.path().equals("level.dat"))) {
            throw new IllegalArgumentException("archive manifest lacks level.dat");
        }
        return new Manifest(lineage, transaction, List.copyOf(files), raw);
    }

    private static String decodePath(String encoded) {
        if (encoded.isEmpty()) throw new IllegalArgumentException("blank manifest path encoding");
        byte[] bytes;
        try { bytes = Base64.getUrlDecoder().decode(encoded); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("non-canonical manifest path encoding", error); }
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded.getBytes(StandardCharsets.UTF_8)).equals(encoded)) {
            throw new IllegalArgumentException("non-canonical manifest path encoding");
        }
        validateRelativePath(decoded);
        return decoded;
    }

    private static void validateRelativePath(String path) {
        if (path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains("\n")
                || path.contains("\r") || path.contains("\t")) throw new IllegalArgumentException("unsafe archive path: " + path);
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("unsafe archive path segment: " + path);
            }
        }
    }

    private static String field(String line, String key) {
        String prefix = key + "\t";
        if (!line.startsWith(prefix) || line.indexOf('\t', prefix.length()) >= 0 || line.length() == prefix.length()) {
            throw new IllegalArgumentException("invalid " + key + " manifest field");
        }
        return line.substring(prefix.length());
    }

    private static void validateId(String label, String value) {
        if (!ID.matcher(value).matches()) throw new IllegalArgumentException(label + " ID is invalid");
    }

    private static void requireRegular(Path path, String label) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException(label + " is not a regular file: " + path);
        }
    }

    private static byte[] readBounded(InputStream input, long maximum) throws IOException {
        try (input) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maximum) throw new IOException("archive manifest exceeds size limit");
                if (read > 0) output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String sha256(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) { return sha256(input); }
    }

    private static String sha256(InputStream input) throws Exception {
        try (input) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
