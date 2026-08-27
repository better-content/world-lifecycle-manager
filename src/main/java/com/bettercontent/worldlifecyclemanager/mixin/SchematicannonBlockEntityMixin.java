package com.bettercontent.worldlifecyclemanager.mixin;

import com.bettercontent.worldlifecyclemanager.SchematicPrinterAccess;
import com.bettercontent.worldlifecyclemanager.SchematicannonSubstitutionAccess;
import com.bettercontent.worldlifecyclemanager.SchematicannonSubstitutions;
import com.simibubi.create.content.schematics.SchematicPrinter;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashMap;
import java.util.Map;

@Mixin(value = SchematicannonBlockEntity.class, remap = false)
public abstract class SchematicannonBlockEntityMixin implements SchematicannonSubstitutionAccess {
    @Unique private static final String WORLD_LIFECYCLE_MANAGER_RULES = "WorldLifecycleManagerSubstitutions";
    @Unique private final LinkedHashMap<ResourceLocation, ResourceLocation> worldLifecycleManager$rules = new LinkedHashMap<>();

    @Shadow public SchematicPrinter printer;

    @Inject(method = "read", at = @At("RETURN"))
    private void worldLifecycleManager$read(CompoundTag tag, boolean clientPacket, CallbackInfo ci) {
        worldLifecycleManager$rules.clear();
        ListTag list = tag.getList(WORLD_LIFECYCLE_MANAGER_RULES, Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size() && worldLifecycleManager$rules.size() < SchematicannonSubstitutions.MAX_RULES; index++) {
            CompoundTag row = list.getCompound(index);
            ResourceLocation source = ResourceLocation.tryParse(row.getString("Source"));
            ResourceLocation target = ResourceLocation.tryParse(row.getString("Target"));
            if (source == null || target == null || source.equals(target)) continue;
            worldLifecycleManager$rules.put(source, target);
        }
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void worldLifecycleManager$write(CompoundTag tag, boolean clientPacket, CallbackInfo ci) {
        ListTag list = new ListTag();
        worldLifecycleManager$rules.forEach((source, target) -> {
            CompoundTag row = new CompoundTag();
            row.putString("Source", source.toString());
            row.putString("Target", target.toString());
            list.add(row);
        });
        tag.put(WORLD_LIFECYCLE_MANAGER_RULES, list);
    }

    @Redirect(method = "tickPrinter", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/schematics/SchematicPrinter;getCurrentRequirement()Lcom/simibubi/create/content/schematics/requirement/ItemRequirement;"))
    private ItemRequirement worldLifecycleManager$resolveRequirement(SchematicPrinter printer) {
        ItemRequirement original = printer.getCurrentRequirement();
        ItemRequirement.StackRequirement originalStack = SchematicannonSubstitutions.simpleRequirement(original);
        if (originalStack == null || worldLifecycleManager$rules.isEmpty()) return original;
        SchematicannonBlockEntity cannon = (SchematicannonBlockEntity) (Object) this;
        if (cannon.hasCreativeCrate || SchematicannonSubstitutions.available(cannon, originalStack)) return original;
        BlockState sourceState = ((SchematicPrinterAccess) printer).worldLifecycleManager$currentState();
        if (!SchematicannonSubstitutions.eligible(sourceState)) return original;
        ResourceLocation sourceId = BuiltInRegistries.BLOCK.getKey(sourceState.getBlock());
        ResourceLocation targetId = worldLifecycleManager$rules.get(sourceId);
        if (targetId == null || !BuiltInRegistries.BLOCK.containsKey(targetId)) return original;
        Block target = BuiltInRegistries.BLOCK.get(targetId);
        BlockState replacement = SchematicannonSubstitutions.replacementState(sourceState, target);
        if (!SchematicannonSubstitutions.eligible(replacement)) return original;
        ItemRequirement replacementRequirement = ItemRequirement.of(replacement, null);
        ItemRequirement.StackRequirement replacementStack = SchematicannonSubstitutions.simpleRequirement(replacementRequirement);
        if (replacementStack == null) return original;
        int reservedForNativeBlocks = SchematicannonSubstitutions.nativeRequirement(cannon, replacementStack.stack.getItem());
        if (SchematicannonSubstitutions.availableCount(cannon, replacementStack)
                < reservedForNativeBlocks + replacementStack.stack.getCount()) return original;
        ((SchematicPrinterAccess) printer).worldLifecycleManager$replaceCurrentState(replacement);
        return printer.getCurrentRequirement();
    }

    @Override public Map<ResourceLocation, ResourceLocation> worldLifecycleManager$substitutions() {
        return Map.copyOf(worldLifecycleManager$rules);
    }

    @Override public void worldLifecycleManager$setSubstitution(ResourceLocation source, ResourceLocation target) {
        SchematicannonSubstitutions.validateRule(source, target, worldLifecycleManager$rules);
        if (!worldLifecycleManager$rules.containsKey(source) && worldLifecycleManager$rules.size() >= SchematicannonSubstitutions.MAX_RULES) {
            throw new IllegalStateException("schematicannon substitution rule limit reached");
        }
        worldLifecycleManager$rules.put(source, target);
        worldLifecycleManager$changed();
    }

    @Override public void worldLifecycleManager$clearSubstitution(ResourceLocation source) {
        if (worldLifecycleManager$rules.remove(source) != null) worldLifecycleManager$changed();
    }

    @Override public void worldLifecycleManager$clearSubstitutions() {
        if (!worldLifecycleManager$rules.isEmpty()) { worldLifecycleManager$rules.clear(); worldLifecycleManager$changed(); }
    }

    @Unique private void worldLifecycleManager$changed() {
        SchematicannonBlockEntity cannon = (SchematicannonBlockEntity) (Object) this;
        if (cannon.state == SchematicannonBlockEntity.State.RUNNING) {
            cannon.state = SchematicannonBlockEntity.State.PAUSED;
            cannon.statusMsg = "ready";
            cannon.missingItem = net.minecraft.world.item.ItemStack.EMPTY;
        }
        cannon.setChanged();
        cannon.updateChecklist();
        cannon.sendData();
    }
}
