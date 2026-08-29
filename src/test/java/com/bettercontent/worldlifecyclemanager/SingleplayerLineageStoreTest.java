package com.bettercontent.worldlifecyclemanager;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class SingleplayerLineageStoreTest {
    @TempDir Path temp;

    @Test void createsStableGenerationZeroBindingAndState() throws Exception {
        Path global = temp.resolve("game/.world_lifecycle_manager");
        Path world = temp.resolve("game/saves/first");

        var first = SingleplayerLineageStore.open(global, world);
        var second = SingleplayerLineageStore.open(global, world);

        assertEquals(first, second);
        assertEquals(0, first.lineage().generation());
        assertEquals(0, first.lineage().totalPrestiges());
        assertTrue(first.lineage().lineageId().startsWith("lineage-"));
        assertTrue(Files.isRegularFile(world.resolve(
                "data/world_lifecycle_manager/singleplayer-lineage-v1.tsv")));
        assertTrue(Files.isRegularFile(first.stateRoot().resolve("lineage-v5.tsv")));
    }

    @Test void independentWorldsDifferButInheritedBindingSharesLineageData() throws Exception {
        Path global = temp.resolve("game/.world_lifecycle_manager");
        Path firstWorld = temp.resolve("game/saves/first");
        Path secondWorld = temp.resolve("game/saves/second");
        Path successorWorld = temp.resolve("game/saves/successor");
        var first = SingleplayerLineageStore.open(global, firstWorld);
        var second = SingleplayerLineageStore.open(global, secondWorld);
        assertNotEquals(first.lineage().lineageId(), second.lineage().lineageId());

        Path relativeBinding = Path.of("data/world_lifecycle_manager/singleplayer-lineage-v1.tsv");
        Files.createDirectories(successorWorld.resolve(relativeBinding).getParent());
        Files.copy(firstWorld.resolve(relativeBinding), successorWorld.resolve(relativeBinding),
                StandardCopyOption.COPY_ATTRIBUTES);
        var successor = SingleplayerLineageStore.open(global, successorWorld);
        assertEquals(first, successor);

        var key = new ResourceLocation("better_content_threads", "threads");
        UUID player = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        var payload = new CompoundTag();
        payload.putString("card", "world_remembers");
        LineagePlayerDataStore.write(first.stateRoot(), first.lineage().lineageId(), key, player, payload);
        assertEquals("world_remembers", LineagePlayerDataStore.read(successor.stateRoot(),
                successor.lineage().lineageId(), key, player).getString("card"));
    }

    @Test void missingOrMismatchedDurableStateDoesNotCreateAReplacementLineage() throws Exception {
        Path global = temp.resolve("game/.world_lifecycle_manager");
        Path world = temp.resolve("game/saves/first");
        var context = SingleplayerLineageStore.open(global, world);
        Files.delete(context.stateRoot().resolve("lineage-v5.tsv"));
        assertThrows(IOException.class, () -> SingleplayerLineageStore.open(global, world));

        PrestigeContracts.writeLineage(context.stateRoot().resolve("lineage-v5.tsv"),
                new PrestigeContracts.Lineage(context.lineage().lineageId(), 1, 1));
        assertThrows(IllegalStateException.class, () -> SingleplayerLineageStore.open(global, world));
    }
}
