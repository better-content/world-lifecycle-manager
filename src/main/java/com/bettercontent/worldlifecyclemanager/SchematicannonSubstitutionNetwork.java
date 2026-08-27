package com.bettercontent.worldlifecyclemanager;

import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.cannon.SchematicannonMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class SchematicannonSubstitutionNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(PrestigeMod.MOD_ID, "schematicannon_substitutions"))
            .networkProtocolVersion(() -> VERSION).clientAcceptedVersions(VERSION::equals)
            .serverAcceptedVersions(VERSION::equals).simpleChannel();
    private static int discriminator;

    public enum EditKind { SET, CLEAR, CLEAR_ALL }

    private SchematicannonSubstitutionNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(RequestPacket.class, discriminator++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RequestPacket::encode).decoder(RequestPacket::decode).consumerMainThread(RequestPacket::handle).add();
        CHANNEL.messageBuilder(EditPacket.class, discriminator++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(EditPacket::encode).decoder(EditPacket::decode).consumerMainThread(EditPacket::handle).add();
        CHANNEL.messageBuilder(StatePacket.class, discriminator++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StatePacket::encode).decoder(StatePacket::decode).consumerMainThread(StatePacket::handle).add();
    }

    public static void request(BlockPos pos) { CHANNEL.sendToServer(new RequestPacket(pos)); }
    public static void edit(EditKind kind, BlockPos pos, ResourceLocation source, ResourceLocation target) {
        CHANNEL.sendToServer(new EditPacket(kind, pos, source, target));
    }

    static void send(ServerPlayer player, SchematicannonBlockEntity cannon) {
        var access = (SchematicannonSubstitutionAccess) cannon;
        List<SchematicannonSubstitutions.Row> rows = SchematicannonSubstitutions.evaluate(cannon, access.worldLifecycleManager$substitutions());
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new StatePacket(cannon.getBlockPos(), rows, access.worldLifecycleManager$substitutions()));
    }

    private static SchematicannonBlockEntity openCannon(ServerPlayer player, BlockPos pos) {
        if (!(player.containerMenu instanceof SchematicannonMenu menu) || menu.contentHolder == null
                || !menu.contentHolder.getBlockPos().equals(pos)) return null;
        if (player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 64) return null;
        return menu.contentHolder;
    }

    public record RequestPacket(BlockPos pos) {
        static void encode(RequestPacket packet, FriendlyByteBuf buffer) { buffer.writeBlockPos(packet.pos); }
        static RequestPacket decode(FriendlyByteBuf buffer) { return new RequestPacket(buffer.readBlockPos()); }
        static void handle(RequestPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get(); context.setPacketHandled(true);
            ServerPlayer player = context.getSender();
            if (player == null) return;
            SchematicannonBlockEntity cannon = openCannon(player, packet.pos);
            if (cannon != null) send(player, cannon);
        }
    }

    public record EditPacket(EditKind kind, BlockPos pos, ResourceLocation source, ResourceLocation target) {
        static void encode(EditPacket packet, FriendlyByteBuf buffer) {
            buffer.writeEnum(packet.kind); buffer.writeBlockPos(packet.pos);
            buffer.writeBoolean(packet.source != null); if (packet.source != null) buffer.writeResourceLocation(packet.source);
            buffer.writeBoolean(packet.target != null); if (packet.target != null) buffer.writeResourceLocation(packet.target);
        }
        static EditPacket decode(FriendlyByteBuf buffer) {
            EditKind kind = buffer.readEnum(EditKind.class); BlockPos pos = buffer.readBlockPos();
            ResourceLocation source = buffer.readBoolean() ? buffer.readResourceLocation() : null;
            ResourceLocation target = buffer.readBoolean() ? buffer.readResourceLocation() : null;
            return new EditPacket(kind, pos, source, target);
        }
        static void handle(EditPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get(); context.setPacketHandled(true);
            ServerPlayer player = context.getSender();
            if (player == null) return;
            SchematicannonBlockEntity cannon = openCannon(player, packet.pos);
            if (cannon == null) return;
            var access = (SchematicannonSubstitutionAccess) cannon;
            try {
                if (packet.kind == EditKind.CLEAR_ALL) access.worldLifecycleManager$clearSubstitutions();
                else if (packet.source != null && packet.kind == EditKind.CLEAR) access.worldLifecycleManager$clearSubstitution(packet.source);
                else if (packet.kind == EditKind.SET && packet.source != null && packet.target != null
                        && isCurrentSource(cannon, packet.source) && carriedTarget(player, packet.target)) {
                    access.worldLifecycleManager$setSubstitution(packet.source, packet.target);
                }
            } catch (IllegalArgumentException | IllegalStateException error) {
                PrestigeMod.LOGGER.warn("Rejected Schematicannon substitution edit from {}: {}", player.getScoreboardName(), error.getMessage());
            }
            send(player, cannon);
        }
    }

    private static boolean isCurrentSource(SchematicannonBlockEntity cannon, ResourceLocation source) {
        return SchematicannonSubstitutions.evaluate(cannon,
                ((SchematicannonSubstitutionAccess) cannon).worldLifecycleManager$substitutions()).stream()
                .anyMatch(row -> row.source().equals(source));
    }

    private static boolean carriedTarget(ServerPlayer player, ResourceLocation target) {
        ItemStack carried = player.containerMenu.getCarried();
        return carried.getItem() instanceof BlockItem item && BuiltInRegistries.BLOCK.getKey(item.getBlock()).equals(target);
    }

    public record StatePacket(BlockPos pos, List<SchematicannonSubstitutions.Row> rows,
                              Map<ResourceLocation, ResourceLocation> rules) {
        static void encode(StatePacket packet, FriendlyByteBuf buffer) {
            if (packet.rows.size() > SchematicannonSubstitutions.MAX_ROWS || packet.rules.size() > SchematicannonSubstitutions.MAX_RULES) {
                throw new IllegalArgumentException("oversized Schematicannon substitution state");
            }
            buffer.writeBlockPos(packet.pos);
            buffer.writeCollection(packet.rows, (out, row) -> {
                out.writeResourceLocation(row.source()); out.writeVarInt(row.required()); out.writeVarInt(row.available());
                out.writeBoolean(row.target() != null); if (row.target() != null) out.writeResourceLocation(row.target());
                out.writeVarInt(row.fallbackAvailable()); out.writeVarInt(row.fallbackNeeded());
                out.writeVarInt(row.covered()); out.writeVarInt(row.uncovered());
            });
            buffer.writeMap(packet.rules, FriendlyByteBuf::writeResourceLocation, FriendlyByteBuf::writeResourceLocation);
        }
        static StatePacket decode(FriendlyByteBuf buffer) {
            BlockPos pos = buffer.readBlockPos();
            int rowCount = boundedCount(buffer, SchematicannonSubstitutions.MAX_ROWS);
            java.util.ArrayList<SchematicannonSubstitutions.Row> rows = new java.util.ArrayList<>(rowCount);
            for (int index = 0; index < rowCount; index++) {
                ResourceLocation source = buffer.readResourceLocation(); int required = buffer.readVarInt(); int available = buffer.readVarInt();
                ResourceLocation target = buffer.readBoolean() ? buffer.readResourceLocation() : null;
                rows.add(new SchematicannonSubstitutions.Row(source, required, available, target,
                        buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()));
            }
            int ruleCount = boundedCount(buffer, SchematicannonSubstitutions.MAX_RULES);
            java.util.LinkedHashMap<ResourceLocation, ResourceLocation> rules = new java.util.LinkedHashMap<>();
            for (int index = 0; index < ruleCount; index++) rules.put(buffer.readResourceLocation(), buffer.readResourceLocation());
            return new StatePacket(pos, List.copyOf(rows), Map.copyOf(rules));
        }
        static int boundedCount(FriendlyByteBuf buffer, int maximum) {
            int count = buffer.readVarInt();
            if (count < 0 || count > maximum) throw new IllegalArgumentException("oversized Schematicannon substitution collection");
            return count;
        }
        static void handle(StatePacket packet, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().setPacketHandled(true);
            SchematicannonSubstitutionClient.accept(packet);
        }
    }
}
