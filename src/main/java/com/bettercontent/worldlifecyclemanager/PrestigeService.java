package com.bettercontent.worldlifecyclemanager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

public final class PrestigeService {
    public record View(String status, String worldName, PrestigeContracts.Lineage lineage, String selectedBiome,
                       String author, List<String> allowedBiomes, List<String> ownUploads,
                       List<SchematicLibrary.Entry> published, boolean operator) {}

    private PrestigeService() {}

    public static Path state(MinecraftServer server) {
        return server.getServerDirectory().toPath().toAbsolutePath().normalize().resolve(".world_lifecycle_manager");
    }

    public static Path control(MinecraftServer server) { return state(server).resolve("control"); }
    public static Path lineagePath(MinecraftServer server) { return state(server).resolve("lineage-v4.tsv"); }

    public static PrestigeContracts.Lineage lineage(MinecraftServer server) throws IOException {
        Path v4 = lineagePath(server);
        if (!Files.isRegularFile(v4)) {
            if (Files.exists(state(server).resolve("lineage-v1.tsv")) || Files.exists(state(server).resolve("lineage-v2.tsv"))
                    || Files.exists(state(server).resolve("lineage-v3.tsv"))) {
                throw new IllegalStateException("legacy Prestige lineage is unsupported; remove .prestige and start the v4 supervisor");
            }
            throw new IllegalStateException("prestige v4 wrapper has not initialized lineage state");
        }
        return PrestigeContracts.readLineage(v4);
    }

    public static List<String> allowedBiomes(MinecraftServer server) throws IOException {
        Path path = server.getServerDirectory().toPath().toAbsolutePath().normalize()
                .resolve("config/world_lifecycle_manager-biomes.txt");
        if (!Files.isRegularFile(path)) throw new IllegalStateException("missing config/world_lifecycle_manager-biomes.txt");
        List<String> configured = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int scannedLines = 0;
            while ((line = reader.readLine()) != null) {
                if (++scannedLines > PrestigeLimits.MAX_BIOME_FILE_LINES) throw new IllegalStateException(
                        "prestige biome allowlist exceeds " + PrestigeLimits.MAX_BIOME_FILE_LINES + " lines");
                String value = line.strip();
                if (value.isEmpty() || value.startsWith("#")) continue;
                if (configured.size() >= PrestigeLimits.MAX_BIOMES) throw new IllegalStateException(
                        "prestige biome allowlist exceeds " + PrestigeLimits.MAX_BIOMES + " entries");
                configured.add(value);
            }
        }
        List<String> values = configured.stream().distinct().sorted().toList();
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
        String status = Files.exists(control(server).resolve("successor-request-v4.tsv")) ? "successor-starting"
                : Files.exists(control(server).resolve("reset-request-v4.tsv")) ? "committed"
                : Files.exists(control(server).resolve("staged-request-v4.tsv")) ? "staged" : "draft";
        List<String> allowedBiomes = allowedBiomes(server);
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
        return new View(status, worldName(server), lineage, biome, author, allowedBiomes, uploads, published, operator);
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
        if (!allowedBiomes(server).contains(biome)) throw new IllegalArgumentException("biome is not allowlisted");
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
        server.getPlayerList().saveAll();
        if (!server.saveEverything(true, true, true)) throw new IllegalStateException("Minecraft save finalization failed");
        String transaction = PrestigeContracts.newTransactionId();
        long oldSeed = server.overworld().getSeed();
        PrestigeContracts.writeWorldBinding(worldBindingPath(server), new PrestigeContracts.WorldBinding(
                lineage.lineageId(), lineage.generation(), transaction, staged.worldName(), oldSeed, staged.biome()));
        PrestigeContracts.writeReset(resetPath, new PrestigeContracts.Reset(lineage.lineageId(), lineage.generation(),
                transaction, staged.worldName(), oldSeed, staged.biome()));
        PrestigeCoordinator.scheduleStop();
        Files.deleteIfExists(control(server).resolve("staged-request-v4.tsv"));
        Files.deleteIfExists(control(server).resolve("draft-v4.tsv"));
        return transaction;
    }

    public static void publish(ServerPlayer player, String author, String fileName) throws IOException {
        long generation = lineage(player.server).generation();
        SchematicLibrary.publish(player.server, player.getGameProfile().getName(), player.hasPermissions(4), author, fileName, generation);
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
