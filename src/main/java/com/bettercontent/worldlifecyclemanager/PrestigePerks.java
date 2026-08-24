package com.bettercontent.worldlifecyclemanager;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PrestigePerks {
    public static final String ACTIVE_MAGIC = "BC_PRESTIGE_PERKS_V1";
    public static final String DRAFT_MAGIC = "BC_PRESTIGE_PERK_DRAFT_V1";
    public static final String STAGED_MAGIC = "BC_PRESTIGE_STAGED_PERKS_V1";
    public static final String RESET_MAGIC = "BC_PRESTIGE_RESET_PERKS_V1";
    public static final String HEALTH_MAGIC = "BC_PRESTIGE_PERK_HEALTH_V1";
    public static final int SAFE_RADIUS = 128;
    public static final int VILLAGE_RADIUS_CHUNKS = 128;

    public enum Perk {
        EXPANDED_ATTUNEMENT("expanded_attunement", null),
        FRONTIER_ATTUNEMENT("frontier_attunement", EXPANDED_ATTUNEMENT),
        SAFE_ARRIVAL("safe_arrival", null),
        SETTLED_ARRIVAL("settled_arrival", SAFE_ARRIVAL),
        FALLBACK_ATTUNEMENT("fallback_attunement", null),
        FOURTH_HORIZON("fourth_horizon", FALLBACK_ATTUNEMENT);

        private final String id;
        private final Perk parent;
        Perk(String id, Perk parent) { this.id = id; this.parent = parent; }
        public String id() { return id; }
        public Perk parent() { return parent; }
        static Perk parse(String value) {
            for (Perk perk : values()) if (perk.id.equals(value)) return perk;
            throw new IllegalArgumentException("unknown prestige perk: " + value);
        }
    }

    public enum Landing { BIOME, VILLAGE;
        static Landing parse(String value) {
            try { return valueOf(value.toUpperCase(Locale.ROOT)); }
            catch (Exception error) { throw new IllegalArgumentException("landing mode must be biome or village"); }
        }
    }

    public record Build(String lineageId, long baseGeneration, EnumSet<Perk> perks, Landing landing,
                        String fallbackBiome) {
        public Build {
            perks = perks.isEmpty() ? EnumSet.noneOf(Perk.class) : EnumSet.copyOf(perks);
            fallbackBiome = fallbackBiome == null ? "" : fallbackBiome;
        }
        public long targetGeneration() { return baseGeneration + 1; }
        public int budget() { return (int) Math.min(Perk.values().length, targetGeneration()); }
        public boolean has(Perk perk) { return perks.contains(perk); }
        public List<String> ids() { return perks.stream().map(Perk::id).toList(); }
    }

    private PrestigePerks() {}

    public static Path activePath(MinecraftServer server) { return PrestigeService.state(server).resolve("perks-v1.tsv"); }
    public static Path draftPath(MinecraftServer server) { return PrestigeService.control(server).resolve("perk-draft-v1.tsv"); }
    public static Path stagedPath(MinecraftServer server) { return PrestigeService.control(server).resolve("staged-perks-v1.tsv"); }
    public static Path resetPath(MinecraftServer server) { return PrestigeService.control(server).resolve("reset-perks-v1.tsv"); }
    public static Path healthPath(MinecraftServer server) { return PrestigeService.control(server).resolve("perk-health-v1.tsv"); }

    public static Build draft(MinecraftServer server) throws IOException {
        PrestigeContracts.Lineage lineage = PrestigeService.lineage(server);
        Path path = draftPath(server);
        if (Files.isRegularFile(path)) return readBuild(path, DRAFT_MAGIC, lineage, lineage.generation());
        EnumSet<Perk> inherited = readActive(server, lineage);
        return new Build(lineage.lineageId(), lineage.generation(), inherited, Landing.BIOME, "");
    }

    public static Build staged(MinecraftServer server) throws IOException {
        PrestigeContracts.Lineage lineage = PrestigeService.lineage(server);
        return readBuild(stagedPath(server), STAGED_MAGIC, lineage, lineage.generation());
    }

    public static Build reset(MinecraftServer server, PrestigeContracts.Successor successor) throws IOException {
        if (!Files.isRegularFile(resetPath(server))) return new Build(successor.lineageId(), successor.baseGeneration(),
                EnumSet.noneOf(Perk.class), Landing.BIOME, "");
        Build build = readBuild(resetPath(server), RESET_MAGIC, new PrestigeContracts.Lineage(
                successor.lineageId(), successor.baseGeneration(), successor.baseGeneration()), successor.baseGeneration());
        Map<String, String> fields = readFields(resetPath(server), RESET_MAGIC);
        if (!fields.getOrDefault("transaction", "").equals(successor.transactionId())) {
            throw new IllegalStateException("perk snapshot transaction does not match successor");
        }
        List<String> allowed = PrestigeService.allowedBiomes(server, build);
        if (!allowed.contains(successor.biome())) throw new IllegalStateException("perk snapshot primary biome is not unlocked");
        if (!build.fallbackBiome().isEmpty() && (!allowed.contains(build.fallbackBiome())
                || build.fallbackBiome().equals(successor.biome()))) {
            throw new IllegalStateException("perk snapshot fallback biome is not distinct and unlocked");
        }
        return build;
    }

    public static void toggle(ServerPlayer player, String id) throws IOException {
        requireEditable(player);
        toggle(player.server, id);
    }

    public static void toggle(MinecraftServer server, String id) throws IOException {
        requireEditable(server);
        Build current = draft(server);
        Perk perk = Perk.parse(id);
        EnumSet<Perk> selected = current.perks().clone();
        if (selected.contains(perk)) {
            for (Perk candidate : Perk.values()) if (candidate.parent() == perk && selected.contains(candidate)) {
                throw new IllegalStateException("refund the dependent perk first");
            }
            if (perk == Perk.SETTLED_ARRIVAL && current.landing() == Landing.VILLAGE) {
                throw new IllegalStateException("switch landing back to biome first");
            }
            if (perk == Perk.FALLBACK_ATTUNEMENT && !current.fallbackBiome().isEmpty()) {
                throw new IllegalStateException("clear the fallback biome first");
            }
            selected.remove(perk);
        } else {
            if (perk.parent() != null && !selected.contains(perk.parent())) throw new IllegalStateException("unlock the branch root first");
            if (selected.size() >= current.budget()) throw new IllegalStateException("no prestige perk points remain");
            selected.add(perk);
        }
        writeBuild(draftPath(server), DRAFT_MAGIC, new Build(current.lineageId(), current.baseGeneration(),
                selected, current.landing(), current.fallbackBiome()), null);
    }

    public static void allocate(MinecraftServer server, String id) throws IOException {
        Perk perk = Perk.parse(id);
        if (draft(server).has(perk)) throw new IllegalStateException("perk is already allocated");
        toggle(server, id);
    }

    public static void refund(MinecraftServer server, String id) throws IOException {
        Perk perk = Perk.parse(id);
        if (!draft(server).has(perk)) throw new IllegalStateException("perk is not allocated");
        toggle(server, id);
    }

    public static void setLanding(ServerPlayer player, String value) throws IOException {
        requireEditable(player);
        setLanding(player.server, value);
    }

    public static void setLanding(MinecraftServer server, String value) throws IOException {
        requireEditable(server);
        Build current = draft(server);
        Landing landing = Landing.parse(value);
        if (landing == Landing.VILLAGE && !current.has(Perk.SETTLED_ARRIVAL)) throw new IllegalStateException("Settled Arrival is not unlocked");
        writeBuild(draftPath(server), DRAFT_MAGIC, new Build(current.lineageId(), current.baseGeneration(),
                current.perks(), landing, landing == Landing.VILLAGE ? "" : current.fallbackBiome()), null);
    }

    public static void setFallback(ServerPlayer player, String value) throws IOException {
        requireEditable(player);
        setFallback(player.server, value);
    }

    public static void setFallback(MinecraftServer server, String value) throws IOException {
        requireEditable(server);
        Build current = draft(server);
        if (!current.has(Perk.FALLBACK_ATTUNEMENT)) throw new IllegalStateException("Fallback Attunement is not unlocked");
        if (current.landing() != Landing.BIOME) throw new IllegalStateException("fallback biome requires biome landing");
        String biome = value.equals("clear") ? "" : value;
        if (!biome.isEmpty()) PrestigeContracts.validateBiome(biome);
        writeBuild(draftPath(server), DRAFT_MAGIC, new Build(current.lineageId(), current.baseGeneration(),
                current.perks(), current.landing(), biome), null);
    }

    public static void stage(MinecraftServer server, String primaryBiome) throws IOException {
        Build build = draft(server);
        validateBuild(server, build, primaryBiome);
        writeBuild(stagedPath(server), STAGED_MAGIC, build, null);
    }

    public static void cancel(MinecraftServer server) throws IOException { Files.deleteIfExists(stagedPath(server)); }

    public static void commit(MinecraftServer server, String transaction, String primaryBiome) throws IOException {
        Build build = staged(server);
        validateBuild(server, build, primaryBiome);
        writeBuild(resetPath(server), RESET_MAGIC, build, transaction);
    }

    public static void writeHealth(MinecraftServer server, PrestigeContracts.Successor successor, Build build,
                                   String resolvedTarget, boolean safe) throws IOException {
        writeAtomic(healthPath(server), List.of(HEALTH_MAGIC,
                "transaction\t" + successor.transactionId(), "attempt\t" + successor.attempt(),
                "landing\t" + build.landing().name().toLowerCase(Locale.ROOT),
                "resolved_target\t" + resolvedTarget, "safe\t" + safe));
    }

    public static List<String> allowedBiomes(MinecraftServer server, Build build) throws IOException {
        List<String> result = new ArrayList<>(readBiomeFile(server, "world_lifecycle_manager-biomes.txt"));
        if (build.has(Perk.EXPANDED_ATTUNEMENT)) result.addAll(readBiomeFile(server, "world_lifecycle_manager-biomes-expanded.txt"));
        if (build.has(Perk.FRONTIER_ATTUNEMENT)) result.addAll(readBiomeFile(server, "world_lifecycle_manager-biomes-frontier.txt"));
        return result.stream().distinct().sorted().toList();
    }

    private static List<String> readBiomeFile(MinecraftServer server, String name) throws IOException {
        Path path = server.getServerDirectory().toPath().toAbsolutePath().normalize().resolve("config").resolve(name);
        if (!Files.isRegularFile(path)) throw new IllegalStateException("missing config/" + name);
        List<String> values = Files.readAllLines(path, StandardCharsets.UTF_8).stream().map(String::strip)
                .filter(value -> !value.isEmpty() && !value.startsWith("#")).distinct().toList();
        PrestigeLimits.requireSize(name, values, PrestigeLimits.MAX_BIOMES);
        for (String value : values) PrestigeContracts.validateBiome(value);
        return values;
    }

    private static void validateBuild(MinecraftServer server, Build build, String primaryBiome) throws IOException {
        validateShape(build);
        if (build.landing() == Landing.VILLAGE && !build.has(Perk.SETTLED_ARRIVAL)) throw new IllegalArgumentException("village landing requires Settled Arrival");
        List<String> allowed = allowedBiomes(server, build);
        if (!allowed.contains(primaryBiome)) throw new IllegalArgumentException("primary biome is not unlocked");
        if (!build.fallbackBiome().isEmpty()) {
            if (!build.has(Perk.FALLBACK_ATTUNEMENT) || build.landing() != Landing.BIOME) throw new IllegalArgumentException("fallback biome is not available");
            if (build.fallbackBiome().equals(primaryBiome) || !allowed.contains(build.fallbackBiome())) throw new IllegalArgumentException("fallback biome must be distinct and unlocked");
        }
    }

    static void validateShape(Build build) {
        if (build.baseGeneration() == Long.MAX_VALUE || build.perks().size() > build.budget()) throw new IllegalArgumentException("perk build exceeds its target-generation budget");
        for (Perk perk : build.perks()) if (perk.parent() != null && !build.has(perk.parent())) throw new IllegalArgumentException("perk prerequisite is missing: " + perk.id());
        if (build.landing() == Landing.VILLAGE && !build.has(Perk.SETTLED_ARRIVAL)) throw new IllegalArgumentException("village landing requires Settled Arrival");
        if (!build.fallbackBiome().isEmpty() && (!build.has(Perk.FALLBACK_ATTUNEMENT) || build.landing() != Landing.BIOME)) {
            throw new IllegalArgumentException("fallback biome is not available");
        }
    }

    private static void requireEditable(ServerPlayer player) {
        if (!player.hasPermissions(4)) throw new IllegalArgumentException("permission level 4 required");
        requireEditable(player.server);
    }

    private static void requireEditable(MinecraftServer server) {
        if (Files.exists(stagedPath(server)) || Files.exists(PrestigeService.control(server).resolve("reset-request-v4.tsv"))) {
            throw new IllegalStateException("perk build is locked by a staged reset");
        }
    }

    private static EnumSet<Perk> readActive(MinecraftServer server, PrestigeContracts.Lineage lineage) throws IOException {
        Path path = activePath(server);
        if (!Files.isRegularFile(path)) return EnumSet.noneOf(Perk.class);
        Map<String, String> fields = readFields(path, ACTIVE_MAGIC);
        if (!lineage.lineageId().equals(fields.get("lineage")) || lineage.generation() != parseLong(fields, "generation")) {
            throw new IllegalStateException("active perk state does not match lineage generation");
        }
        EnumSet<Perk> perks = parsePerks(fields.getOrDefault("perks", ""));
        if (perks.size() > Math.min(Perk.values().length, lineage.generation())) throw new IllegalArgumentException("active perks exceed completed prestige points");
        for (Perk perk : perks) if (perk.parent() != null && !perks.contains(perk.parent())) throw new IllegalArgumentException("active perk prerequisite is missing");
        return perks;
    }

    private static Build readBuild(Path path, String magic, PrestigeContracts.Lineage lineage, long baseGeneration) throws IOException {
        Map<String, String> fields = readFields(path, magic);
        if (!lineage.lineageId().equals(fields.get("lineage")) || parseLong(fields, "base_generation") != baseGeneration
                || parseLong(fields, "target_generation") != baseGeneration + 1) throw new IllegalStateException("perk build identity is stale");
        Build build = new Build(lineage.lineageId(), baseGeneration, parsePerks(fields.getOrDefault("perks", "")),
                Landing.parse(fields.get("landing")), decodeOptional(fields.get("fallback")));
        validateShape(build);
        return build;
    }

    private static EnumSet<Perk> parsePerks(String value) {
        EnumSet<Perk> perks = EnumSet.noneOf(Perk.class);
        if (!value.isEmpty() && !value.equals("-")) for (String id : value.split(",", -1)) if (!perks.add(Perk.parse(id))) throw new IllegalArgumentException("duplicate prestige perk");
        return perks;
    }

    private static void writeBuild(Path path, String magic, Build build, String transaction) throws IOException {
        List<String> lines = new ArrayList<>(List.of(magic, "lineage\t" + build.lineageId(),
                "base_generation\t" + build.baseGeneration(), "target_generation\t" + build.targetGeneration()));
        if (transaction != null) lines.add("transaction\t" + transaction);
        lines.add("perks\t" + (build.ids().isEmpty() ? "-" : String.join(",", build.ids())));
        lines.add("landing\t" + build.landing().name().toLowerCase(Locale.ROOT));
        lines.add("fallback\t" + (build.fallbackBiome().isEmpty() ? "-" : build.fallbackBiome()));
        writeAtomic(path, lines);
    }

    private static Map<String, String> readFields(Path path, String magic) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals(magic)) throw new IllegalArgumentException("invalid prestige perk contract");
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 1; index < lines.size(); index++) {
            String[] row = lines.get(index).split("\\t", -1);
            if (row.length != 2 || fields.putIfAbsent(row[0], row[1]) != null) throw new IllegalArgumentException("invalid prestige perk fields");
        }
        return fields;
    }

    private static long parseLong(Map<String, String> fields, String key) {
        try { long value = Long.parseLong(fields.get(key)); if (value < 0) throw new NumberFormatException(); return value; }
        catch (Exception error) { throw new IllegalArgumentException("invalid prestige perk " + key); }
    }
    private static String decodeOptional(String value) { return value == null || value.equals("-") ? "" : value; }

    private static void writeAtomic(Path path, List<String> lines) throws IOException {
        Files.createDirectories(path.getParent());
        Path partial = path.resolveSibling(path.getFileName() + ".partial");
        Files.writeString(partial, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        try { Files.move(partial, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException error) { throw new IOException("perk state filesystem lacks atomic moves", error); }
    }
}
