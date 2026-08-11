package com.bettercontent.prestige;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Closed, ordered contracts shared with the dedicated prestige supervisor. */
public final class PrestigeContracts {
    public static final String LINEAGE_MAGIC = "BC_PRESTIGE_LINEAGE_V4";
    public static final String DRAFT_MAGIC = "BC_PRESTIGE_DRAFT_V4";
    public static final String STAGED_MAGIC = "BC_PRESTIGE_STAGED_V4";
    public static final String RESET_MAGIC = "BC_PRESTIGE_RESET_V4";
    public static final String SUCCESSOR_MAGIC = "BC_PRESTIGE_SUCCESSOR_V4";
    public static final String HEALTH_MAGIC = "BC_PRESTIGE_HEALTH_V4";
    public static final String SHUTDOWN_MAGIC = "BC_PRESTIGE_SHUTDOWN_V4";
    public static final String WORLD_BINDING_MAGIC = "BC_PRESTIGE_WORLD_BINDING_V4";
    public static final String ACTIVE_SUCCESSOR_MAGIC = "BC_PRESTIGE_ACTIVE_SUCCESSOR_V1";

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern WORLD = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern AUTHOR = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private PrestigeContracts() {}

    public record Lineage(String lineageId, long totalPrestiges, long generation) {}
    public record Draft(String lineageId, long generation, String biome, String author, String worldName) {}
    public record Staged(String lineageId, long generation, String biome, String author, String worldName) {}
    public record Reset(String lineageId, long baseGeneration, String transactionId, String worldName, long oldSeed, String biome) {}
    public record Successor(String lineageId, long baseGeneration, long targetGeneration, String transactionId,
                            long successorSeed, String biome, int attempt) {}
    public record WorldBinding(String lineageId, long baseGeneration, String transactionId, String worldName,
                               long oldSeed, String biome) {}
    public record ActiveSuccessor(long pid, long startTicks, String lineageId, String transactionId, int attempt) {}

    public static String newTransactionId() {
        return "transaction-" + UUID.randomUUID().toString().replace("-", "");
    }

    public static void validateId(String label, String value) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must match " + ID.pattern());
        }
    }

    public static void validateWorldName(String value) {
        if (value == null || !WORLD.matcher(value).matches() || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("world name is outside the supported prestige contract");
        }
    }

    public static void validateBiome(String value) {
        if (value == null || !RESOURCE.matcher(value).matches()) {
            throw new IllegalArgumentException("biome is not a resource location: " + value);
        }
    }

    public static void validateAuthor(String value) {
        if (value == null || !AUTHOR.matcher(value).matches()) {
            throw new IllegalArgumentException("author is not a Minecraft profile name: " + value);
        }
    }

    public static Lineage readLineage(Path path) throws IOException {
        Map<String, String> fields = read(path, LINEAGE_MAGIC,
                List.of("lineage", "total_prestiges", "generation"));
        String lineage = fields.get("lineage");
        validateId("lineage ID", lineage);
        long total = parseNonNegativeLong("total_prestiges", fields.get("total_prestiges"));
        long generation = parseNonNegativeLong("generation", fields.get("generation"));
        if (generation != total) throw new IllegalArgumentException("generation must equal total_prestiges");
        return new Lineage(lineage, total, generation);
    }

    public static Draft readDraft(Path path) throws IOException {
        Map<String, String> fields = read(path, DRAFT_MAGIC, List.of("lineage", "generation", "biome", "author", "world"));
        return validatedDraft(fields);
    }

    public static Staged readStaged(Path path) throws IOException {
        Map<String, String> fields = read(path, STAGED_MAGIC, List.of("lineage", "generation", "biome", "author", "world"));
        Draft draft = validatedDraft(fields);
        return new Staged(draft.lineageId(), draft.generation(), draft.biome(), draft.author(), draft.worldName());
    }

    private static Draft validatedDraft(Map<String, String> fields) {
        String lineage = fields.get("lineage");
        long generation = parseNonNegativeLong("generation", fields.get("generation"));
        String biome = fields.get("biome");
        String author = fields.get("author");
        String world = fields.get("world");
        validateId("lineage ID", lineage);
        validateBiome(biome);
        validateAuthor(author);
        validateWorldName(world);
        return new Draft(lineage, generation, biome, author, world);
    }

    public static Reset readReset(Path path) throws IOException {
        Map<String, String> fields = read(path, RESET_MAGIC,
                List.of("state", "lineage", "base_generation", "transaction", "world", "old_seed", "biome", "seed_mode"));
        if (!fields.get("state").equals("committed")) throw new IllegalArgumentException("reset state is not committed");
        if (!fields.get("seed_mode").equals("random")) throw new IllegalArgumentException("seed_mode is not random");
        String lineage = fields.get("lineage");
        long baseGeneration = parseNonNegativeLong("base_generation", fields.get("base_generation"));
        String transaction = fields.get("transaction");
        String world = fields.get("world");
        String biome = fields.get("biome");
        validateId("lineage ID", lineage);
        validateId("transaction ID", transaction);
        validateWorldName(world);
        validateBiome(biome);
        return new Reset(lineage, baseGeneration, transaction, world, parseLong("old_seed", fields.get("old_seed")), biome);
    }

    public static Successor readSuccessor(Path path) throws IOException {
        Map<String, String> fields = read(path, SUCCESSOR_MAGIC,
                List.of("lineage", "base_generation", "target_generation", "transaction", "successor_seed", "biome", "attempt"));
        String lineage = fields.get("lineage");
        long baseGeneration = parseNonNegativeLong("base_generation", fields.get("base_generation"));
        long targetGeneration = parseNonNegativeLong("target_generation", fields.get("target_generation"));
        if (baseGeneration == Long.MAX_VALUE || targetGeneration != baseGeneration + 1) {
            throw new IllegalArgumentException("successor generations are not consecutive");
        }
        String transaction = fields.get("transaction");
        String biome = fields.get("biome");
        validateId("lineage ID", lineage);
        validateId("transaction ID", transaction);
        validateBiome(biome);
        long attempt = parseNonNegativeLong("attempt", fields.get("attempt"));
        if (attempt < 1 || attempt > 3) throw new IllegalArgumentException("attempt must be in 1..3");
        return new Successor(lineage, baseGeneration, targetGeneration, transaction,
                parseLong("successor_seed", fields.get("successor_seed")), biome, (int) attempt);
    }

    public static WorldBinding readWorldBinding(Path path) throws IOException {
        Map<String, String> fields = read(path, WORLD_BINDING_MAGIC,
                List.of("lineage", "base_generation", "transaction", "world", "old_seed", "biome"));
        String lineage = fields.get("lineage");
        String transaction = fields.get("transaction");
        String world = fields.get("world");
        String biome = fields.get("biome");
        validateId("lineage ID", lineage);
        validateId("transaction ID", transaction);
        validateWorldName(world);
        validateBiome(biome);
        return new WorldBinding(lineage, parseNonNegativeLong("base_generation", fields.get("base_generation")),
                transaction, world, parseLong("old_seed", fields.get("old_seed")), biome);
    }

    public static String readShutdownTransaction(Path path) throws IOException {
        Map<String, String> fields = read(path, SHUTDOWN_MAGIC, List.of("transaction"));
        String transaction = fields.get("transaction");
        validateId("transaction ID", transaction);
        return transaction;
    }

    public static ActiveSuccessor readActiveSuccessor(Path path) throws IOException {
        Map<String, String> fields = read(path, ACTIVE_SUCCESSOR_MAGIC,
                List.of("pid", "start_ticks", "lineage", "transaction", "attempt"));
        long pid = parsePositiveLong("pid", fields.get("pid"));
        long startTicks = parsePositiveLong("start_ticks", fields.get("start_ticks"));
        String lineage = fields.get("lineage");
        String transaction = fields.get("transaction");
        validateId("lineage ID", lineage);
        validateId("transaction ID", transaction);
        long attempt = parseNonNegativeLong("attempt", fields.get("attempt"));
        if (attempt < 1 || attempt > 3) throw new IllegalArgumentException("attempt must be in 1..3");
        return new ActiveSuccessor(pid, startTicks, lineage, transaction, (int) attempt);
    }

    public static void writeLineage(Path path, Lineage lineage) throws IOException {
        validateId("lineage ID", lineage.lineageId());
        if (lineage.totalPrestiges() < 0 || lineage.generation() < 0
                || lineage.generation() != lineage.totalPrestiges()) {
            throw new IllegalArgumentException("invalid lineage counters");
        }
        writeAtomic(path, List.of(LINEAGE_MAGIC,
                "lineage\t" + lineage.lineageId(),
                "total_prestiges\t" + lineage.totalPrestiges(),
                "generation\t" + lineage.generation()));
    }

    public static void writeDraft(Path path, Draft draft) throws IOException {
        validateDraft(draft.lineageId(), draft.generation(), draft.biome(), draft.author(), draft.worldName());
        writeAtomic(path, List.of(DRAFT_MAGIC,
                "lineage\t" + draft.lineageId(), "generation\t" + draft.generation(), "biome\t" + draft.biome(),
                "author\t" + draft.author(), "world\t" + draft.worldName()));
    }

    public static void writeStaged(Path path, Staged staged) throws IOException {
        validateDraft(staged.lineageId(), staged.generation(), staged.biome(), staged.author(), staged.worldName());
        writeAtomic(path, List.of(STAGED_MAGIC,
                "lineage\t" + staged.lineageId(), "generation\t" + staged.generation(), "biome\t" + staged.biome(),
                "author\t" + staged.author(), "world\t" + staged.worldName()));
    }

    private static void validateDraft(String lineage, long generation, String biome, String author, String world) {
        validateId("lineage ID", lineage);
        if (generation < 0) throw new IllegalArgumentException("generation is negative");
        validateBiome(biome);
        validateAuthor(author);
        validateWorldName(world);
    }

    public static void writeReset(Path path, Reset reset) throws IOException {
        validateId("lineage ID", reset.lineageId());
        if (reset.baseGeneration() < 0) throw new IllegalArgumentException("base generation is negative");
        validateId("transaction ID", reset.transactionId());
        validateWorldName(reset.worldName());
        validateBiome(reset.biome());
        writeAtomic(path, List.of(RESET_MAGIC,
                "state\tcommitted", "lineage\t" + reset.lineageId(),
                "base_generation\t" + reset.baseGeneration(),
                "transaction\t" + reset.transactionId(), "world\t" + reset.worldName(),
                "old_seed\t" + reset.oldSeed(), "biome\t" + reset.biome(), "seed_mode\trandom"));
    }

    public static void writeWorldBinding(Path path, WorldBinding binding) throws IOException {
        validateId("lineage ID", binding.lineageId());
        validateId("transaction ID", binding.transactionId());
        validateWorldName(binding.worldName());
        validateBiome(binding.biome());
        if (binding.baseGeneration() < 0) throw new IllegalArgumentException("base generation is negative");
        writeAtomic(path, List.of(WORLD_BINDING_MAGIC, "lineage\t" + binding.lineageId(),
                "base_generation\t" + binding.baseGeneration(), "transaction\t" + binding.transactionId(),
                "world\t" + binding.worldName(), "old_seed\t" + binding.oldSeed(), "biome\t" + binding.biome()));
    }

    public static void writeHealth(Path path, Successor successor, long actualSeed, String actualBiome,
                                   String worldName, boolean freshPlayers) throws IOException {
        validateWorldName(worldName);
        validateBiome(actualBiome);
        String status = actualSeed == successor.successorSeed()
                && actualBiome.equals(successor.biome()) && freshPlayers ? "healthy" : "mismatch";
        writeAtomic(path, List.of(HEALTH_MAGIC,
                "lineage\t" + successor.lineageId(), "base_generation\t" + successor.baseGeneration(),
                "target_generation\t" + successor.targetGeneration(), "transaction\t" + successor.transactionId(),
                "successor_seed\t" + successor.successorSeed(), "actual_seed\t" + actualSeed,
                "requested_biome\t" + successor.biome(), "actual_biome\t" + actualBiome,
                "attempt\t" + successor.attempt(), "world\t" + worldName,
                "level_dat\ttrue", "fresh_players\t" + freshPlayers, "status\t" + status));
    }

    private static Map<String, String> read(Path path, String magic, List<String> keys) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() != keys.size() + 1 || !lines.get(0).equals(magic)) {
            throw new IllegalArgumentException("invalid or unsupported contract: " + path.getFileName());
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < keys.size(); index++) {
            String line = lines.get(index + 1);
            int tab = line.indexOf('\t');
            if (tab <= 0 || line.indexOf('\t', tab + 1) >= 0 || !line.substring(0, tab).equals(keys.get(index))) {
                throw new IllegalArgumentException("invalid " + keys.get(index) + " contract field");
            }
            String value = line.substring(tab + 1);
            if (value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("blank or multiline " + keys.get(index) + " contract field");
            }
            result.put(keys.get(index), value);
        }
        return Map.copyOf(result);
    }

    private static long parseLong(String label, String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { throw new IllegalArgumentException(label + " is not a signed 64-bit integer", error); }
    }

    private static long parseNonNegativeLong(String label, String value) {
        long parsed = parseLong(label, value);
        if (parsed < 0) throw new IllegalArgumentException(label + " is negative");
        return parsed;
    }

    private static long parsePositiveLong(String label, String value) {
        long parsed = parseLong(label, value);
        if (parsed <= 0) throw new IllegalArgumentException(label + " is not positive");
        return parsed;
    }

    private static void writeAtomic(Path path, List<String> lines) throws IOException {
        Files.createDirectories(path.getParent());
        Path partial = path.resolveSibling(path.getFileName() + ".partial");
        if (Files.exists(partial)) throw new IOException("partial contract already exists: " + partial.getFileName());
        Files.writeString(partial, String.join("\n", new ArrayList<>(lines)) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        try (FileChannel channel = FileChannel.open(partial, StandardOpenOption.WRITE)) { channel.force(true); }
        try {
            Files.move(partial, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) { directory.force(true); }
        } catch (AtomicMoveNotSupportedException error) {
            Files.deleteIfExists(partial);
            throw new IOException("contract filesystem does not support atomic publication", error);
        }
    }
}
