package com.bettercontent.worldlifecyclemanager;

import com.bettercontent.worldlifecyclemanager.PrestigeMod;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
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
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, PrestigeMod.MOD_ID);
    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES = DeferredRegister.create(ForgeRegistries.RECIPE_TYPES, PrestigeMod.MOD_ID);

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
    public static final RegistryObject<Block> SCHEMATIC_MINT = BLOCKS.register("schematic_mint",
            () -> new SchematicMintBlock(BlockBehaviour.Properties.of().strength(3.5F, 6.0F).sound(SoundType.METAL).requiresCorrectToolForDrops()));
    public static final RegistryObject<Item> SCHEMATIC_MINT_ITEM = ITEMS.register("schematic_mint",
            () -> new BlockItem(SCHEMATIC_MINT.get(), new Item.Properties().stacksTo(1)));
    public static final RegistryObject<BlockEntityType<SchematicMintBlockEntity>> SCHEMATIC_MINT_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("schematic_mint", () -> BlockEntityType.Builder
                    .of(SchematicMintBlockEntity::new, SCHEMATIC_MINT.get()).build(null));
    public static final RegistryObject<MenuType<SchematicMintMenu>> SCHEMATIC_MINT_MENU = MENUS.register(
            "schematic_mint", () -> IForgeMenuType.create(SchematicMintMenu::new));
    public static final RegistryObject<RecipeType<SchematicMintRecipe>> SCHEMATIC_MINTING = RECIPE_TYPES.register(
            "schematic_minting", () -> new RecipeType<>() { @Override public String toString() { return PrestigeMod.MOD_ID + ":schematic_minting"; } });
    public static final RegistryObject<RecipeSerializer<SchematicMintRecipe>> SCHEMATIC_MINTING_SERIALIZER = RECIPE_SERIALIZERS.register(
            "schematic_minting", SchematicMintRecipe.Serializer::new);

    private PrestigeRegistry() {}
}
