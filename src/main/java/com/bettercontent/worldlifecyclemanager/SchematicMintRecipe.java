package com.bettercontent.worldlifecyclemanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

public record SchematicMintRecipe(ResourceLocation id, NonNullList<Ingredient> ingredients, ItemStack result)
        implements Recipe<SimpleContainer> {
    @Override public boolean matches(SimpleContainer container, Level level) {
        java.util.List<ItemStack> remaining = new java.util.ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) remaining.add(stack);
        }
        if (remaining.size() != ingredients.size()) return false;
        for (Ingredient ingredient : ingredients) {
            int match = -1;
            for (int i = 0; i < remaining.size(); i++) if (ingredient.test(remaining.get(i))) { match = i; break; }
            if (match < 0) return false;
            remaining.remove(match);
        }
        return true;
    }
    @Override public ItemStack assemble(SimpleContainer container, net.minecraft.core.RegistryAccess access) { return result.copy(); }
    @Override public boolean canCraftInDimensions(int width, int height) { return width * height >= ingredients.size(); }
    @Override public ItemStack getResultItem(net.minecraft.core.RegistryAccess access) { return result.copy(); }
    @Override public ResourceLocation getId() { return id; }
    @Override public RecipeSerializer<?> getSerializer() { return PrestigeRegistry.SCHEMATIC_MINTING_SERIALIZER.get(); }
    @Override public RecipeType<?> getType() { return PrestigeRegistry.SCHEMATIC_MINTING.get(); }

    public static final class Serializer implements RecipeSerializer<SchematicMintRecipe> {
        @Override public SchematicMintRecipe fromJson(ResourceLocation id, JsonObject json) {
            JsonArray array = GsonHelper.getAsJsonArray(json, "ingredients");
            if (array.isEmpty() || array.size() > 3) throw new IllegalArgumentException("Schematic Mint recipes require 1-3 ingredients");
            NonNullList<Ingredient> ingredients = NonNullList.create();
            array.forEach(value -> ingredients.add(Ingredient.fromJson(value)));
            return new SchematicMintRecipe(id, ingredients, net.minecraftforge.common.crafting.CraftingHelper.getItemStack(GsonHelper.getAsJsonObject(json, "result"), true));
        }
        @Override public SchematicMintRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
            int size = buffer.readVarInt();
            if (size < 1 || size > 3) throw new IllegalArgumentException("invalid Schematic Mint ingredient count");
            NonNullList<Ingredient> ingredients = NonNullList.withSize(size, Ingredient.EMPTY);
            for (int i = 0; i < size; i++) ingredients.set(i, Ingredient.fromNetwork(buffer));
            return new SchematicMintRecipe(id, ingredients, buffer.readItem());
        }
        @Override public void toNetwork(FriendlyByteBuf buffer, SchematicMintRecipe recipe) {
            buffer.writeVarInt(recipe.ingredients.size());
            recipe.ingredients.forEach(ingredient -> ingredient.toNetwork(buffer));
            buffer.writeItem(recipe.result);
        }
    }
}
