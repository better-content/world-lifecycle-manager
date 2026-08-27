package com.bettercontent.worldlifecyclemanager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import wayoftime.bloodmagic.core.data.Binding;

public final class WorldCondenserInterfaceBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public WorldCondenserInterfaceBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WorldCondenserBlockEntity(pos, state);
    }

    static boolean isBoundApprenticeOrb(ItemStack stack) {
        var itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return itemId != null && itemId.toString().equals("bloodmagic:apprenticebloodorb")
                && Binding.fromStack(stack) != null;
    }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof WorldCondenserBlockEntity condenser)) return InteractionResult.PASS;
        ItemStack held = player.getItemInHand(hand);
        if (!condenser.isAttuned() && isBoundApprenticeOrb(held)) {
            if (!level.isClientSide) {
                condenser.attune();
                player.displayClientMessage(Component.translatable("message.world_lifecycle_manager.condenser_attuned"), false);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!condenser.isAttuned()) {
            if (!level.isClientSide) player.displayClientMessage(Component.translatable("message.world_lifecycle_manager.condenser_needs_orb"), true);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!WorldCondenserFormation.isFormed(level, pos, state)) {
            if (!level.isClientSide) player.displayClientMessage(Component.translatable("message.world_lifecycle_manager.condenser_unformed"), true);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            ThreadsBridge.emit(serverPlayer,"condenser","formed");
            if (!PrestigeNetwork.allowPhysicalOpen(serverPlayer)) return InteractionResult.CONSUME;
            try {
                NetworkHooks.openScreen(serverPlayer, condenser, buffer -> {
                    buffer.writeBlockPos(pos);
                    buffer.writeBoolean(false);
                    buffer.writeVarInt(0);
                });
            } catch (RuntimeException error) {
                PrestigeMod.LOGGER.error("World Condenser menu failed to open for {} at {}", serverPlayer.getScoreboardName(), pos, error);
                serverPlayer.displayClientMessage(Component.literal("World Condenser failed to open; check the server log."), false);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
