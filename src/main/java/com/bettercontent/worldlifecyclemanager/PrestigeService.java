package com.bettercontent.worldlifecyclemanager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PrestigeService {
    public enum Recovery { NONE, DISCARD_DRAFT, DISCARD_STAGED }
    public record RecoveryDiagnostic(Recovery action, String message) {}
    record Selection(PrestigePerks.Build build, List<String> biomes, String author) {
        Selection { biomes = List.copyOf(biomes); }
    }
    public record View(String status, String worldName, PrestigeContracts.Lineage lineage, List<String> selectedBiomes,
                       String author, List<String> allowedBiomes,
                       List<SchematicLibrary.Entry> published, boolean operator, PrestigePerks.Build perkBuild) {}

    private PrestigeService() {}

    /** True only where the external supervisor can complete a Prestige transaction. */
    public static boolean supportsPrestigeReset(MinecraftServer server) {
        return server.isDedicatedServer();
    }

    static void requirePrestigeReset(MinecraftServer server) {
        if (!supportsPrestigeReset(server)) {
            throw new IllegalStateException("Prestige is disabled in single-player; use a supervised dedicated server");
        }
    }

    public static Path state(MinecraftServer server) {
        return server.getServerDirectory().toPath().toAbsolutePath().normalize().resolve(".world_lifecycle_manager");
    }

    public static Path control(MinecraftServer server) { return state(server).resolve("control"); }
    public static Path lineagePath(MinecraftServer server) throws IOException {
        return lineageContext(server).stateRoot().resolve("lineage-v5.tsv");
    }

    public static PrestigeContracts.Lineage lineage(MinecraftServer server) throws IOException {
        return lineageContext(server).lineage();
    }

    public static Path lineagePlayerState(MinecraftServer server) throws IOException {
        return lineageContext(server).stateRoot();
    }

    private static SingleplayerLineageStore.Context lineageContext(MinecraftServer server) throws IOException {
        if (!supportsPrestigeReset(server)) {
            return SingleplayerLineageStore.open(state(server), server.getWorldPath(LevelResource.ROOT));
        }
        if (Files.exists(server.getServerDirectory().toPath().toAbsolutePath().normalize().resolve(".prestige"))) {
            throw new IllegalStateException("legacy .prestige state is unsupported; move or remove it before starting");
        }
        Path v5 = state(server).resolve("lineage-v5.tsv");
        if (!Files.isRegularFile(v5)) {
            if (Files.exists(state(server).resolve("lineage-v1.tsv")) || Files.exists(state(server).resolve("lineage-v2.tsv"))
                    || Files.exists(state(server).resolve("lineage-v3.tsv")) || Files.exists(state(server).resolve("lineage-v4.tsv"))) {
                throw new IllegalStateException("Prestige v1-v4 state is unsupported; archive or move .world_lifecycle_manager and start a new lineage");
            }
            throw new IllegalStateException("prestige v5 wrapper has not initialized lineage state");
        }
        for (String legacy : List.of("draft-v4.tsv", "staged-request-v4.tsv", "reset-request-v4.tsv",
                "successor-request-v4.tsv", "health-result-v4.tsv", "shutdown-request-v4.tsv")) {
            if (Files.exists(control(server).resolve(legacy))) throw new IllegalStateException(
                    "Prestige v4 lifecycle state is unsupported; archive or move .world_lifecycle_manager and start a new lineage");
        }
        for (String legacy : List.of("perks-v1.tsv", "control/perk-draft-v1.tsv", "control/staged-perks-v1.tsv",
                "control/reset-perks-v1.tsv", "control/perk-health-v2.tsv")) {
            if (Files.exists(state(server).resolve(legacy))) throw new IllegalStateException(
                    "Prestige perk v1 state is unsupported; archive or move .world_lifecycle_manager and start a new lineage");
        }
        if (Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data/world_lifecycle_manager/reset-binding-v4.tsv"))) {
            throw new IllegalStateException("Prestige v4 world binding is unsupported; archive the old world and start a new lineage");
        }
        PrestigeContracts.Lineage lineage = PrestigeContracts.readLineage(v5);
        return new SingleplayerLineageStore.Context(state(server), lineage);
    }

    public static List<String> allowedBiomes(MinecraftServer server) throws IOException {
        requirePrestigeReset(server);
        return allowedBiomes(server, PrestigePerks.draft(server));
    }

    public static List<String> allowedBiomes(MinecraftServer server, PrestigePerks.Build build) throws IOException {
        requirePrestigeReset(server);
        List<String> values = PrestigePerks.allowedBiomes(server, build);
        if (values.isEmpty()) throw new IllegalStateException("prestige biome allowlist is empty");
        for (String value : values) {
            PrestigeContracts.validateBiome(value);
            ResourceLocation id = new ResourceLocation(value);
            if (!server.registryAccess().registryOrThrow(Registries.BIOME).containsKey(id)) {
                throw new IllegalStateException("allowlisted biome is not registered: " + value);
            }
        }
        return values;
    }

    public static View view(ServerPlayer player, boolean includeSchematics) throws IOException {
        MinecraftServer server = player.server;
        requirePrestigeReset(server);
        PrestigeContracts.Lineage lineage = lineage(server);
        String currentWorld = worldName(server);
        RecoveryDiagnostic recovery = diagnoseRecovery(server, lineage, currentWorld);
        if (recovery.action() != Recovery.NONE) throw new IllegalStateException(recovery.message());
        String status = lifecycleStatus(server);
        Selection selection = switch (status) {
            case "staged" -> stagedSelection(server, lineage, currentWorld);
            case "committed", "successor-starting" -> resetSelection(server, lineage, currentWorld,
                    player.getGameProfile().getName());
            default -> draftSelection(server, lineage, currentWorld, player.getGameProfile().getName());
        };
        List<String> allowedBiomes = allowedBiomes(server, selection.build());
        boolean operator = player.hasPermissions(4);
        List<SchematicLibrary.Entry> published = List.of();
        if (includeSchematics) published = SchematicLibrary.list(server);
        return new View(status, currentWorld, lineage, selection.biomes(), selection.author(), allowedBiomes,
                published, operator, selection.build());
    }

    private static String lifecycleStatus(MinecraftServer server) {
        return Files.exists(control(server).resolve("successor-request-v5.tsv")) ? "successor-starting"
                : Files.exists(control(server).resolve("reset-request-v5.tsv")) ? "committed"
                : Files.exists(control(server).resolve("staged-request-v5.tsv")) ? "staged" : "draft";
    }

    private static Selection draftSelection(MinecraftServer server, PrestigeContracts.Lineage lineage,
                                            String currentWorld, String defaultAuthor) throws IOException {
        PrestigePerks.Build build = PrestigePerks.draft(server);
        Path path = control(server).resolve("draft-v5.tsv");
        if (!Files.isRegularFile(path)) {
            if (!build.biomes().isEmpty()) throw new IllegalStateException("prestige draft contracts are incomplete");
            return new Selection(build, build.biomes(), defaultAuthor);
        }
        PrestigeContracts.Draft draft = PrestigeContracts.readDraft(path);
        validateDraftSelection(lineage, currentWorld, draft, build);
        return new Selection(build, draft.biomes(), draft.author());
    }

    private static Selection stagedSelection(MinecraftServer server, PrestigeContracts.Lineage lineage,
                                             String currentWorld) throws IOException {
        Path path = control(server).resolve("staged-request-v5.tsv");
        if (!Files.isRegularFile(path) || !Files.isRegularFile(PrestigePerks.stagedPath(server))) {
            throw new IllegalStateException("staged prestige contracts are incomplete");
        }
        PrestigeContracts.Staged staged = PrestigeContracts.readStaged(path);
        PrestigePerks.Build build = PrestigePerks.staged(server);
        validateStagedSelection(lineage, currentWorld, staged, build);
        return new Selection(build, staged.biomes(), staged.author());
    }

    private static Selection resetSelection(MinecraftServer server, PrestigeContracts.Lineage lineage,
                                            String currentWorld, String defaultAuthor) throws IOException {
        Path path = control(server).resolve("reset-request-v5.tsv");
        if (!Files.isRegularFile(path) || !Files.isRegularFile(PrestigePerks.resetPath(server))) {
            throw new IllegalStateException("committed prestige contracts are incomplete");
        }
        PrestigeContracts.Reset reset = PrestigeContracts.readReset(path);
        PrestigePerks.Build build = PrestigePerks.reset(server, reset);
        if (!reset.lineageId().equals(lineage.lineageId())
                || (reset.baseGeneration() != lineage.generation()
                    && (reset.baseGeneration() == Long.MAX_VALUE || reset.baseGeneration() + 1 != lineage.generation()))
                || !reset.worldName().equals(currentWorld)) {
            throw new IllegalStateException("committed prestige identity is stale");
        }
        return new Selection(build, reset.biomes(), defaultAuthor);
    }

    static void validateDraftSelection(PrestigeContracts.Lineage lineage, String currentWorld,
                                       PrestigeContracts.Draft draft, PrestigePerks.Build build) {
        if (!draft.lineageId().equals(lineage.lineageId()) || draft.generation() != lineage.generation()
                || !draft.worldName().equals(currentWorld) || !build.lineageId().equals(lineage.lineageId())
                || build.baseGeneration() != lineage.generation()) {
            throw new IllegalStateException("draft identity is stale");
        }
        if (!draft.biomes().equals(build.biomes())) throw new IllegalStateException("draft biome snapshots do not match");
    }

    static void validateStagedSelection(PrestigeContracts.Lineage lineage, String currentWorld,
                                        PrestigeContracts.Staged staged, PrestigePerks.Build build) {
        if (!staged.lineageId().equals(lineage.lineageId()) || staged.generation() != lineage.generation()
                || !staged.worldName().equals(currentWorld) || !build.lineageId().equals(lineage.lineageId())
                || build.baseGeneration() != lineage.generation()) {
            throw new IllegalStateException("staged identity is stale");
        }
        if (!staged.biomes().equals(build.biomes())) throw new IllegalStateException("staged biome snapshots do not match");
    }

    public static RecoveryDiagnostic diagnoseRecovery(MinecraftServer server) {
        if (!supportsPrestigeReset(server)) return new RecoveryDiagnostic(Recovery.NONE, "");
        try {
            return diagnoseRecovery(server, lineage(server), worldName(server));
        } catch (Exception error) {
            return new RecoveryDiagnostic(Recovery.NONE, "");
        }
    }

    private static RecoveryDiagnostic diagnoseRecovery(MinecraftServer server, PrestigeContracts.Lineage lineage,
                                                       String currentWorld) {
        Path control = control(server);
        if (Files.exists(control.resolve("reset-request-v5.tsv"))
                || Files.exists(control.resolve("successor-request-v5.tsv"))) {
            return new RecoveryDiagnostic(Recovery.NONE, "");
        }
        boolean stagedArtifacts = Files.exists(control.resolve("staged-request-v5.tsv"))
                || Files.exists(PrestigePerks.stagedPath(server));
        if (stagedArtifacts) {
            try {
                stagedSelection(server, lineage, currentWorld);
                return new RecoveryDiagnostic(Recovery.NONE, "");
            } catch (Exception error) {
                return recoverable(Recovery.DISCARD_STAGED, "staged prestige state", error);
            }
        }
        boolean draftArtifacts = Files.exists(control.resolve("draft-v5.tsv"))
                || Files.exists(PrestigePerks.draftPath(server));
        if (draftArtifacts) {
            try {
                draftSelection(server, lineage, currentWorld, "Operator");
            } catch (Exception error) {
                return recoverable(Recovery.DISCARD_DRAFT, "prestige draft state", error);
            }
        }
        return new RecoveryDiagnostic(Recovery.NONE, "");
    }

    private static RecoveryDiagnostic recoverable(Recovery action, String label, Exception error) {
        String detail = error.getMessage();
        if (detail == null || detail.isBlank()) detail = error.getClass().getSimpleName();
        return new RecoveryDiagnostic(action, label + " is invalid: " + detail
                + "; a nearby operator may discard this uncommitted state");
    }

    public static void recoverInvalid(ServerPlayer player, BlockPos interfacePos) throws IOException {
        requirePrestigeReset(player.server);
        requireOperator(player);
        requireCondenser(player, interfacePos);
        MinecraftServer server = player.server;
        RecoveryDiagnostic recovery = diagnoseRecovery(server);
        if (recovery.action() == Recovery.NONE) throw new IllegalStateException("no invalid uncommitted prestige state exists");
        if (Files.exists(control(server).resolve("reset-request-v5.tsv"))
                || Files.exists(control(server).resolve("successor-request-v5.tsv"))) {
            throw new IllegalStateException("committed prestige state cannot be discarded in-game");
        }
        if (recovery.action() == Recovery.DISCARD_STAGED) {
            Files.deleteIfExists(control(server).resolve("staged-request-v5.tsv"));
            Files.deleteIfExists(PrestigePerks.stagedPath(server));
            recovery = diagnoseRecovery(server);
        }
        if (recovery.action() == Recovery.DISCARD_DRAFT) {
            Files.deleteIfExists(control(server).resolve("draft-v5.tsv"));
            Files.deleteIfExists(PrestigePerks.draftPath(server));
        }
    }

    public static void saveDraft(ServerPlayer player, List<String> biomes) throws IOException {
        requireOperator(player);
        saveDraft(player.server, biomes, player.getGameProfile().getName());
    }

    public static void saveDraft(MinecraftServer server, List<String> biomes, String author) throws IOException {
        requirePrestigeReset(server);
        if (Files.exists(control(server).resolve("staged-request-v5.tsv"))
                || Files.exists(control(server).resolve("reset-request-v5.tsv"))) {
            throw new IllegalStateException("prestige selection is locked");
        }
        PrestigeContracts.validateBiomes(biomes);
        if (!allowedBiomes(server, PrestigePerks.draft(server)).containsAll(biomes)) throw new IllegalArgumentException("biome preference is not allowlisted");
        PrestigeContracts.validateAuthor(author);
        PrestigeContracts.Lineage lineage = lineage(server);
        PrestigePerks.setBiomes(server, biomes);
        PrestigeContracts.writeDraft(control(server).resolve("draft-v5.tsv"), new PrestigeContracts.Draft(
                lineage.lineageId(), lineage.generation(), biomes, author, worldName(server)));
    }

    public static void setBiomeSlot(ServerPlayer player, int slot, String value) throws IOException {
        requirePrestigeReset(player.server);
        requireOperator(player);
        if (slot < 0 || slot > 2) throw new IllegalArgumentException("biome preference slot is outside 1..3");
        List<String> current = new java.util.ArrayList<>(PrestigePerks.draft(player.server).biomes());
        if (value.equals("clear")) {
            if (slot == 0) throw new IllegalArgumentException("primary biome cannot be cleared");
            while (current.size() > slot) current.remove(current.size() - 1);
        } else {
            PrestigeContracts.validateBiome(value);
            if (slot > current.size()) throw new IllegalArgumentException("biome preferences must be contiguous");
            if (current.contains(value) && (slot >= current.size() || !current.get(slot).equals(value))) {
                throw new IllegalArgumentException("biome preferences must be unique");
            }
            if (slot == current.size()) current.add(value); else current.set(slot, value);
        }
        saveDraft(player.server, current, player.getGameProfile().getName());
    }

    public static void stage(ServerPlayer player, BlockPos interfacePos) throws IOException {
        requireOperator(player);
        requireCondenser(player, interfacePos);
        stage(player.server);
    }

    public static void stage(MinecraftServer server) throws IOException {
        requirePrestigeReset(server);
        if (Files.exists(control(server).resolve("reset-request-v5.tsv"))) throw new IllegalStateException("reset already committed");
        Path draftPath = control(server).resolve("draft-v5.tsv");
        if (!Files.isRegularFile(draftPath)) {
            throw new IllegalStateException("no prestige draft exists; select biomes first with /world_lifecycle_manager select <biomes>");
        }
        PrestigeContracts.Draft draft = PrestigeContracts.readDraft(draftPath);
        PrestigeContracts.Lineage lineage = lineage(server);
        if (!draft.lineageId().equals(lineage.lineageId()) || draft.generation() != lineage.generation()
                || !draft.worldName().equals(worldName(server))) {
            throw new IllegalStateException("draft identity is stale");
        }
        if (!allowedBiomes(server).containsAll(draft.biomes())) throw new IllegalStateException("draft biome is no longer allowlisted");
        PrestigePerks.stage(server, draft.biomes());
        PrestigeContracts.writeStaged(control(server).resolve("staged-request-v5.tsv"), new PrestigeContracts.Staged(
                draft.lineageId(), draft.generation(), draft.biomes(), draft.author(), draft.worldName()));
    }

    public static void cancel(ServerPlayer player) throws IOException {
        requireOperator(player);
        cancel(player.server);
    }

    public static void cancel(MinecraftServer server) throws IOException {
        requirePrestigeReset(server);
        if (Files.exists(control(server).resolve("reset-request-v5.tsv"))) throw new IllegalStateException("committed reset cannot be cancelled in-game");
        Files.deleteIfExists(control(server).resolve("staged-request-v5.tsv"));
        PrestigePerks.cancel(server);
    }

    public static String commit(ServerPlayer player, BlockPos interfacePos) throws IOException {
        requireOperator(player);
        requireCondenser(player, interfacePos);
        return commit(player.server);
    }

    public static String commit(MinecraftServer server) throws IOException {
        requirePrestigeReset(server);
        String currentWorld = worldName(server);
        Path resetPath = control(server).resolve("reset-request-v5.tsv");
        if (Files.exists(resetPath)) throw new IllegalStateException("reset already committed");
        Path stagedPath = control(server).resolve("staged-request-v5.tsv");
        if (!Files.isRegularFile(stagedPath)) {
            throw new IllegalStateException("no staged prestige request exists; run /world_lifecycle_manager stage first");
        }
        PrestigeContracts.Staged staged = PrestigeContracts.readStaged(stagedPath);
        PrestigeContracts.Lineage lineage = lineage(server);
        if (!staged.lineageId().equals(lineage.lineageId()) || staged.generation() != lineage.generation()
                || !staged.worldName().equals(currentWorld)) {
            throw new IllegalStateException("staged identity is stale");
        }
        String transaction = PrestigeContracts.newTransactionId();
        long oldSeed = server.overworld().getSeed();
        PrestigePerks.commit(server, transaction, staged.biomes());
        PrestigeContracts.writeWorldBinding(worldBindingPath(server), new PrestigeContracts.WorldBinding(
                lineage.lineageId(), lineage.generation(), transaction, staged.worldName(), oldSeed, staged.biomes()));
        PrestigeContracts.writeReset(resetPath, new PrestigeContracts.Reset(lineage.lineageId(), lineage.generation(),
                transaction, staged.worldName(), oldSeed, staged.biomes()));
        // The scheduled clean halt performs Minecraft's normal player/world flush. Doing a second synchronous
        // save here can exceed the watchdog in a large pack; the supervisor cannot archive until that halt exits.
        PrestigeCoordinator.scheduleStop();
        Files.deleteIfExists(control(server).resolve("staged-request-v5.tsv"));
        Files.deleteIfExists(control(server).resolve("draft-v5.tsv"));
        return transaction;
    }

    public static SchematicLibrary.Entry publish(ServerPlayer player, String fileName, byte[] compressedNbt) throws IOException {
        requirePrestigeReset(player.server);
        requireOperator(player);
        String author = player.getGameProfile().getName();
        long generation = lineage(player.server).generation();
        SchematicLibrary.Entry entry = SchematicLibrary.publish(player.server, author, fileName, compressedNbt, generation);
        String threadEpisode=java.util.UUID.nameUUIDFromBytes((author+"\u0000"+fileName).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        ThreadsBridge.emit(player, "schematic_capture", "substantial",threadEpisode);
        ThreadsBridge.emit(player, "schematic_publish", "correlated",threadEpisode);
        return entry;
    }

    public static void remove(ServerPlayer player, String id) throws IOException {
        requirePrestigeReset(player.server);
        requireOperator(player);
        SchematicLibrary.remove(player.server, id);
    }

    private static void requireCondenser(ServerPlayer player, BlockPos pos) {
        if (!(player.level().getBlockEntity(pos) instanceof WorldCondenserBlockEntity)) {
            throw new IllegalStateException("nearby World Condenser Interface required");
        }
    }

    private static void requireOperator(ServerPlayer player) {
        if (!player.hasPermissions(4)) throw new IllegalArgumentException("permission level 4 required");
    }

    public static String worldName(MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String name = root.getFileName().toString();
        PrestigeContracts.validateWorldName(name);
        return name;
    }

    public static Path worldBindingPath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
                .resolve("data/world_lifecycle_manager/reset-binding-v5.tsv");
    }
}
