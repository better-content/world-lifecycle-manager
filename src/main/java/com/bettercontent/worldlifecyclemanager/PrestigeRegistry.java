package com.bettercontent.worldlifecyclemanager;

import com.bettercontent.worldlifecyclemanager.PrestigeMod;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class PrestigeRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, PrestigeMod.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, PrestigeMod.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, PrestigeMod.MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, PrestigeMod.MOD_ID);

    private static BlockBehaviour.Properties condenserProperties() {
        return BlockBehaviour.Properties.of().strength(5.0F, 18.0F).sound(SoundType.METAL).requiresCorrectToolForDrops();
    }

    public static final RegistryObject<Block> WORLD_CONDENSER_HULL = BLOCKS.register("world_condenser_hull",
            () -> new Block(condenserProperties()));
    public static final RegistryObject<Block> WORLD_CONDENSER_INTERFACE = BLOCKS.register("world_condenser_interface",
            () -> new WorldCondenserInterfaceBlock(condenserProperties().noOcclusion()));
    public static final RegistryObject<Item> WORLD_CONDENSER_HULL_ITEM = ITEMS.register("world_condenser_hull",
            () -> new BlockItem(WORLD_CONDENSER_HULL.get(), new Item.Properties()));
    public static final RegistryObject<Item> WORLD_CONDENSER_INTERFACE_ITEM = ITEMS.register("world_condenser_interface",
            () -> new BlockItem(WORLD_CONDENSER_INTERFACE.get(), new Item.Properties().stacksTo(1)));
    public static final RegistryObject<BlockEntityType<WorldCondenserBlockEntity>> WORLD_CONDENSER_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("world_condenser_interface", () -> BlockEntityType.Builder
                    .of(WorldCondenserBlockEntity::new, WORLD_CONDENSER_INTERFACE.get()).build(null));
    public static final RegistryObject<MenuType<WorldCondenserMenu>> WORLD_CONDENSER_MENU = MENUS.register(
            "world_condenser", () -> IForgeMenuType.create(WorldCondenserMenu::new));

    private PrestigeRegistry() {}
}
