package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineStatsTest {
    @TempDir Path world;

    @Test
    void statArgumentsMapToTheVanillaIds() {
        assertEquals(new OfflineStats.Key("minecraft:custom", "minecraft:play_time"), OfflineStats.key("PLAY_ONE_MINUTE"));
        assertEquals(new OfflineStats.Key("minecraft:custom", "minecraft:jump"), OfflineStats.key("jump"));
        assertEquals(new OfflineStats.Key("minecraft:custom", "minecraft:open_chest"), OfflineStats.key("CHEST_OPENED"));
        assertEquals(new OfflineStats.Key("minecraft:mined", "minecraft:diamond_ore"), OfflineStats.key("MINE_BLOCK:DIAMOND_ORE"));
        assertEquals(new OfflineStats.Key("minecraft:killed", "minecraft:zombie"), OfflineStats.key("KILL_ENTITY:zombie"));
        assertEquals(new OfflineStats.Key("minecraft:picked_up", "minecraft:diamond"), OfflineStats.key("PICKUP:DIAMOND"));
        assertThrows(IllegalArgumentException.class, () -> OfflineStats.key("MINE_BLOCK"));
        assertThrows(IllegalArgumentException.class, () -> OfflineStats.key("NOT_A_STAT"));
    }

    @Test
    void readsValuesFromTheStatsFile() throws IOException {
        Path folder = world.resolve("players").resolve("stats");
        Files.createDirectories(folder);
        Files.createDirectories(world.resolve("stats"));
        assertEquals(folder, OfflineStats.folder(world), "26.1+ layout wins");
        UUID alex = UUID.randomUUID();
        Files.writeString(folder.resolve(alex + ".json"), """
                {"stats":{"minecraft:custom":{"minecraft:play_time":72000,"minecraft:jump":5},
                 "minecraft:mined":{"minecraft:diamond_ore":3}},"DataVersion":4000}
                """);
        OfflineStats.Key play = OfflineStats.key("PLAY_ONE_MINUTE"), ore = OfflineStats.key("MINE_BLOCK:DIAMOND_ORE"), kills = OfflineStats.key("KILL_ENTITY:ZOMBIE");
        Map<OfflineStats.Key, Long> values = OfflineStats.read(folder, alex, List.of(play, ore, kills));
        assertEquals(72000L, values.get(play));
        assertEquals(3L, values.get(ore));
        assertEquals(null, values.get(kills), "never killed one");
        assertTrue(OfflineStats.read(folder, UUID.randomUUID(), List.of(play)).isEmpty(), "no file, no values");
        UUID broken = UUID.randomUUID();
        Files.writeString(folder.resolve(broken + ".json"), "{\"stats\":{");
        assertThrows(IOException.class, () -> OfflineStats.read(folder, broken, List.of(play)));
    }

    @Test
    void olderWorldsKeepStatsNextToTheLevel() {
        assertEquals(world.resolve("stats"), OfflineStats.folder(world));
    }
}
