package com.bettercontent.worldlifecyclemanager;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraftforge.network.NetworkHooks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.file.Files;
import java.nio.file.Path;

public final class PrestigeCoordinator {
    private static int stopCountdown = -1;
    private static int shutdownPoll = 0;

    private PrestigeCoordinator() {}

    public static void scheduleStop() { stopCountdown = 20; }

    @SubscribeEvent public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) PrestigeNetwork.sendManifest(player);
    }
    @SubscribeEvent public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) PrestigeNetwork.cancelSync(player);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("world_lifecycle_manager")
                .then(Commands.literal("status").executes(context -> {
                    try {
                        var lineage = PrestigeService.lineage(context.getSource().getServer());
                        var build = PrestigePerks.draft(context.getSource().getServer());
                        context.getSource().sendSuccess(() -> Component.literal("Prestige generation=" + lineage.generation()
                                + " total=" + lineage.totalPrestiges() + " status=" + prestigeStatus(context.getSource().getServer())
                                + " biomes=" + (build.biomes().isEmpty() ? "-" : String.join(",", build.biomes()))), false);
                        return 1;
                    } catch (Exception error) {
                        context.getSource().sendFailure(Component.literal("Prestige status failed: " + error.getMessage()));
                        return 0;
                    }
                }))
                .then(Commands.literal("gui")
                        .executes(context -> openGui(context.getSource(), context.getSource().getPlayerOrException(), 0))
                        .then(Commands.literal("configure").executes(context ->
                                openGui(context.getSource(), context.getSource().getPlayerOrException(), 0)))
                        .then(Commands.literal("schematics").executes(context ->
                                openGui(context.getSource(), context.getSource().getPlayerOrException(), 1)))
                        .then(Commands.literal("perks").executes(context ->
                                openGui(context.getSource(), context.getSource().getPlayerOrException(), 2)))
                        .then(Commands.literal("player").requires(source -> source.hasPermission(4))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.literal("configure").executes(context -> openGui(
                                                context.getSource(), EntityArgument.getPlayer(context, "player"), 0)))
                                        .then(Commands.literal("schematics").executes(context -> openGui(
                                                context.getSource(), EntityArgument.getPlayer(context, "player"), 1)))
                                        .then(Commands.literal("perks").executes(context -> openGui(
                                                context.getSource(), EntityArgument.getPlayer(context, "player"), 2))))))
                .then(Commands.literal("perks")
                        .executes(context -> {
                            try {
                                var build = PrestigePerks.draft(context.getSource().getServer());
                                context.getSource().sendSuccess(() -> Component.literal("Upcoming prestige perks "
                                        + build.perks().size() + "/" + build.budget() + ": " + String.join(", ", build.ids())), false);
                                return 1;
                            } catch (Exception error) { context.getSource().sendFailure(Component.literal("Prestige perks failed: " + error.getMessage())); return 0; }
                        })
                        .then(Commands.literal("allocate").requires(source -> source.hasPermission(4))
                                .then(Commands.argument("perk", StringArgumentType.word()).executes(context -> perkMutation(context, true))))
                        .then(Commands.literal("refund").requires(source -> source.hasPermission(4))
                                .then(Commands.argument("perk", StringArgumentType.word()).executes(context -> perkMutation(context, false)))))
                .then(Commands.literal("select").requires(source -> source.hasPermission(4))
                        .then(Commands.argument("biomes", StringArgumentType.greedyString()).executes(context -> {
                            try {
                                java.util.List<String> biomes = parseBiomeArguments(StringArgumentType.getString(context, "biomes"));
                                PrestigeService.saveDraft(context.getSource().getServer(), biomes, "Operator");
                                PrestigeMod.LOGGER.info("Prestige CLI selection succeeded actor={} biomes={}",
                                        context.getSource().getTextName(), String.join(",", biomes));
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "Selected Prestige biomes " + String.join(" > ", biomes) + "; stage with /world_lifecycle_manager stage"), true);
                                return 1;
                            } catch (Exception error) {
                                PrestigeMod.LOGGER.warn("Prestige CLI selection failed actor={}: {}",
                                        context.getSource().getTextName(), error.getMessage());
                                context.getSource().sendFailure(Component.literal("Prestige selection failed: " + error.getMessage()));
                                return 0;
                            }
                        })))
                .then(Commands.literal("stage").requires(source -> source.hasPermission(4))
                        .executes(context -> {
                            try {
                                PrestigeService.stage(context.getSource().getServer());
                                PrestigeMod.LOGGER.info("Prestige CLI stage succeeded actor={}", context.getSource().getTextName());
                                context.getSource().sendSuccess(() -> Component.literal("Staged prestige reset; commit with /world_lifecycle_manager commit "
                                        + PrestigeService.worldName(context.getSource().getServer())), true);
                                return 1;
                            } catch (Exception error) {
                                PrestigeMod.LOGGER.warn("Prestige CLI stage failed actor={}: {}",
                                        context.getSource().getTextName(), error.getMessage());
                                context.getSource().sendFailure(Component.literal("Prestige stage failed: " + error.getMessage()));
                                return 0;
                            }
                        }))
                .then(Commands.literal("cancel").requires(source -> source.hasPermission(4)).executes(context -> {
                    try {
                        PrestigeService.cancel(context.getSource().getServer());
                        context.getSource().sendSuccess(() -> Component.literal("Cancelled staged prestige request"), true);
                        return 1;
                    } catch (Exception error) {
                        context.getSource().sendFailure(Component.literal("Prestige cancel failed: " + error.getMessage()));
                        return 0;
                    }
                }))
                .then(Commands.literal("commit").requires(source -> source.hasPermission(4))
                        .then(Commands.argument("world-name", StringArgumentType.word()).executes(context -> {
                            try {
                                String transaction = PrestigeService.commit(context.getSource().getServer(),
                                        StringArgumentType.getString(context, "world-name"));
                                PrestigeMod.LOGGER.info("Prestige CLI commit succeeded actor={} transaction={}",
                                        context.getSource().getTextName(), transaction);
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "Prestige commit accepted: " + transaction + "; clean shutdown is scheduled"), true);
                                context.getSource().getServer().getPlayerList().broadcastSystemMessage(Component.literal(
                                        "Prestige committed " + transaction + " by operator command; all world and player state "
                                                + "will be archived and reset."), false);
                                return 1;
                            } catch (Exception error) {
                                PrestigeMod.LOGGER.warn("Prestige CLI commit failed actor={}: {}",
                                        context.getSource().getTextName(), error.getMessage());
                                context.getSource().sendFailure(Component.literal("Prestige commit failed: " + error.getMessage()));
                                return 0;
                            }
                        })))
                .then(Commands.literal("recovery").requires(source -> source.hasPermission(4))
                        .then(Commands.literal("cancel-staged").executes(context -> {
                            try {
                                Files.deleteIfExists(PrestigeService.control(context.getSource().getServer()).resolve("staged-request-v5.tsv"));
                                PrestigePerks.cancel(context.getSource().getServer());
                                context.getSource().sendSuccess(() -> Component.literal("Cancelled staged prestige request"), true);
                                return 1;
                            } catch (Exception error) {
                                context.getSource().sendFailure(Component.literal("Recovery cancel failed: " + error.getMessage()));
                                return 0;
                            }
                        }))));
    }

    private static int perkMutation(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context,
                                    boolean allocate) {
        try {
            String id = StringArgumentType.getString(context, "perk");
            if (allocate) PrestigePerks.allocate(context.getSource().getServer(), id);
            else PrestigePerks.refund(context.getSource().getServer(), id);
            context.getSource().sendSuccess(() -> Component.literal((allocate ? "Allocated " : "Refunded ") + id), true);
            return 1;
        } catch (Exception error) {
            context.getSource().sendFailure(Component.literal("Prestige perk change failed: " + error.getMessage()));
            return 0;
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        PrestigeNetwork.tickSync(server);
        Path successorPath = PrestigeService.control(server).resolve("successor-request-v5.tsv");
        if (!Files.isRegularFile(successorPath)) return;
        try {
            PrestigeContracts.Successor successor = PrestigeContracts.readSuccessor(successorPath);
            PrestigePerks.Build perks = PrestigePerks.reset(server, successor);
            if (successor.attempt() > perks.successorAttempts()) {
                throw new IllegalStateException("successor attempt is not authorized by the committed perk build");
            }
            PrestigeContracts.Lineage lineage = PrestigeService.lineage(server);
            if (!successor.lineageId().equals(lineage.lineageId())
                    || successor.baseGeneration() != lineage.generation()
                    || successor.targetGeneration() != lineage.generation() + 1) {
                throw new IllegalStateException("successor request does not match the active lineage generation");
            }
            ServerLevel level = server.overworld();
            LandingResult landing = resolveLanding(level, successor, perks);
            boolean foundExact = landing != null;
            BlockPos spawn = foundExact ? landing.pos() : level.getSharedSpawnPos();
            if (foundExact) configureSuccessorSpawn(server, level, spawn);
            String actualBiome = level.getBiome(spawn).unwrapKey()
                    .map(key -> key.location().toString()).orElse("minecraft:the_void");
            boolean fresh = freshDirectory(server.getWorldPath(LevelResource.PLAYER_DATA_DIR))
                    && freshDirectory(server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR))
                    && freshDirectory(server.getWorldPath(LevelResource.PLAYER_STATS_DIR))
                    && server.getPlayerCount() == 0 && foundExact;
            if (!Files.isRegularFile(server.getWorldPath(LevelResource.LEVEL_DATA_FILE))) {
                throw new IllegalStateException("successor level.dat is missing");
            }
            String resolvedBiome = foundExact ? landing.resolvedBiome() : "-";
            PrestigeContracts.writeHealth(PrestigeService.control(server).resolve("health-result-v5.tsv"), successor,
                    level.getSeed(), resolvedBiome, actualBiome, PrestigeService.worldName(server), fresh, foundExact);
            PrestigePerks.writeHealth(server, successor, perks, resolvedBiome, spawn);
            server.sendSystemMessage(Component.literal("Prestige successor health published for " + successor.transactionId()
                    + " biome=" + actualBiome));
        } catch (Exception error) {
            server.sendSystemMessage(Component.literal("Prestige successor health failed: " + error.getMessage()));
        }
    }

    private record LandingResult(BlockPos pos, String resolvedBiome) {}

    static void configureSuccessorSpawn(MinecraftServer server, ServerLevel level, BlockPos spawn) {
        ChunkPos previous = new ChunkPos(level.getSharedSpawnPos());
        ChunkPos target = new ChunkPos(spawn);
        if (!previous.equals(target)) {
            level.getChunkSource().removeRegionTicket(TicketType.START, previous, 11, Unit.INSTANCE);
        }
        level.setDefaultSpawnPos(spawn, 0.0F);
        level.getChunkSource().addRegionTicket(TicketType.START, target, 11, Unit.INSTANCE);
        // Avoid vanilla's random 21x21 respawn search synchronously loading neighboring C2ME chunks.
        level.getGameRules().getRule(GameRules.RULE_SPAWN_RADIUS).set(0, server);
    }

    private static LandingResult resolveLanding(ServerLevel level, PrestigeContracts.Successor successor,
                                                PrestigePerks.Build perks) {
        for (String target : successor.biomes()) {
            Pair<BlockPos, Holder<Biome>> found = findBiome(level, target);
            if (found == null) continue;
            BlockPos candidate = found.getFirst();
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, candidate.getX(), candidate.getZ());
            return new LandingResult(new BlockPos(candidate.getX(), y, candidate.getZ()), target);
        }
        return null;
    }

    private static Pair<BlockPos, Holder<Biome>> findBiome(ServerLevel level, String id) {
        ResourceLocation requested = new ResourceLocation(id);
        return level.findClosestBiome3d(holder -> holder.unwrapKey().map(key -> key.location().equals(requested)).orElse(false),
                level.getSharedSpawnPos(), 16_384, 32, 64);
    }

    static java.util.List<String> parseBiomeArguments(String input) {
        String stripped = input == null ? "" : input.strip();
        java.util.List<String> biomes = stripped.isEmpty() ? java.util.List.of() : java.util.List.of(stripped.split("\\s+"));
        PrestigeContracts.validateBiomes(biomes);
        return biomes;
    }

    private static String prestigeStatus(MinecraftServer server) {
        Path control = PrestigeService.control(server);
        return Files.exists(control.resolve("successor-request-v5.tsv")) ? "successor-starting"
                : Files.exists(control.resolve("reset-request-v5.tsv")) ? "committed"
                : Files.exists(control.resolve("staged-request-v5.tsv")) ? "staged" : "draft";
    }

    private static int openGui(CommandSourceStack source, ServerPlayer player, int tab) {
        NetworkHooks.openScreen(player, new SimpleMenuProvider(
                (id, inventory, ignored) -> new WorldCondenserMenu(id, inventory, BlockPos.ZERO, true, tab),
                Component.literal("Prestige")), buffer -> {
            buffer.writeBlockPos(BlockPos.ZERO);
            buffer.writeBoolean(true);
            buffer.writeVarInt(tab);
        });
        String view = switch (tab) { case 0 -> "configure"; case 1 -> "schematics"; default -> "perks"; };
        source.sendSuccess(() -> Component.literal("Opened Prestige " + view + " for " + player.getScoreboardName()), false);
        return 1;
    }

    private static boolean freshDirectory(Path path) throws java.io.IOException {
        if (!Files.exists(path)) return true;
        if (!Files.isDirectory(path)) return false;
        try (var entries = Files.list(path)) { return entries.findAny().isEmpty(); }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (stopCountdown >= 0 && --stopCountdown <= 0) {
            stopCountdown = -1;
            server.halt(false);
            return;
        }
        if (++shutdownPoll < 20) return;
        shutdownPoll = 0;
        Path shutdownPath = PrestigeService.control(server).resolve("shutdown-request-v5.tsv");
        if (!Files.isRegularFile(shutdownPath)) return;
        try {
            String transaction = PrestigeContracts.readShutdownTransaction(shutdownPath);
            Path activePath = PrestigeService.control(server).resolve("active-successor-process-v2.tsv");
            if (Files.isRegularFile(activePath)
                    && PrestigeContracts.readActiveSuccessor(activePath).transactionId().equals(transaction)) {
                Files.deleteIfExists(shutdownPath);
                server.saveEverything(true, true, true);
                server.halt(false);
            }
        } catch (Exception error) {
            server.sendSystemMessage(Component.literal("Ignoring invalid prestige shutdown request: " + error.getMessage()));
        }
    }
}
