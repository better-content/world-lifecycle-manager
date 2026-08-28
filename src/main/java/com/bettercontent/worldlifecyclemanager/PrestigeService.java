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
    public record View(String status, String worldName, PrestigeContracts.Lineage lineage, List<String> selectedBiomes,
                       String author, List<String> allowedBiomes,
                       List<SchematicLibrary.Entry> published, boolean operator, PrestigePerks.Build perkBuild) {}

    private PrestigeService() {}

    public static Path state(MinecraftServer server) {
        return server.getServerDirectory().toPath().toAbsolutePath().normalize().resolve(".world_lifecycle_manager");
    }

    public static Path control(MinecraftServer server) { return state(server).resolve("control"); }
    public static Path lineagePath(MinecraftServer server) { return state(server).resolve("lineage-v5.tsv"); }

    public static PrestigeContracts.Lineage lineage(MinecraftServer server) throws IOException {
        if (Files.exists(server.getServerDirectory().toPath().toAbsolutePath().normalize().resolve(".prestige"))) {
            throw new IllegalStateException("legacy .prestige state is unsupported; move or remove it before starting");
        }
        Path v5 = lineagePath(server);
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
        return PrestigeContracts.readLineage(v5);
    }

    public static List<String> allowedBiomes(MinecraftServer server) throws IOException {
        return allowedBiomes(server, PrestigePerks.draft(server));
    }

    public static List<String> allowedBiomes(MinecraftServer server, PrestigePerks.Build build) throws IOException {
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
        PrestigeContracts.Lineage lineage = lineage(server);
        PrestigePerks.Build perkBuild = PrestigePerks.draft(server);
        String status = Files.exists(control(server).resolve("successor-request-v5.tsv")) ? "successor-starting"
                : Files.exists(control(server).resolve("reset-request-v5.tsv")) ? "committed"
                : Files.exists(control(server).resolve("staged-request-v5.tsv")) ? "staged" : "draft";
        List<String> allowedBiomes = allowedBiomes(server, perkBuild);
        List<String> biomes = perkBuild.biomes();
        String author = player.getGameProfile().getName();
        Path draftPath = control(server).resolve("draft-v5.tsv");
        if (Files.isRegularFile(draftPath)) {
            PrestigeContracts.Draft draft = PrestigeContracts.readDraft(draftPath);
            biomes = draft.biomes(); author = draft.author();
        } else {
            Path stagedPath = control(server).resolve("staged-request-v5.tsv");
            if (Files.isRegularFile(stagedPath)) {
                PrestigeContracts.Staged staged = PrestigeContracts.readStaged(stagedPath);
                biomes = staged.biomes(); author = staged.author();
            }
        }
        boolean operator = player.hasPermissions(4);
        List<SchematicLibrary.Entry> published = List.of();
        if (includeSchematics) published = SchematicLibrary.list(server);
        return new View(status, worldName(server), lineage, biomes, author, allowedBiomes, published, operator, perkBuild);
    }

    public static void saveDraft(ServerPlayer player, List<String> biomes) throws IOException {
        requireOperator(player);
        saveDraft(player.server, biomes, player.getGameProfile().getName());
    }

    public static void saveDraft(MinecraftServer server, List<String> biomes, String author) throws IOException {
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
