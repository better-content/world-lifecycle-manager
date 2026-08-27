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
import java.util.Map;
import java.util.Set;

/** Server-authoritative paid prestige graph and its closed transaction contracts. */
public final class PrestigePerks {
    public static final String ACTIVE_MAGIC = "BC_PRESTIGE_PERKS_V2";
    public static final String DRAFT_MAGIC = "BC_PRESTIGE_PERK_DRAFT_V2";
    public static final String STAGED_MAGIC = "BC_PRESTIGE_STAGED_PERKS_V2";
    public static final String RESET_MAGIC = "BC_PRESTIGE_RESET_PERKS_V2";
    public static final String HEALTH_MAGIC = "BC_PRESTIGE_PERK_HEALTH_V3";
    public static final int MAX_POINTS = 12;

    public enum Perk {
        BIOME_SELECTION("biome_selection"),
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

    public enum OnboardingMode { SPAWN_ONLY, CLASS, EMBARK }

    /** Stable API consumed by Class Selector. */
    public record OnboardingPolicy(OnboardingMode mode, Set<String> unlockedClassIds,
                                   int embarkBudget, boolean starterSchematicannon) {
        public OnboardingPolicy { unlockedClassIds = Set.copyOf(unlockedClassIds); }
    }

    public record Build(String lineageId, long baseGeneration, EnumSet<Perk> perks, List<String> biomes) {
        public Build {
            perks = perks.isEmpty() ? EnumSet.noneOf(Perk.class) : EnumSet.copyOf(perks);
            biomes = biomes == null ? List.of() : List.copyOf(biomes);
        }
        public long targetGeneration() { return Math.addExact(baseGeneration, 1); }
        public int budget() { return (int) Math.min(MAX_POINTS, targetGeneration()); }
        public boolean has(Perk perk) { return perks.contains(perk); }
        public List<String> ids() { return perks.stream().map(Perk::id).toList(); }
        public boolean classSelectorUnlocked() { return perks.stream().anyMatch(CLASSES::contains); }
        public boolean embarkUnlocked() { return perks.containsAll(CLASSES); }
        public int successorAttempts() { return 8; }
        public OnboardingPolicy onboardingPolicy() {
            if (embarkUnlocked()) {
                int quota = has(Perk.EMBARK_BUDGET_IV) ? 18 : has(Perk.EMBARK_BUDGET_III) ? 15
                        : has(Perk.EMBARK_BUDGET_II) ? 12 : has(Perk.EMBARK_BUDGET_I) ? 9 : 6;
                return new OnboardingPolicy(OnboardingMode.EMBARK, Set.copyOf(CLASS_IDS.values()), quota,
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

    public static Path activePath(MinecraftServer server) { return PrestigeService.state(server).resolve("perks-v2.tsv"); }
    public static Path draftPath(MinecraftServer server) { return PrestigeService.control(server).resolve("perk-draft-v2.tsv"); }
    public static Path stagedPath(MinecraftServer server) { return PrestigeService.control(server).resolve("staged-perks-v2.tsv"); }
    public static Path resetPath(MinecraftServer server) { return PrestigeService.control(server).resolve("reset-perks-v2.tsv"); }
    public static Path healthPath(MinecraftServer server) { return PrestigeService.control(server).resolve("perk-health-v3.tsv"); }

    public static OnboardingPolicy activeOnboardingPolicy(MinecraftServer server) throws IOException {
        PrestigeContracts.Lineage lineage = PrestigeService.lineage(server);
        EnumSet<Perk> active = readActive(server, lineage);
        return onboardingPolicy(active);
    }

    static OnboardingPolicy onboardingPolicy(EnumSet<Perk> paid) {
        return new Build("lineage-policy", 0, paid, List.of()).onboardingPolicy();
    }

    public static Build draft(MinecraftServer server) throws IOException {
        PrestigeContracts.Lineage lineage = PrestigeService.lineage(server);
        Path path = draftPath(server);
        if (Files.isRegularFile(path)) return readBuild(path, DRAFT_MAGIC, lineage, lineage.generation(), false);
        return new Build(lineage.lineageId(), lineage.generation(), readActive(server, lineage), List.of());
    }

    public static Build staged(MinecraftServer server) throws IOException {
        PrestigeContracts.Lineage lineage = PrestigeService.lineage(server);
        return readBuild(stagedPath(server), STAGED_MAGIC, lineage, lineage.generation(), false);
    }

    public static Build reset(MinecraftServer server, PrestigeContracts.Successor successor) throws IOException {
        Build build = readBuild(resetPath(server), RESET_MAGIC, new PrestigeContracts.Lineage(
                successor.lineageId(), successor.baseGeneration(), successor.baseGeneration()), successor.baseGeneration(), true);
        Map<String, String> fields = readFields(resetPath(server), RESET_MAGIC,
                List.of("lineage", "base_generation", "target_generation", "transaction", "perks", "biome_1", "biome_2", "biome_3"));
        if (!fields.get("transaction").equals(successor.transactionId())) {
            throw new IllegalStateException("perk snapshot transaction does not match successor");
        }
        if (!build.biomes().equals(successor.biomes())) throw new IllegalStateException("perk snapshot biome preferences do not match successor");
        validateBuild(server, build);
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
            if (perk == Perk.BIOME_SELECTION && !current.biomes().isEmpty()) throw new IllegalStateException("clear biome preferences first");
        } else {
            if (selected.size() >= current.budget()) throw new IllegalStateException("no prestige perk points remain");
            selected.add(perk);
            validatePaidSet(selected, current.budget());
        }
        writeBuild(draftPath(server), DRAFT_MAGIC, new Build(current.lineageId(), current.baseGeneration(),
                selected, current.biomes()), null);
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

    public static void setBiomes(MinecraftServer server, List<String> biomes) throws IOException {
        requireEditable(server);
        Build current = draft(server);
        if (!biomes.isEmpty() && !current.has(Perk.BIOME_SELECTION)) {
            throw new IllegalStateException("Biome Selection is not allocated; run /world_lifecycle_manager perks allocate biome_selection first");
        }
        if (!biomes.isEmpty()) PrestigeContracts.validateBiomes(biomes);
        writeBuild(draftPath(server), DRAFT_MAGIC, new Build(current.lineageId(), current.baseGeneration(),
                current.perks(), biomes), null);
    }

    public static void stage(MinecraftServer server, List<String> biomes) throws IOException {
        Build build = draft(server);
        if (!build.biomes().equals(biomes)) build = new Build(build.lineageId(), build.baseGeneration(), build.perks(), biomes);
        validateBuild(server, build); writeBuild(stagedPath(server), STAGED_MAGIC, build, null);
    }
    public static void cancel(MinecraftServer server) throws IOException { Files.deleteIfExists(stagedPath(server)); }
    public static void commit(MinecraftServer server, String transaction, List<String> biomes) throws IOException {
        Build build = staged(server);
        if (!build.biomes().equals(biomes)) throw new IllegalStateException("staged biome preferences changed");
        validateBuild(server, build); writeBuild(resetPath(server), RESET_MAGIC, build, transaction);
    }

    public static void writeHealth(MinecraftServer server, PrestigeContracts.Successor successor, Build build,
                                   String resolvedBiome, BlockPos spawn) throws IOException {
        if (!build.biomes().equals(successor.biomes())) throw new IllegalArgumentException("perk health preferences do not match successor");
        if (!resolvedBiome.equals("-") && !successor.biomes().contains(resolvedBiome)) {
            throw new IllegalArgumentException("perk health resolved biome is not requested");
        }
        writeAtomic(healthPath(server), List.of(HEALTH_MAGIC,
                "lineage\t" + successor.lineageId(), "base_generation\t" + successor.baseGeneration(),
                "target_generation\t" + successor.targetGeneration(), "transaction\t" + successor.transactionId(),
                "attempt\t" + successor.attempt(), "resolved_biome\t" + resolvedBiome,
                "spawn_x\t" + spawn.getX(), "spawn_y\t" + spawn.getY(), "spawn_z\t" + spawn.getZ()));
    }

    public static List<String> allowedBiomes(MinecraftServer server, Build build) throws IOException {
        return new ArrayList<>(readBiomeFile(server, "world_lifecycle_manager-biomes.txt")).stream().distinct().sorted().toList();
    }

    private static List<String> readBiomeFile(MinecraftServer server, String name) throws IOException {
        Path path = server.getServerDirectory().toPath().toAbsolutePath().normalize().resolve("config").resolve(name);
        if (!Files.isRegularFile(path)) throw new IllegalStateException("missing config/" + name);
        List<String> values = parseBiomeLines(name, Files.readAllLines(path, StandardCharsets.UTF_8));
        return values;
    }

    static List<String> parseBiomeLines(String name, List<String> lines) {
        List<String> values = lines.stream().map(String::strip)
                .filter(value -> !value.isEmpty() && !value.startsWith("#")).toList();
        PrestigeLimits.requireSize(name, values, PrestigeLimits.MAX_BIOMES);
        if (values.isEmpty()) throw new IllegalArgumentException(name + " is empty");
        if (new java.util.LinkedHashSet<>(values).size() != values.size()) throw new IllegalArgumentException(name + " contains duplicate biome rows");
        for (String value : values) PrestigeContracts.validateBiome(value);
        return List.copyOf(values);
    }

    private static void validateBuild(MinecraftServer server, Build build) throws IOException {
        validateShape(build);
        List<String> allowed = allowedBiomes(server, build);
        if (!build.has(Perk.BIOME_SELECTION)) throw new IllegalArgumentException("Biome Selection must be allocated before staging");
        PrestigeContracts.validateBiomes(build.biomes());
        if (!allowed.containsAll(build.biomes())) throw new IllegalArgumentException("biome preference is not allowlisted");
    }

    static void validateShape(Build build) {
        if (build.baseGeneration() == Long.MAX_VALUE) throw new IllegalArgumentException("prestige generation is exhausted");
        validatePaidSet(build.perks(), build.budget());
        if (!build.biomes().isEmpty()) {
            if (!build.has(Perk.BIOME_SELECTION)) throw new IllegalArgumentException("biome preferences require Biome Selection");
            PrestigeContracts.validateBiomes(build.biomes());
        }
    }

    static void validatePaidSet(EnumSet<Perk> perks, int budget) {
        if (perks.size() > budget) throw new IllegalArgumentException("perk build exceeds its prestige-point budget");
        for (Perk perk : perks) if (perk != Perk.BIOME_SELECTION && !perks.contains(Perk.BIOME_SELECTION)) throw new IllegalArgumentException(perk.id() + " requires biome_selection");
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
        if (Files.exists(stagedPath(server)) || Files.exists(PrestigeService.control(server).resolve("reset-request-v5.tsv"))) throw new IllegalStateException("perk build is locked by a staged reset");
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
                ? List.of("lineage", "base_generation", "target_generation", "transaction", "perks", "biome_1", "biome_2", "biome_3")
                : List.of("lineage", "base_generation", "target_generation", "perks", "biome_1", "biome_2", "biome_3");
        Map<String, String> fields = readFields(path, magic, keys);
        if (!lineage.lineageId().equals(fields.get("lineage")) || parseLong(fields, "base_generation") != baseGeneration
                || baseGeneration == Long.MAX_VALUE || parseLong(fields, "target_generation") != baseGeneration + 1) throw new IllegalStateException("perk build identity is stale");
        if (transaction) PrestigeContracts.validateId("transaction ID", fields.get("transaction"));
        Build build = new Build(lineage.lineageId(), baseGeneration, parsePerks(fields.get("perks")), decodeBiomes(fields));
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
        lines.add("biome_1\t" + encodeBiome(build.biomes(), 0));
        lines.add("biome_2\t" + encodeBiome(build.biomes(), 1));
        lines.add("biome_3\t" + encodeBiome(build.biomes(), 2));
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
    private static List<String> decodeBiomes(Map<String, String> fields) {
        List<String> result = new ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            String value = fields.get("biome_" + index);
            if (value.equals("-")) {
                for (int later = index + 1; later <= 3; later++) if (!fields.get("biome_" + later).equals("-")) throw new IllegalArgumentException("biome preferences must be contiguous");
                break;
            }
            result.add(value);
        }
        if (!result.isEmpty()) PrestigeContracts.validateBiomes(result);
        return List.copyOf(result);
    }
    private static String encodeBiome(List<String> biomes, int index) { return index < biomes.size() ? biomes.get(index) : "-"; }

    private static void writeAtomic(Path path, List<String> lines) throws IOException {
        Files.createDirectories(path.getParent());
        Path partial = path.resolveSibling(path.getFileName() + ".partial");
        Files.writeString(partial, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        try { Files.move(partial, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException error) { throw new IOException("perk state filesystem lacks atomic moves", error); }
    }
}
