package com.bettercontent.worldlifecyclemanager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Server-authoritative paid prestige graph and its closed transaction contracts. */
public final class PrestigePerks {
    public static final String ACTIVE_MAGIC = "BC_PRESTIGE_PERKS_V1";
    public static final String DRAFT_MAGIC = "BC_PRESTIGE_PERK_DRAFT_V1";
    public static final String STAGED_MAGIC = "BC_PRESTIGE_STAGED_PERKS_V1";
    public static final String RESET_MAGIC = "BC_PRESTIGE_RESET_PERKS_V1";
    public static final String HEALTH_MAGIC = "BC_PRESTIGE_PERK_HEALTH_V2";
    public static final int SAFE_RADIUS = 128;
    public static final int VILLAGE_RADIUS_CHUNKS = 128;
    public static final int MAX_POINTS = 17;

    public enum Perk {
        EXPANDED_ATTUNEMENT("expanded_attunement"),
        FRONTIER_ATTUNEMENT("frontier_attunement"),
        SAFE_ARRIVAL("safe_arrival"),
        SETTLED_ARRIVAL("settled_arrival"),
        FALLBACK_ATTUNEMENT("fallback_attunement"),
        FOURTH_HORIZON("fourth_horizon"),
        CLASS_WAYFINDER("class_wayfinder"),
        CLASS_FIELD_COOK("class_field_cook"),
        CLASS_RAIL_SCOUT("class_rail_scout"),
        CLASS_FLOOD_RUNNER("class_flood_runner"),
        CLASS_MARKET_RUNNER("class_market_runner"),
        CLASS_TRAIL_WRANGLER("class_trail_wrangler"),
        EMBARK_BUDGET_I("embark_budget_i"),
        EMBARK_BUDGET_II("embark_budget_ii"),
        EMBARK_BUDGET_III("embark_budget_iii"),
        EMBARK_BUDGET_IV("embark_budget_iv"),
        SCHEMATICANNON_START("schematicannon_start");

        private final String id;
        Perk(String id) { this.id = id; }
        public String id() { return id; }
        static Perk parse(String value) {
            for (Perk perk : values()) if (perk.id.equals(value)) return perk;
            throw new IllegalArgumentException("unknown prestige perk: " + value);
        }
    }

    private static final EnumSet<Perk> ORIGINAL = EnumSet.of(
            Perk.EXPANDED_ATTUNEMENT, Perk.FRONTIER_ATTUNEMENT,
            Perk.SAFE_ARRIVAL, Perk.SETTLED_ARRIVAL,
            Perk.FALLBACK_ATTUNEMENT, Perk.FOURTH_HORIZON);
    private static final EnumSet<Perk> CLASSES = EnumSet.of(
            Perk.CLASS_WAYFINDER, Perk.CLASS_FIELD_COOK, Perk.CLASS_RAIL_SCOUT,
            Perk.CLASS_FLOOD_RUNNER, Perk.CLASS_MARKET_RUNNER, Perk.CLASS_TRAIL_WRANGLER);
    private static final Map<Perk, String> CLASS_IDS = Map.of(
            Perk.CLASS_WAYFINDER, "wayfinder",
            Perk.CLASS_FIELD_COOK, "field_cook",
            Perk.CLASS_RAIL_SCOUT, "rail_scout",
            Perk.CLASS_FLOOD_RUNNER, "flood_runner",
            Perk.CLASS_MARKET_RUNNER, "market_runner",
            Perk.CLASS_TRAIL_WRANGLER, "trail_wrangler");

    public enum Landing { BIOME, VILLAGE;
        static Landing parse(String value) {
            try { return valueOf(value.toUpperCase(Locale.ROOT)); }
            catch (Exception error) { throw new IllegalArgumentException("landing mode must be biome or village"); }
        }
    }

    public enum OnboardingMode { SPAWN_ONLY, CLASS, EMBARK }

    /** Stable API consumed by Class Selector. */
    public record OnboardingPolicy(OnboardingMode mode, Set<String> unlockedClassIds,
                                   int embarkBudget, boolean starterSchematicannon) {
        public OnboardingPolicy { unlockedClassIds = Set.copyOf(unlockedClassIds); }
    }

    public record Build(String lineageId, long baseGeneration, EnumSet<Perk> perks, Landing landing,
                        String fallbackBiome) {
        public Build {
            perks = perks.isEmpty() ? EnumSet.noneOf(Perk.class) : EnumSet.copyOf(perks);
            fallbackBiome = fallbackBiome == null ? "" : fallbackBiome;
        }
        public long targetGeneration() { return Math.addExact(baseGeneration, 1); }
        public int budget() { return (int) Math.min(MAX_POINTS, targetGeneration()); }
        public boolean has(Perk perk) { return perks.contains(perk); }
        public List<String> ids() { return perks.stream().map(Perk::id).toList(); }
        public boolean classSelectorUnlocked() { return perks.containsAll(ORIGINAL); }
        public boolean embarkUnlocked() { return classSelectorUnlocked() && perks.containsAll(CLASSES); }
        public int successorAttempts() { return has(Perk.FOURTH_HORIZON) ? 4 : 3; }
        public OnboardingPolicy onboardingPolicy() {
            if (embarkUnlocked()) {
                int quota = has(Perk.EMBARK_BUDGET_IV) ? 18 : has(Perk.EMBARK_BUDGET_III) ? 15
                        : has(Perk.EMBARK_BUDGET_II) ? 12 : has(Perk.EMBARK_BUDGET_I) ? 9 : 6;
                return new OnboardingPolicy(OnboardingMode.EMBARK, Set.of(), quota,
                        has(Perk.SCHEMATICANNON_START));
            }
            if (classSelectorUnlocked()) {
                var ids = new java.util.LinkedHashSet<String>();
                for (Perk perk : CLASSES) if (has(perk)) ids.add(CLASS_IDS.get(perk));
                return new OnboardingPolicy(OnboardingMode.CLASS, ids, 0, false);
            }
            return new OnboardingPolicy(OnboardingMode.SPAWN_ONLY, Set.of(), 0, false);
        }
    }

    private PrestigePerks() {}

    public static Path activePath(MinecraftServer server) { return PrestigeService.state(server).resolve("perks-v1.tsv"); }
    public static Path draftPath(MinecraftServer server) { return PrestigeService.control(server).resolve("perk-draft-v1.tsv"); }
    public static Path stagedPath(MinecraftServer server) { return PrestigeService.control(server).resolve("staged-perks-v1.tsv"); }
    public static Path resetPath(MinecraftServer server) { return PrestigeService.control(server).resolve("reset-perks-v1.tsv"); }
    public static Path healthPath(MinecraftServer server) { return PrestigeService.control(server).resolve("perk-health-v2.tsv"); }

    public static OnboardingPolicy activeOnboardingPolicy(MinecraftServer server) throws IOException {
        PrestigeContracts.Lineage lineage = PrestigeService.lineage(server);
        EnumSet<Perk> active = readActive(server, lineage);
        return onboardingPolicy(active);
    }

    static OnboardingPolicy onboardingPolicy(EnumSet<Perk> paid) {
        return new Build("lineage-policy", 0, paid, Landing.BIOME, "").onboardingPolicy();
    }

    public static Build draft(MinecraftServer server) throws IOException {
        PrestigeContracts.Lineage lineage = PrestigeService.lineage(server);
        Path path = draftPath(server);
        if (Files.isRegularFile(path)) return readBuild(path, DRAFT_MAGIC, lineage, lineage.generation(), false);
        return new Build(lineage.lineageId(), lineage.generation(), readActive(server, lineage), Landing.BIOME, "");
    }

    public static Build staged(MinecraftServer server) throws IOException {
        PrestigeContracts.Lineage lineage = PrestigeService.lineage(server);
        return readBuild(stagedPath(server), STAGED_MAGIC, lineage, lineage.generation(), false);
    }

    public static Build reset(MinecraftServer server, PrestigeContracts.Successor successor) throws IOException {
        Build build = readBuild(resetPath(server), RESET_MAGIC, new PrestigeContracts.Lineage(
                successor.lineageId(), successor.baseGeneration(), successor.baseGeneration()), successor.baseGeneration(), true);
        Map<String, String> fields = readFields(resetPath(server), RESET_MAGIC,
                List.of("lineage", "base_generation", "target_generation", "transaction", "perks", "landing", "fallback"));
        if (!fields.get("transaction").equals(successor.transactionId())) {
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

    public static void toggle(ServerPlayer player, String id) throws IOException { requireEditable(player); toggle(player.server, id); }

    public static void toggle(MinecraftServer server, String id) throws IOException {
        requireEditable(server);
        Build current = draft(server);
        Perk perk = Perk.parse(id);
        EnumSet<Perk> selected = current.perks().clone();
        if (selected.contains(perk)) {
            selected.remove(perk);
            try { validatePaidSet(selected, current.budget()); }
            catch (IllegalArgumentException error) { throw new IllegalStateException("refund dependent perks first: " + error.getMessage()); }
            if (perk == Perk.SETTLED_ARRIVAL && current.landing() == Landing.VILLAGE) throw new IllegalStateException("switch landing back to biome first");
            if (perk == Perk.FALLBACK_ATTUNEMENT && !current.fallbackBiome().isEmpty()) throw new IllegalStateException("clear the fallback biome first");
        } else {
            if (selected.size() >= current.budget()) throw new IllegalStateException("no prestige perk points remain");
            selected.add(perk);
            validatePaidSet(selected, current.budget());
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

    public static void setLanding(ServerPlayer player, String value) throws IOException { requireEditable(player); setLanding(player.server, value); }

    public static void setLanding(MinecraftServer server, String value) throws IOException {
        requireEditable(server);
        Build current = draft(server);
        Landing landing = Landing.parse(value);
        if (landing == Landing.VILLAGE && !current.has(Perk.SETTLED_ARRIVAL)) throw new IllegalStateException("Settled Arrival is not unlocked");
        writeBuild(draftPath(server), DRAFT_MAGIC, new Build(current.lineageId(), current.baseGeneration(),
                current.perks(), landing, landing == Landing.VILLAGE ? "" : current.fallbackBiome()), null);
    }

    public static void setFallback(ServerPlayer player, String value) throws IOException { requireEditable(player); setFallback(player.server, value); }

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
        Build build = draft(server); validateBuild(server, build, primaryBiome); writeBuild(stagedPath(server), STAGED_MAGIC, build, null);
    }
    public static void cancel(MinecraftServer server) throws IOException { Files.deleteIfExists(stagedPath(server)); }
    public static void commit(MinecraftServer server, String transaction, String primaryBiome) throws IOException {
        Build build = staged(server); validateBuild(server, build, primaryBiome); writeBuild(resetPath(server), RESET_MAGIC, build, transaction);
    }

    public static void writeHealth(MinecraftServer server, PrestigeContracts.Successor successor, Build build,
                                   String resolvedTarget, BlockPos landing, boolean safe) throws IOException {
        writeAtomic(healthPath(server), List.of(HEALTH_MAGIC,
                "lineage\t" + successor.lineageId(), "base_generation\t" + successor.baseGeneration(),
                "target_generation\t" + successor.targetGeneration(), "transaction\t" + successor.transactionId(),
                "attempt\t" + successor.attempt(), "landing\t" + build.landing().name().toLowerCase(Locale.ROOT),
                "resolved_target\t" + resolvedTarget, "landing_x\t" + landing.getX(),
                "landing_y\t" + landing.getY(), "landing_z\t" + landing.getZ(), "safe\t" + safe));
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
        List<String> allowed = allowedBiomes(server, build);
        if (!allowed.contains(primaryBiome)) throw new IllegalArgumentException("primary biome is not unlocked");
        if (!build.fallbackBiome().isEmpty()) {
            if (!build.has(Perk.FALLBACK_ATTUNEMENT) || build.landing() != Landing.BIOME) throw new IllegalArgumentException("fallback biome is not available");
            if (build.fallbackBiome().equals(primaryBiome) || !allowed.contains(build.fallbackBiome())) throw new IllegalArgumentException("fallback biome must be distinct and unlocked");
        }
    }

    static void validateShape(Build build) {
        if (build.baseGeneration() == Long.MAX_VALUE) throw new IllegalArgumentException("prestige generation is exhausted");
        validatePaidSet(build.perks(), build.budget());
        if (build.landing() == Landing.VILLAGE && !build.has(Perk.SETTLED_ARRIVAL)) throw new IllegalArgumentException("village landing requires Settled Arrival");
        if (!build.fallbackBiome().isEmpty() && (!build.has(Perk.FALLBACK_ATTUNEMENT) || build.landing() != Landing.BIOME)) throw new IllegalArgumentException("fallback biome is not available");
    }

    static void validatePaidSet(EnumSet<Perk> perks, int budget) {
        if (perks.size() > budget) throw new IllegalArgumentException("perk build exceeds its prestige-point budget");
        require(perks, Perk.FRONTIER_ATTUNEMENT, Perk.EXPANDED_ATTUNEMENT);
        require(perks, Perk.SETTLED_ARRIVAL, Perk.SAFE_ARRIVAL);
        require(perks, Perk.FOURTH_HORIZON, Perk.FALLBACK_ATTUNEMENT);
        for (Perk classPerk : CLASSES) if (perks.contains(classPerk) && !perks.containsAll(ORIGINAL)) throw new IllegalArgumentException(classPerk.id() + " requires all six world-shaping perks");
        for (Perk advanced : List.of(Perk.EMBARK_BUDGET_I, Perk.EMBARK_BUDGET_II, Perk.EMBARK_BUDGET_III, Perk.EMBARK_BUDGET_IV, Perk.SCHEMATICANNON_START)) {
            if (perks.contains(advanced) && !perks.containsAll(CLASSES)) throw new IllegalArgumentException(advanced.id() + " requires all six classes");
        }
        require(perks, Perk.EMBARK_BUDGET_II, Perk.EMBARK_BUDGET_I);
        require(perks, Perk.EMBARK_BUDGET_III, Perk.EMBARK_BUDGET_II);
        require(perks, Perk.EMBARK_BUDGET_IV, Perk.EMBARK_BUDGET_III);
        require(perks, Perk.SCHEMATICANNON_START, Perk.EMBARK_BUDGET_IV);
        if (perks.contains(Perk.SCHEMATICANNON_START) && perks.size() != MAX_POINTS) throw new IllegalArgumentException("schematicannon_start requires every other paid perk");
    }

    private static void require(EnumSet<Perk> perks, Perk child, Perk parent) {
        if (perks.contains(child) && !perks.contains(parent)) throw new IllegalArgumentException(child.id() + " requires " + parent.id());
    }

    private static void requireEditable(ServerPlayer player) {
        if (!player.hasPermissions(4)) throw new IllegalArgumentException("permission level 4 required");
        requireEditable(player.server);
    }
    private static void requireEditable(MinecraftServer server) {
        if (Files.exists(stagedPath(server)) || Files.exists(PrestigeService.control(server).resolve("reset-request-v4.tsv"))) throw new IllegalStateException("perk build is locked by a staged reset");
    }

    private static EnumSet<Perk> readActive(MinecraftServer server, PrestigeContracts.Lineage lineage) throws IOException {
        Path path = activePath(server);
        if (!Files.isRegularFile(path)) {
            if (lineage.generation() == 0) return EnumSet.noneOf(Perk.class);
            throw new IllegalStateException("active perk state is missing for a prestiged lineage");
        }
        Map<String, String> fields = readFields(path, ACTIVE_MAGIC, List.of("lineage", "generation", "perks"));
        if (!lineage.lineageId().equals(fields.get("lineage")) || lineage.generation() != parseLong(fields, "generation")) throw new IllegalStateException("active perk state does not match lineage generation");
        EnumSet<Perk> perks = parsePerks(fields.get("perks"));
        validatePaidSet(perks, (int) Math.min(MAX_POINTS, lineage.generation()));
        return perks;
    }

    private static Build readBuild(Path path, String magic, PrestigeContracts.Lineage lineage, long baseGeneration, boolean transaction) throws IOException {
        List<String> keys = transaction
                ? List.of("lineage", "base_generation", "target_generation", "transaction", "perks", "landing", "fallback")
                : List.of("lineage", "base_generation", "target_generation", "perks", "landing", "fallback");
        Map<String, String> fields = readFields(path, magic, keys);
        if (!lineage.lineageId().equals(fields.get("lineage")) || parseLong(fields, "base_generation") != baseGeneration
                || baseGeneration == Long.MAX_VALUE || parseLong(fields, "target_generation") != baseGeneration + 1) throw new IllegalStateException("perk build identity is stale");
        if (transaction) PrestigeContracts.validateId("transaction ID", fields.get("transaction"));
        Build build = new Build(lineage.lineageId(), baseGeneration, parsePerks(fields.get("perks")), Landing.parse(fields.get("landing")), decodeOptional(fields.get("fallback")));
        validateShape(build);
        return build;
    }

    private static EnumSet<Perk> parsePerks(String value) {
        EnumSet<Perk> perks = EnumSet.noneOf(Perk.class);
        if (!value.equals("-")) for (String id : value.split(",", -1)) if (id.isEmpty() || !perks.add(Perk.parse(id))) throw new IllegalArgumentException("invalid or duplicate prestige perk");
        return perks;
    }

    private static void writeBuild(Path path, String magic, Build build, String transaction) throws IOException {
        validateShape(build);
        List<String> lines = new ArrayList<>(List.of(magic, "lineage\t" + build.lineageId(), "base_generation\t" + build.baseGeneration(), "target_generation\t" + build.targetGeneration()));
        if (transaction != null) { PrestigeContracts.validateId("transaction ID", transaction); lines.add("transaction\t" + transaction); }
        lines.add("perks\t" + (build.ids().isEmpty() ? "-" : String.join(",", build.ids())));
        lines.add("landing\t" + build.landing().name().toLowerCase(Locale.ROOT));
        lines.add("fallback\t" + (build.fallbackBiome().isEmpty() ? "-" : build.fallbackBiome()));
        writeAtomic(path, lines);
    }

    private static Map<String, String> readFields(Path path, String magic, List<String> keys) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() != keys.size() + 1 || !lines.get(0).equals(magic)) throw new IllegalArgumentException("invalid prestige perk contract");
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 0; index < keys.size(); index++) {
            String[] row = lines.get(index + 1).split("\\t", -1);
            if (row.length != 2 || !row[0].equals(keys.get(index)) || row[1].isEmpty()) throw new IllegalArgumentException("invalid prestige perk fields");
            fields.put(row[0], row[1]);
        }
        return fields;
    }

    private static long parseLong(Map<String, String> fields, String key) {
        try { long value = Long.parseLong(fields.get(key)); if (value < 0) throw new NumberFormatException(); return value; }
        catch (Exception error) { throw new IllegalArgumentException("invalid prestige perk " + key); }
    }
    private static String decodeOptional(String value) { return value.equals("-") ? "" : value; }

    private static void writeAtomic(Path path, List<String> lines) throws IOException {
        Files.createDirectories(path.getParent());
        Path partial = path.resolveSibling(path.getFileName() + ".partial");
        Files.writeString(partial, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        try { Files.move(partial, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException error) { throw new IOException("perk state filesystem lacks atomic moves", error); }
    }
}
