package com.bettercontent.worldlifecyclemanager;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

public interface SchematicannonSubstitutionAccess {
    Map<ResourceLocation, ResourceLocation> worldLifecycleManager$substitutions();
    void worldLifecycleManager$setSubstitution(ResourceLocation source, ResourceLocation target);
    void worldLifecycleManager$clearSubstitution(ResourceLocation source);
    void worldLifecycleManager$clearSubstitutions();
}
