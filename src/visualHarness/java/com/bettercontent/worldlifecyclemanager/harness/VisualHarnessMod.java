package com.bettercontent.worldlifecyclemanager.harness;

import com.bettercontent.worldlifecyclemanager.SchematicannonSubstitutionAccess;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

@Mod(VisualHarnessMod.MOD_ID)
public final class VisualHarnessMod {
    public static final String MOD_ID = "world_lifecycle_manager_visual_harness";
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(ResourceLocation.fromNamespaceAndPath(MOD_ID, "capture")).networkProtocolVersion(() -> "1")
            .clientAcceptedVersions("1"::equals).serverAcceptedVersions("1"::equals).simpleChannel();

    public VisualHarnessMod() {
        CHANNEL.messageBuilder(CapturePacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(CapturePacket::encode).decoder(CapturePacket::decode)
                .consumerMainThread(CapturePacket::handle).add();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("wlmvisual").requires(source -> source.hasPermission(2))
                .then(Commands.literal("prepare").then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> prepare(EntityArgument.getPlayer(context, "player")))))
                .then(Commands.literal("capture").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("name", StringArgumentType.word()).executes(context -> capture(
                                EntityArgument.getPlayer(context, "player"), StringArgumentType.getString(context, "name")))))));
    }

    private static int prepare(ServerPlayer player) {
        BlockPos pos = player.blockPosition().east(2);
        player.serverLevel().setBlockAndUpdate(pos, AllBlocks.SCHEMATICANNON.getDefaultState());
        if (!(player.serverLevel().getBlockEntity(pos) instanceof SchematicannonBlockEntity cannon)) return 0;
        ((SchematicannonSubstitutionAccess) cannon).worldLifecycleManager$setSubstitution(
                ResourceLocation.fromNamespaceAndPath("minecraft", "stone"),
                ResourceLocation.fromNamespaceAndPath("minecraft", "cobblestone"));
        cannon.checklist.required.clear();
        cannon.checklist.gathered.clear();
        cannon.checklist.required.put(Blocks.STONE.asItem(), 48);
        cannon.checklist.gathered.put(Blocks.STONE.asItem(), 12);
        cannon.checklist.required.put(Blocks.COBBLESTONE.asItem(), 8);
        cannon.checklist.gathered.put(Blocks.COBBLESTONE.asItem(), 40);
        cannon.checklist.required.put(Blocks.OAK_PLANKS.asItem(), 24);
        cannon.checklist.gathered.put(Blocks.OAK_PLANKS.asItem(), 24);
        cannon.sendData();
        NetworkHooks.openScreen(player, cannon, cannon::sendToMenu);
        player.getServer().sendSystemMessage(Component.literal("WLM_VISUAL prepared real Schematicannon menu at " + pos));
        return 1;
    }

    private static int capture(ServerPlayer player, String name) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new CapturePacket(name));
        player.getServer().sendSystemMessage(Component.literal("WLM_VISUAL capture requested: " + name));
        return 1;
    }

    record CapturePacket(String name) {
        static void encode(CapturePacket packet, FriendlyByteBuf buffer) { buffer.writeUtf(packet.name, 64); }
        static CapturePacket decode(FriendlyByteBuf buffer) { return new CapturePacket(buffer.readUtf(64)); }
        static void handle(CapturePacket packet, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> supplier) {
            supplier.get().setPacketHandled(true);
            VisualHarnessScreenshot.request(packet.name);
        }
    }
}
