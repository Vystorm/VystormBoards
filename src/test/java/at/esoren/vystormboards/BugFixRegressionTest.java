package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import Vystorm.vystorm_core.display.ClientDisplays;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.schedule.TaskGroup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BugFixRegressionTest {
    @TempDir Path dir;

    static void set(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    static Object get(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    static Object call(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        var method = object.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(object, args); }
        catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    private BoardsPlugin plugin() throws Exception { return reloadable(dir); }

    /** A mocked plugin whose real load() can run, with its files in {@code dir}. */
    static BoardsPlugin reloadable(Path dir) throws Exception {
        BoardsPlugin p = mock(BoardsPlugin.class);
        when(p.getLogger()).thenReturn(mock(Logger.class));
        YamlConfiguration config = new YamlConfiguration();
        config.set("placeholderapi", false);
        config.set("leaderboards.enabled", false);
        when(p.getConfig()).thenReturn(config);
        set(p, "lang", mock(Lang.class));
        set(p, "core", TestCore.api());
        set(p, "boardsFile", dir.resolve("boards.yml").toFile());
        set(p, "leaderboardsFile", dir.resolve("leaderboards.yml").toFile());
        set(p, "pollsFile", dir.resolve("polls.yml").toFile());
        for (String field : List.of("boards", "tilesByMap", "lastClick", "rankingTops", "objectiveCache")) set(p, field, new LinkedHashMap<>());
        for (String field : List.of("unreadable", "sampled", "badSources", "boardPolls")) set(p, field, new HashSet<>());
        set(p, "leaderboards", new Leaderboards());
        set(p, "polls", new Polls());
        set(p, "cooldowns", new ClickCooldowns());
        set(p, "navigation", new PageNavigation());
        set(p, "tasks", mock(TaskGroup.class));
        set(p, "assets", mock(Assets.class));
        set(p, "composer", mock(Composer.class));
        set(p, "boardKey", new NamespacedKey("vystormboards", "board"));
        return p;
    }

    private Board board(String name, String content) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(content);
        return Board.read(name, yaml);
    }

    @Test void failedDataWritesRemainDirtyAndAreRetried() throws Exception {
        BoardsPlugin p = plugin();
        Leaderboards rankings = (Leaderboards) get(p, "leaderboards");
        Polls polls = (Polls) get(p, "polls");
        rankings.put("stat:JUMP", UUID.randomUUID(), "Steve", 12, 1, 10);
        polls.vote("event", "pvp", UUID.randomUUID(), true);
        try (var writes = mockStatic(AtomicFiles.class)) {
            writes.when(() -> AtomicFiles.write(any(Path.class), any(YamlConfiguration.class))).thenThrow(new IOException("disk full"));
            call(p, "saveData", new Class<?>[0]);
            assertTrue(rankings.dirty(), "ranking must retry after failed write");
            assertTrue(polls.dirty(), "votes must retry after failed write");
        }
        call(p, "saveData", new Class<?>[0]);
        assertFalse(rankings.dirty());
        assertFalse(polls.dirty());
        assertTrue(Files.readString(dir.resolve("polls.yml")).contains("pvp"));
        assertTrue(Files.readString(dir.resolve("leaderboards.yml")).contains("Steve"));
    }

    @Test void disabledOrInvalidVoteButtonsNeverRunRewards() throws Exception {
        for (String vote : List.of("event:pvp", "invalid")) {
            BoardsPlugin p = plugin();
            set(p, "pollsOn", vote.equals("invalid"));
            Board board = board("demo", "clicks: [{vote: '" + vote + "', command: reward}]");
            Player viewer = mock(Player.class);
            when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
            when(viewer.getName()).thenReturn("Steve");
            when(viewer.getEyeLocation()).thenReturn(new Location(null, .5, .5, -2, 0, 0));
            BoardRuntime runtime = new BoardRuntime(board, (b, v) -> "", (b, v) -> new byte[16384]);
            call(p, "click", new Class<?>[]{Player.class, BoardRuntime.class}, viewer, runtime);
            verify(viewer, never()).performCommand(anyString());
            assertEquals(0, ((Polls) get(p, "polls")).total("event"));
        }
    }

    @Test void createCannotOverwriteABoardSkippedDuringLoad() throws Exception {
        BoardsPlugin p = plugin();
        String original = "boards:\n  Demo:\n    world: unloaded\n    elements: [{text: precious}]\n";
        Files.writeString(dir.resolve("boards.yml"), original);
        Player sender = mock(Player.class);
        doCallRealMethod().when(p).create(any(), any());
        p.create(sender, new String[]{"create", "demo", "1", "1"});
        verify((Lang) get(p, "lang")).send(sender, "exists", "name", "demo");
        verify(sender, never()).getTargetBlockExact(anyInt());
        assertEquals(original, Files.readString(dir.resolve("boards.yml")));
    }

    @Test void failedRemovalDoesNotTouchFramesOrReportSuccess() throws Exception {
        BoardsPlugin p = plugin();
        p.getConfig().set("render-mode", "entities");
        Board board = board("demo", "maps: [7]");
        @SuppressWarnings("unchecked") Map<String, BoardRuntime> boards = (Map<String, BoardRuntime>) get(p, "boards");
        boards.put("demo", new BoardRuntime(board, (b, v) -> "", (b, v) -> new byte[16384]));
        Files.writeString(dir.resolve("boards.yml"), "boards:\n  demo:\n    maps: [7]\n");
        World world = mock(World.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(mock(Block.class));
        CommandSender sender = mock(CommandSender.class);
        doCallRealMethod().when(p).remove(any(), any());
        try (var bukkit = mockStatic(Bukkit.class); var writes = mockStatic(AtomicFiles.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            writes.when(() -> AtomicFiles.write(any(Path.class), any(YamlConfiguration.class))).thenThrow(new IOException("disk full"));
            p.remove(sender, new String[]{"remove", "demo"});
            verifyNoInteractions(world);
            verify((Lang) get(p, "lang")).send(sender, "failed");
            assertEquals(1, boards.size());
        }
    }

    @Test void failedCreationDoesNotSpawnOrphanFrames() throws Exception {
        BoardsPlugin p = plugin();
        World world = mock(World.class);
        Block wall = mock(Block.class), spot = mock(Block.class);
        when(world.getBlockAt(0, 64, 0)).thenReturn(wall);
        when(world.getBlockAt(0, 64, -1)).thenReturn(spot);
        when(wall.getWorld()).thenReturn(world);
        when(wall.getRelative(BlockFace.NORTH)).thenReturn(spot);
        Material solid = mock(Material.class); when(solid.isSolid()).thenReturn(true);
        when(wall.getType()).thenReturn(solid);
        when(spot.getWorld()).thenReturn(world);
        when(spot.getX()).thenReturn(0); when(spot.getY()).thenReturn(64); when(spot.getZ()).thenReturn(-1);
        when(spot.getRelative(BlockFace.SOUTH)).thenReturn(wall);
        when(spot.isPassable()).thenReturn(true);
        MapView view = mock(MapView.class);
        when(view.getId()).thenReturn(7);
        CommandSender sender = mock(CommandSender.class);
        doCallRealMethod().when(p).create(any(), any());
        try (var bukkit = mockStatic(Bukkit.class); var writes = mockStatic(AtomicFiles.class);
             var items = mockConstruction(org.bukkit.inventory.ItemStack.class, (item, context) ->
                     when(item.getItemMeta()).thenReturn(mock(org.bukkit.inventory.meta.MapMeta.class)))) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(() -> Bukkit.createMap(world)).thenReturn(view);
            writes.when(() -> AtomicFiles.write(any(Path.class), any(YamlConfiguration.class))).thenThrow(new IOException("disk full"));
            p.create(sender, new String[]{"create", "demo", "1", "1", "world", "0", "64", "0", "north"});
            verify(world, never()).spawn(any(Location.class), any(Class.class), any(java.util.function.Consumer.class));
            verify((Lang) get(p, "lang")).send(sender, "failed");
        }
    }

    @Test void perBoardPacketFailureAllocatesAndPersistsEntityMaps() throws Exception {
        BoardsPlugin p = plugin();
        Files.writeString(dir.resolve("boards.yml"), "boards:\n  demo:\n    world: world\n    maps: []\n");
        PacketBoards packets = mock(PacketBoards.class);
        set(p, "packets", packets);
        p.getConfig().set("packet-view-distance", Double.NaN);
        World world = mock(World.class);
        MapView map = mock(MapView.class);
        when(map.getId()).thenReturn(42);
        try (var core = mockStatic(CoreApi.class); var displays = mockStatic(ClientDisplays.class); var bukkit = mockStatic(Bukkit.class)) {
            core.when(() -> CoreApi.has(CoreApi.Capability.CLIENT_DISPLAYS)).thenReturn(true);
            displays.when(ClientDisplays::supported).thenReturn(true);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(() -> Bukkit.createMap(world)).thenReturn(map);
            bukkit.when(() -> Bukkit.getMap(42)).thenReturn(map);
            call(p, "load", new Class<?>[0]);
            verify(packets).open(any(), eq(48.0), any());
            verify(map).addRenderer(any(BoardRuntime.Tile.class));
            assertEquals(List.of(42), YamlConfiguration.loadConfiguration(dir.resolve("boards.yml").toFile()).getIntegerList("boards.demo.maps"));
        }
    }

    @Test void failedMapIdPersistenceDoesNotAttachUnsavedMaps() throws Exception {
        BoardsPlugin p = plugin(); p.getConfig().set("render-mode", "entities");
        String original = "boards:\n  demo:\n    world: world\n    maps: []\n";
        Files.writeString(dir.resolve("boards.yml"), original);
        World world = mock(World.class); MapView map = mock(MapView.class); when(map.getId()).thenReturn(42);
        try (var bukkit = mockStatic(Bukkit.class); var writes = mockStatic(AtomicFiles.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(() -> Bukkit.createMap(world)).thenReturn(map);
            bukkit.when(() -> Bukkit.getMap(42)).thenReturn(map);
            writes.when(() -> AtomicFiles.write(any(Path.class), any(YamlConfiguration.class))).thenThrow(new IOException("disk full"));
            call(p, "load", new Class<?>[0]);
            verify(map, never()).addRenderer(any());
            assertEquals(original, Files.readString(dir.resolve("boards.yml")));
        }
    }

    @Test void mapInitializationRestoresFallbackBoardsWhilePacketModeIsActive() throws Exception {
        BoardsPlugin p = plugin(); set(p, "packetMode", true); set(p, "packets", mock(PacketBoards.class));
        Board board = board("demo", "maps: [7]");
        @SuppressWarnings("unchecked") Map<String, BoardRuntime> boards = (Map<String, BoardRuntime>) get(p, "boards");
        boards.put("demo", new BoardRuntime(board, (b, v) -> "", (b, v) -> new byte[16384]));
        MapView map = mock(MapView.class); when(map.getId()).thenReturn(7);
        doCallRealMethod().when(p).onMapLoad(any());
        MapInitializeEvent event = mock(MapInitializeEvent.class); when(event.getMap()).thenReturn(map);
        p.onMapLoad(event);
        verify(map).addRenderer(any(BoardRuntime.Tile.class));
    }

    @Test void mixedCaseBoardTagsResolveTheirRuntime() throws Exception {
        BoardsPlugin p = plugin();
        BoardRuntime runtime = new BoardRuntime(board("Demo", "{}"), (b, v) -> "", (b, v) -> new byte[16384]);
        @SuppressWarnings("unchecked") Map<String, BoardRuntime> boards = (Map<String, BoardRuntime>) get(p, "boards");
        boards.put("demo", runtime);
        ItemFrame frame = mock(ItemFrame.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(frame.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn("Demo");
        assertSame(runtime, call(p, "boardOf", new Class<?>[]{org.bukkit.entity.Entity.class}, frame));
    }

    @Test void staleOfflineSeedCannotOverwriteANewerOnlineSample() {
        Leaderboards rankings = new Leaderboards(); UUID id = UUID.randomUUID();
        rankings.put("stat:JUMP", id, "Steve", 100, 2000, 10);
        rankings.put("stat:JUMP", id, "Steve", 20, 1000, 10);
        assertEquals(100, rankings.ranking("stat:JUMP", false).getFirst().value());
    }

    @Test void reducedRankingLimitPrunesAllExcessEntriesEvenWhenValueIsUnchanged() {
        Leaderboards rankings = new Leaderboards(); UUID newest = UUID.randomUUID();
        for (int i = 0; i < 5; i++) rankings.put("stat:JUMP", UUID.randomUUID(), "Old" + i, i, i, 10);
        rankings.put("stat:JUMP", newest, "Steve", 100, 10, 10);
        rankings.ranking("stat:JUMP", false);
        rankings.put("stat:JUMP", newest, "Steve", 100, 20, 2);
        assertEquals(2, rankings.size("stat:JUMP"));
        assertEquals(List.of("Steve", "Old4"), rankings.ranking("stat:JUMP", false).stream().map(Leaderboards.Ranked::name).toList());
    }

    @Test void newlyOpenedPollIsPersistedWithoutVotes() {
        Polls polls = new Polls();
        polls.setClosed("event", false);
        assertTrue(polls.dirty());
        Polls loaded = new Polls(); loaded.load(polls.save());
        assertTrue(loaded.exists("event"));
    }

    @Test void pollPlaceholdersAreConsistentlyCaseInsensitive() {
        Polls polls = new Polls(); UUID id = UUID.randomUUID(); polls.vote("event", "pvp", id, true);
        assertEquals("1 pvp", polls.apply("{VOTES:EVENT} {VOTE:EVENT}", id));
    }

    @Test void editorFindsMixedCaseBoardNamesFromCommandCompletion() throws Exception {
        BoardDraft draft = new BoardDraft("demo", "boards:\n  Demo:\n    pages: [[{text: old}]]\n");
        assertTrue(draft.text(0, 0, "new"));
        YamlConfiguration yaml = new YamlConfiguration(); yaml.loadFromString(draft.yaml());
        assertEquals(Set.of("Demo"), yaml.getConfigurationSection("boards").getKeys(false));
    }

    @Test void editorPageChangesKeepWhitespacePaddedNavigationTargetsBound() throws Exception {
        BoardDraft draft = new BoardDraft("demo", "boards:\n  demo:\n    pages: [[], [], []]\n    clicks: [{navigate: ' 2 '}]\n");
        draft.movePage(1, 2);
        assertEquals("3", draft.board().clicks.getFirst().navigate());
        draft.removePage(2);
        assertTrue(draft.board().clicks.isEmpty());
    }

    @Test void fallbackBoardRestoresFramesWhenAnEmptyChunkLoads() throws Exception {
        BoardsPlugin p = plugin(); set(p, "packetMode", true); set(p, "packets", mock(PacketBoards.class));
        @SuppressWarnings("unchecked") Map<String, BoardRuntime> boards = (Map<String, BoardRuntime>) get(p, "boards");
        boards.put("demo", new BoardRuntime(board("demo", "maps: [7]"), (b, v) -> "", (b, v) -> new byte[16384]));
        World world = mock(World.class); when(world.getName()).thenReturn("world");
        Chunk chunk = mock(Chunk.class); when(chunk.getWorld()).thenReturn(world);
        var event = mock(org.bukkit.event.world.EntitiesLoadEvent.class);
        when(event.getChunk()).thenReturn(chunk);
        doCallRealMethod().when(p).onEntitiesLoad(any());
        call(p, "onEntitiesLoad", new Class<?>[]{org.bukkit.event.world.EntitiesLoadEvent.class}, event);
        verify((TaskGroup) get(p, "tasks")).later(eq(1L), any(Runnable.class));
    }

    @Test void unchangedRankingValuesStillPersistLastSeenForEviction() {
        Leaderboards rankings = new Leaderboards(); UUID id = UUID.randomUUID();
        rankings.put("stat:JUMP", id, "Steve", 10, 1, 10); rankings.save();
        rankings.put("stat:JUMP", id, "Steve", 10, 2, 10);
        assertTrue(rankings.dirty());
    }

    @Test void previewPagePlaceholderUsesTheDraftPage() throws Exception {
        BoardsPlugin p = plugin();
        when(p.placeholders(anyString(), any(), any())).thenCallRealMethod();
        Player viewer = mock(Player.class); World world = mock(World.class);
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID()); when(viewer.getName()).thenReturn("Steve");
        when(viewer.getWorld()).thenReturn(world); when(world.getName()).thenReturn("world");
        Board board = board("demo", "pages: [[], [], []]");
        Composer composer = (Composer) get(p, "composer");
        when(composer.render(eq(board), any(), eq("Steve"), any(), anyLong(), eq(1))).thenAnswer(invocation -> {
            java.util.function.UnaryOperator<String> placeholders = invocation.getArgument(1);
            assertEquals("2/3", placeholders.apply("{page}/{pages}")); return new int[16384];
        });
        ((PageNavigation) get(p, "navigation")).navigate(board, viewer.getUniqueId(), "1", 0);
        doCallRealMethod().when(p).editorPixels(any(), any(), anyInt());
        try (var bukkit = mockStatic(Bukkit.class)) { p.editorPixels(board, viewer, 1); }
    }

    @Test void unreadablePollStorageDoesNotAcceptVotesOrRewards() throws Exception {
        BoardsPlugin p = plugin(); set(p, "pollsOn", true);
        @SuppressWarnings("unchecked") Set<java.io.File> unreadable = (Set<java.io.File>) get(p, "unreadable");
        unreadable.add(dir.resolve("polls.yml").toFile());
        Player viewer = mock(Player.class); when(viewer.getUniqueId()).thenReturn(UUID.randomUUID()); when(viewer.getName()).thenReturn("Steve");
        when(viewer.getEyeLocation()).thenReturn(new Location(null, .5, .5, -2, 0, 0));
        Board board = board("demo", "clicks: [{vote: 'event:pvp', command: reward}]");
        BoardRuntime runtime = new BoardRuntime(board, (b, v) -> "", (b, v) -> new byte[16384]);
        call(p, "click", new Class<?>[]{Player.class, BoardRuntime.class}, viewer, runtime);
        verify(viewer, never()).performCommand(anyString());
        assertEquals(0, ((Polls) get(p, "polls")).total("event"));
    }

    @Test void disablingEditorClosesItsInventories() throws Exception {
        BoardsPlugin p = plugin();
        BoardEditor editor = new BoardEditor(p, TestCore.api(), dir.resolve("boards.yml"));
        Class<?> holderClass = Class.forName(BoardEditor.class.getName() + "$Holder");
        var constructor = holderClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        var holder = (org.bukkit.inventory.InventoryHolder) constructor.newInstance(UUID.randomUUID(), null);
        var inventory = mock(org.bukkit.inventory.Inventory.class); when(inventory.getHolder()).thenReturn(holder);
        Player viewer = mock(Player.class); var view = mock(org.bukkit.inventory.InventoryView.class);
        when(viewer.getOpenInventory()).thenReturn(view);
        try (var bukkit = mockStatic(Bukkit.class); var compat = mockStatic(Vystorm.vystorm_core.compat.InventoryCompat.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(viewer));
            compat.when(() -> Vystorm.vystorm_core.compat.InventoryCompat.top(view)).thenReturn(inventory);
            editor.close();
            verify(viewer).closeInventory();
        }
    }

    @Test void expiredCooldownsOfDisconnectedPlayersAreEventuallyReleased() {
        ClickCooldowns cooldowns = new ClickCooldowns(); UUID departed = UUID.randomUUID(), active = UUID.randomUUID();
        cooldowns.start(departed, "reward", 1000, 1);
        cooldowns.prune(departed, 1500);
        assertTrue(cooldowns.tracks(departed));
        cooldowns.start(active, "reward", 62000, 1);
        assertFalse(cooldowns.tracks(departed));
        assertEquals(1, cooldowns.remaining(active, "reward", 62000));
    }

    @Test void unreadableStorageRejectsAdministrativeChanges() throws Exception {
        BoardsPlugin p = plugin(); set(p, "pollsOn", true); set(p, "rankingsOn", true);
        @SuppressWarnings("unchecked") Set<java.io.File> unreadable = (Set<java.io.File>) get(p, "unreadable");
        unreadable.add(dir.resolve("polls.yml").toFile()); unreadable.add(dir.resolve("leaderboards.yml").toFile());
        Polls polls = (Polls) get(p, "polls"); UUID id = UUID.randomUUID(); polls.vote("event", "pvp", id, true);
        polls.deadline("event", 10_000);
        Leaderboards rankings = (Leaderboards) get(p, "leaderboards"); rankings.put("stat:JUMP", id, "Steve", 10, 1, 10);
        CommandSender sender = mock(CommandSender.class);
        doCallRealMethod().when(p).poll(any(), any()); doCallRealMethod().when(p).deadline(any(), any()); doCallRealMethod().when(p).top(any(), any());
        p.poll(sender, new String[]{"poll", "event", "reset", "confirm"});
        p.deadline(sender, new String[]{"deadline", "event", "clear"});
        p.top(sender, new String[]{"top", "stat:JUMP", "reset", "confirm"});
        assertEquals(1, polls.total("event")); assertEquals(10_000, polls.deadline("event")); assertEquals(1, rankings.size("stat:JUMP"));
        verify((Lang) get(p, "lang"), times(3)).send(sender, "failed");
    }
}
