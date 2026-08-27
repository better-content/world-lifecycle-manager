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
    public record View(String status, String worldName, PrestigeContracts.Lineage lineage, String selectedBiome,
                       String author, List<String> allowedBiomes, List<String> ownUploads,
                       List<SchematicLibrary.Entry> published, boolean operator, PrestigePerks.Build perkBuild) {}

    private PrestigeService() {}

    public static Path state(MinecraftServer server) {
        return server.getServerDirectory().toPath().toAbsolutePath().normalize().resolve(".world_lifecycle_manager");
    }

    public static Path control(MinecraftServer server) { return state(server).resolve("control"); }
    public static Path lineagePath(MinecraftServer server) { return state(server).resolve("lineage-v4.tsv"); }

    public static PrestigeContracts.Lineage lineage(MinecraftServer server) throws IOException {
        if (Files.exists(server.getServerDirectory().toPath().toAbsolutePath().normalize().resolve(".prestige"))) {
            throw new IllegalStateException("legacy .prestige state is unsupported; move or remove it before starting");
        }
        Path v4 = lineagePath(server);
        if (!Files.isRegularFile(v4)) {
            if (Files.exists(state(server).resolve("lineage-v1.tsv")) || Files.exists(state(server).resolve("lineage-v2.tsv"))
                    || Files.exists(state(server).resolve("lineage-v3.tsv"))) {
                throw new IllegalStateException("legacy Prestige lineage is unsupported; remove .world_lifecycle_manager and start cleanly");
            }
            throw new IllegalStateException("prestige v4 wrapper has not initialized lineage state");
        }
        return PrestigeContracts.readLineage(v4);
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
        String status = Files.exists(control(server).resolve("successor-request-v4.tsv")) ? "successor-starting"
                : Files.exists(control(server).resolve("reset-request-v4.tsv")) ? "committed"
                : Files.exists(control(server).resolve("staged-request-v4.tsv")) ? "staged" : "draft";
        List<String> allowedBiomes = allowedBiomes(server, perkBuild);
        String biome = allowedBiomes.get(0);
        String author = player.getGameProfile().getName();
        Path draftPath = control(server).resolve("draft-v4.tsv");
        if (Files.isRegularFile(draftPath)) {
            PrestigeContracts.Draft draft = PrestigeContracts.readDraft(draftPath);
            biome = draft.biome(); author = draft.author();
        } else {
            Path stagedPath = control(server).resolve("staged-request-v4.tsv");
            if (Files.isRegularFile(stagedPath)) {
                PrestigeContracts.Staged staged = PrestigeContracts.readStaged(stagedPath);
                biome = staged.biome(); author = staged.author();
            }
        }
        boolean operator = player.hasPermissions(4);
        List<String> uploads = List.of();
        List<SchematicLibrary.Entry> published = List.of();
        if (includeSchematics) {
            uploads = operator ? SchematicLibrary.allUploads(server)
                    : SchematicLibrary.ownUploads(server, player.getGameProfile().getName()).stream()
                    .map(name -> player.getGameProfile().getName() + "/" + name).toList();
            published = SchematicLibrary.list(server);
        }
        return new View(status, worldName(server), lineage, biome, author, allowedBiomes, uploads, published, operator, perkBuild);
    }

    public static void saveDraft(ServerPlayer player, String biome) throws IOException {
        requireOperator(player);
        saveDraft(player.server, biome, player.getGameProfile().getName());
    }

    public static void saveDraft(MinecraftServer server, String biome, String author) throws IOException {
        if (Files.exists(control(server).resolve("staged-request-v4.tsv"))
                || Files.exists(control(server).resolve("reset-request-v4.tsv"))) {
            throw new IllegalStateException("prestige selection is locked");
        }
        if (!allowedBiomes(server, PrestigePerks.draft(server)).contains(biome)) throw new IllegalArgumentException("biome is not unlocked");
        PrestigeContracts.validateAuthor(author);
        PrestigeContracts.Lineage lineage = lineage(server);
        PrestigeContracts.writeDraft(control(server).resolve("draft-v4.tsv"), new PrestigeContracts.Draft(
                lineage.lineageId(), lineage.generation(), biome, author, worldName(server)));
    }

    public static void stage(ServerPlayer player, BlockPos interfacePos) throws IOException {
        requireOperator(player);
        requireCondenser(player, interfacePos);
        stage(player.server);
    }

    public static void stage(MinecraftServer server) throws IOException {
        if (Files.exists(control(server).resolve("reset-request-v4.tsv"))) throw new IllegalStateException("reset already committed");
        PrestigeContracts.Draft draft = PrestigeContracts.readDraft(control(server).resolve("draft-v4.tsv"));
        PrestigeContracts.Lineage lineage = lineage(server);
        if (!draft.lineageId().equals(lineage.lineageId()) || draft.generation() != lineage.generation()
                || !draft.worldName().equals(worldName(server))) {
            throw new IllegalStateException("draft identity is stale");
        }
        if (!allowedBiomes(server).contains(draft.biome())) throw new IllegalStateException("draft biome is no longer allowlisted");
        PrestigePerks.stage(server, draft.biome());
        PrestigeContracts.writeStaged(control(server).resolve("staged-request-v4.tsv"), new PrestigeContracts.Staged(
                draft.lineageId(), draft.generation(), draft.biome(), draft.author(), draft.worldName()));
    }

    public static void cancel(ServerPlayer player) throws IOException {
        requireOperator(player);
        cancel(player.server);
    }

    public static void cancel(MinecraftServer server) throws IOException {
        if (Files.exists(control(server).resolve("reset-request-v4.tsv"))) throw new IllegalStateException("committed reset cannot be cancelled in-game");
        Files.deleteIfExists(control(server).resolve("staged-request-v4.tsv"));
        PrestigePerks.cancel(server);
    }

    public static String commit(ServerPlayer player, BlockPos interfacePos, String confirmation) throws IOException {
        requireOperator(player);
        requireCondenser(player, interfacePos);
        return commit(player.server, confirmation);
    }

    public static String commit(MinecraftServer server, String confirmation) throws IOException {
        if (!worldName(server).equals(confirmation)) throw new IllegalArgumentException("confirmation must exactly match the world name");
        Path resetPath = control(server).resolve("reset-request-v4.tsv");
        if (Files.exists(resetPath)) throw new IllegalStateException("reset already committed");
        PrestigeContracts.Staged staged = PrestigeContracts.readStaged(control(server).resolve("staged-request-v4.tsv"));
        PrestigeContracts.Lineage lineage = lineage(server);
        if (!staged.lineageId().equals(lineage.lineageId()) || staged.generation() != lineage.generation()
                || !staged.worldName().equals(worldName(server))) {
            throw new IllegalStateException("staged identity is stale");
        }
        String transaction = PrestigeContracts.newTransactionId();
        long oldSeed = server.overworld().getSeed();
        PrestigePerks.commit(server, transaction, staged.biome());
        PrestigeContracts.writeWorldBinding(worldBindingPath(server), new PrestigeContracts.WorldBinding(
                lineage.lineageId(), lineage.generation(), transaction, staged.worldName(), oldSeed, staged.biome()));
        PrestigeContracts.writeReset(resetPath, new PrestigeContracts.Reset(lineage.lineageId(), lineage.generation(),
                transaction, staged.worldName(), oldSeed, staged.biome()));
        // The scheduled clean halt performs Minecraft's normal player/world flush. Doing a second synchronous
        // save here can exceed the watchdog in a large pack; the supervisor cannot archive until that halt exits.
        PrestigeCoordinator.scheduleStop();
        Files.deleteIfExists(control(server).resolve("staged-request-v4.tsv"));
        Files.deleteIfExists(control(server).resolve("draft-v4.tsv"));
        return transaction;
    }

    public static void publish(ServerPlayer player, String author, String fileName) throws IOException {
        long generation = lineage(player.server).generation();
        SchematicLibrary.publish(player.server, player.getGameProfile().getName(), player.hasPermissions(4), author, fileName, generation);
        ThreadsBridge.emit(player, "schematic_capture", "substantial");
        ThreadsBridge.emit(player, "schematic_publish", "correlated");
    }

    public static void remove(ServerPlayer player, String id) throws IOException {
        requireOperator(player);
        SchematicLibrary.remove(player.server, id);
    }

    private static void requireCondenser(ServerPlayer player, BlockPos pos) {
        if (!(player.level().getBlockEntity(pos) instanceof WorldCondenserBlockEntity entity) || !entity.isAttuned()
                || !WorldCondenserFormation.isFormed(player.level(), pos, player.level().getBlockState(pos))) {
            throw new IllegalStateException("formed and attuned World Condenser required");
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
                .resolve("data/world_lifecycle_manager/reset-binding-v4.tsv");
    }
}
