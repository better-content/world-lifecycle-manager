package com.bettercontent.worldlifecyclemanager;

import com.bettercontent.worldlifecyclemanager.PrestigeMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

public final class PrestigeNetwork {
    private static final String VERSION = "2";
    private static final int REFRESH_CACHE_TICKS = 10;
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(PrestigeMod.MOD_ID, "world_lifecycle_manager"))
            .networkProtocolVersion(() -> VERSION).clientAcceptedVersions(VERSION::equals)
            .serverAcceptedVersions(VERSION::equals).simpleChannel();
    private static int discriminator;
    private static final Map<ServerPlayer, CachedState> STATE_CACHE = new WeakHashMap<>();
    private static final Map<ServerPlayer, Long> LAST_PHYSICAL_OPEN = new WeakHashMap<>();

    private PrestigeNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(ActionPacket.class, discriminator++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ActionPacket::encode).decoder(ActionPacket::decode).consumerMainThread(ActionPacket::handle).add();
        CHANNEL.messageBuilder(StatePacket.class, discriminator++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StatePacket::encode).decoder(StatePacket::decode).consumerMainThread(StatePacket::handle).add();
        CHANNEL.messageBuilder(DownloadPacket.class, discriminator++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DownloadPacket::encode).decoder(DownloadPacket::decode).consumerMainThread(DownloadPacket::handle).add();
    }

    public static void sendAction(Action action, BlockPos pos, String value) {
        CHANNEL.sendToServer(new ActionPacket(action, pos, value));
    }

    public static boolean allowPhysicalOpen(ServerPlayer player) {
        long now = player.server.overworld().getGameTime();
        Long previous = LAST_PHYSICAL_OPEN.put(player, now);
        return previous == null || now < previous || now - previous >= REFRESH_CACHE_TICKS;
    }

    public static void sendState(ServerPlayer player, ViewKind view, boolean allowCached) {
        BlockPos pos = player.containerMenu instanceof WorldCondenserMenu menu ? menu.pos() : BlockPos.ZERO;
        long now = player.server.overworld().getGameTime();
        CachedState cached = STATE_CACHE.get(player);
        if (allowCached && cached != null && cached.view == view && cached.pos.equals(pos)
                && now >= cached.tick && now - cached.tick < REFRESH_CACHE_TICKS) {
            send(player, cached.packet);
            return;
        }
        try {
            StatePacket packet = new StatePacket(PrestigeService.view(player, view == ViewKind.SCHEMATICS), pos, view);
            STATE_CACHE.put(player, new CachedState(now, pos, view, packet));
            send(player, packet);
        } catch (Exception error) {
            String message = safeError(error);
            PrestigeMod.LOGGER.warn("World Condenser state failed for {}: {}", player.getScoreboardName(), message);
            StatePacket packet = StatePacket.failure(pos, view, message);
            STATE_CACHE.put(player, new CachedState(now, pos, view, packet));
            send(player, packet);
        }
    }

    private static void send(ServerPlayer player, StatePacket packet) {
        CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    private static String safeError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return message.length() <= 256 ? message : message.substring(0, 255) + "…";
    }

    private record CachedState(long tick, BlockPos pos, ViewKind view, StatePacket packet) {}
    public enum ViewKind { RESET, SCHEMATICS }
    public enum Action { REFRESH_RESET, REFRESH_SCHEMATICS, SET_BIOME, STAGE, CANCEL, COMMIT, PUBLISH, DOWNLOAD, REMOVE }

    public record ActionPacket(Action action, BlockPos pos, String value) {
        static void encode(ActionPacket packet, FriendlyByteBuf buffer) {
            buffer.writeEnum(packet.action); buffer.writeBlockPos(packet.pos); buffer.writeUtf(packet.value, 256);
        }
        static ActionPacket decode(FriendlyByteBuf buffer) {
            return new ActionPacket(buffer.readEnum(Action.class), buffer.readBlockPos(), buffer.readUtf(256));
        }
        static void handle(ActionPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.setPacketHandled(true);
            ServerPlayer player = context.getSender();
            if (player == null || !(player.containerMenu instanceof WorldCondenserMenu menu) || !menu.pos().equals(packet.pos)) return;
            try {
                switch (packet.action) {
                    case REFRESH_RESET -> sendState(player, ViewKind.RESET, true);
                    case REFRESH_SCHEMATICS -> sendState(player, ViewKind.SCHEMATICS, true);
                    case SET_BIOME -> {
                        PrestigeService.saveDraft(player, packet.value);
                    }
                    case STAGE -> {
                        requirePhysicalMenu(player, menu);
                        PrestigeService.stage(player, packet.pos);
                    }
                    case CANCEL -> PrestigeService.cancel(player);
                    case COMMIT -> {
                        requirePhysicalMenu(player, menu);
                        String tx = PrestigeService.commit(player, packet.pos, packet.value);
                        player.server.getPlayerList().broadcastSystemMessage(Component.literal(
                                "World Condenser committed " + tx + " by " + player.getGameProfile().getName()
                                        + "; all world and player state will be archived and reset."), false);
                    }
                    case PUBLISH -> {
                        int separator = packet.value.indexOf('/');
                        if (separator <= 0 || separator == packet.value.length() - 1) {
                            throw new IllegalArgumentException("upload selection is malformed");
                        }
                        PrestigeService.publish(player, packet.value.substring(0, separator), packet.value.substring(separator + 1));
                    }
                    case DOWNLOAD -> {
                        SchematicLibrary.Entry entry = SchematicLibrary.list(player.server).stream()
                                .filter(candidate -> candidate.id().equals(packet.value)).findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("unknown schematic entry"));
                        byte[] data = SchematicLibrary.download(player.server, entry.id());
                        CHANNEL.sendTo(new DownloadPacket(entry.author(), entry.originalName(), entry.sha256(), data),
                                player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
                    }
                    case REMOVE -> PrestigeService.remove(player, packet.value);
                }
            } catch (Exception error) {
                player.displayClientMessage(Component.literal("World Condenser refused: " + error.getMessage()), false);
            }
            switch (packet.action) {
                case SET_BIOME, STAGE, CANCEL -> sendState(player, ViewKind.RESET, false);
                case PUBLISH, REMOVE -> sendState(player, ViewKind.SCHEMATICS, false);
                default -> { }
            }
        }

        private static void requirePhysicalMenu(ServerPlayer player, WorldCondenserMenu menu) {
            if (menu.remote() || !menu.isInOriginalDimension(player) || !menu.stillValid(player)) {
                throw new IllegalStateException("destructive actions require a nearby physical World Condenser menu");
            }
        }
    }

    public record ClientEntry(String id, String author, String name, long size, String sha256) {}
    public record StatePacket(ViewKind view, String error, String status, String worldName, BlockPos pos, long total, long generation, String selectedBiome,
                              String author, boolean operator, List<String> biomes, List<String> uploads,
                              List<ClientEntry> published) {
        StatePacket(PrestigeService.View state, BlockPos pos, ViewKind view) {
            this(view, "", state.status(), state.worldName(), pos, state.lineage().totalPrestiges(), state.lineage().generation(),
                    state.selectedBiome(), state.author(), state.operator(), state.allowedBiomes(), state.ownUploads(),
                    state.published().stream().map(entry -> new ClientEntry(entry.id(), entry.author(), entry.originalName(),
                            entry.size(), entry.sha256())).toList());
        }
        static StatePacket failure(BlockPos pos, ViewKind view, String error) {
            return new StatePacket(view, error, "unavailable", "", pos, 0, 0, "", "", false,
                    List.of(), List.of(), List.of());
        }
        static void encode(StatePacket packet, FriendlyByteBuf buffer) {
            PrestigeLimits.requireSize("biomes", packet.biomes, PrestigeLimits.MAX_BIOMES);
            PrestigeLimits.requireSize("uploads", packet.uploads, SchematicLibrary.MAX_UPLOADS);
            PrestigeLimits.requireSize("published", packet.published, SchematicLibrary.MAX_PUBLISHED);
            buffer.writeEnum(packet.view); buffer.writeUtf(packet.error, 256);
            buffer.writeUtf(packet.status, 64); buffer.writeUtf(packet.worldName, 128); buffer.writeBlockPos(packet.pos);
            buffer.writeLong(packet.total);
            buffer.writeLong(packet.generation); buffer.writeUtf(packet.selectedBiome, 256); buffer.writeUtf(packet.author, 32);
            buffer.writeBoolean(packet.operator);
            buffer.writeCollection(packet.biomes, (out, value) -> out.writeUtf(value, 256));
            buffer.writeCollection(packet.uploads, (out, value) -> out.writeUtf(value, 256));
            buffer.writeCollection(packet.published, (out, entry) -> {
                out.writeUtf(entry.id, 64); out.writeUtf(entry.author, 32); out.writeUtf(entry.name, 256);
                out.writeLong(entry.size); out.writeUtf(entry.sha256, 64);
            });
        }
        static StatePacket decode(FriendlyByteBuf buffer) {
            ViewKind view = buffer.readEnum(ViewKind.class); String error = buffer.readUtf(256);
            String status = buffer.readUtf(64); String worldName = buffer.readUtf(128); BlockPos pos = buffer.readBlockPos();
            long total = buffer.readLong();
            long generation = buffer.readLong(); String biome = buffer.readUtf(256); String author = buffer.readUtf(32);
            boolean operator = buffer.readBoolean();
            List<String> biomes = readBounded(buffer, PrestigeLimits.MAX_BIOMES, in -> in.readUtf(256));
            List<String> uploads = readBounded(buffer, SchematicLibrary.MAX_UPLOADS, in -> in.readUtf(256));
            List<ClientEntry> entries = readBounded(buffer, SchematicLibrary.MAX_PUBLISHED, in -> new ClientEntry(
                    in.readUtf(64), in.readUtf(32), in.readUtf(256), in.readLong(), in.readUtf(64)));
            return new StatePacket(view, error, status, worldName, pos, total, generation, biome, author, operator,
                    biomes, uploads, entries);
        }
        static void handle(StatePacket packet, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().setPacketHandled(true);
            PrestigeClientState.accept(packet);
        }
    }

    private static <T> List<T> readBounded(FriendlyByteBuf buffer, int maximum, Function<FriendlyByteBuf, T> reader) {
        int count = buffer.readVarInt();
        PrestigeLimits.requireCount("packet collection", count, maximum);
        List<T> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(reader.apply(buffer));
        return List.copyOf(result);
    }

    public record DownloadPacket(String author, String name, String sha256, byte[] data) {
        static void encode(DownloadPacket packet, FriendlyByteBuf buffer) {
            buffer.writeUtf(packet.author, 32); buffer.writeUtf(packet.name, 256); buffer.writeUtf(packet.sha256, 64);
            buffer.writeByteArray(packet.data);
        }
        static DownloadPacket decode(FriendlyByteBuf buffer) {
            return new DownloadPacket(buffer.readUtf(32), buffer.readUtf(256), buffer.readUtf(64),
                    buffer.readByteArray((int) SchematicLibrary.MAX_BYTES));
        }
        static void handle(DownloadPacket packet, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().setPacketHandled(true);
            PrestigeClientState.saveDownload(packet);
        }
    }
}
