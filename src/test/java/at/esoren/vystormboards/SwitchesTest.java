package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._advancedMethods.packageHandler.PackageInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import Vystorm.vystorm_core._uiMethods.settings.ConfigEditor;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.lang.Lang;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;

/**
 * Every feature has a switch (1.19.0): off stops the feature and answers the one shared message, on behaves as before,
 * an old config.yml gains the new keys, and the placeholders resolve and are empty while their feature is off.
 */
class SwitchesTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private static final NamespacedKey KEY = NamespacedKey.minecraft("board");
    @TempDir Path dir;

    // ------------------------------------------------------------------ config, descriptions, old configs

    @Test void givenPackagedConfig_whenRead_thenEverySwitchIsOnAndExplained() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        for (Feature feature : Feature.values()) {
            assertTrue(config.isBoolean(feature.path), feature.path);
            assertTrue(config.getBoolean(feature.path), feature.path);
            String parent = feature.path.contains(".") ? feature.path.substring(0, feature.path.lastIndexOf('.')) : feature.path;
            assertFalse(config.getComments(feature.path).isEmpty() && config.getComments(parent).isEmpty(), "comment for " + feature.path);
        }
    }

    @Test void givenEveryLanguage_whenRead_thenEverySwitchAndPlaceholderHasADescriptionAndTheOffMessageExists() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            assertTrue(lang.isString("feature-off"), code);
            assertTrue(lang.getString("feature-needs").contains("{switch}"), code);
            for (Feature feature : Feature.values()) assertFalse(lang.getString(feature.description(), "").isBlank(), code + " " + feature.description());
            for (String key : List.of("votes", "votes_percent", "vote", "poll_open", "guestbook_count", "guestbook_name", "guestbook_text",
                    "rank", "rank_value", "top_name", "top_value", "board_count"))
                assertFalse(lang.getString("placeholder." + key, "").isBlank(), code + " placeholder." + key);
        }
        assertEquals("<red>This feature is switched off on this server.", YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/en.yml")).getString("feature-off"));
    }

    @Test void givenAConfigFrom1_18_whenRead_thenEveryFeatureStaysOn() {
        YamlConfiguration old = YamlConfiguration.loadConfiguration(new File("src/test/resources/config-1.18.yml"));
        assertFalse(old.contains("boards.enabled"), "the old file has no master switch");
        assertEquals(Set.of(), Feature.Switches.read(old).off());
        assertEquals(Set.of(Feature.POLLS, Feature.VOTE_CHANGE), Feature.Switches.read(yaml("polls: {enabled: false, allow-change: false}")).off());
    }

    @Test void givenAConfigFrom1_18_whenCoreMergesDefaults_thenEveryNewSwitchIsAddedAndOldValuesStay() throws Exception {
        Path data = Files.createDirectories(dir.resolve("VystormBoards"));
        String old = Files.readString(Path.of("src/test/resources/config-1.18.yml")).replace("allow-change: true", "allow-change: false");
        Files.writeString(data.resolve("config.yml"), old);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("VystormBoards");
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getResource("config.yml")).thenAnswer(call -> Files.newInputStream(Path.of("src/main/resources/config.yml")));
        YamlConfiguration live = YamlConfiguration.loadConfiguration(data.resolve("config.yml").toFile());
        when(plugin.getConfig()).thenReturn(live);

        int added = ConfigEditor.mergeDefaults(plugin);

        YamlConfiguration merged = YamlConfiguration.loadConfiguration(data.resolve("config.yml").toFile());
        assertTrue(added >= 14, "added " + added);
        for (Feature feature : Feature.values()) assertTrue(merged.isBoolean(feature.path), feature.path);
        assertFalse(merged.getBoolean("polls.allow-change"), "an existing value is never changed");
        assertTrue(Files.readString(data.resolve("config.yml")).contains("The whole plugin"), "comments come along");
        assertEquals(Set.of(Feature.VOTE_CHANGE), Feature.Switches.read(merged).off());
        assertTrue(live.isBoolean("boards.enabled"), "the in-memory config gains the keys too");
    }

    // ------------------------------------------------------------------ master switch and commands

    private BoardsPlugin plugin(Feature... off) throws Exception {
        BoardsPlugin p = mock(BoardsPlugin.class);
        set(p, "lang", mock(Lang.class));
        set(p, "switches", Feature.Switches.without(off));
        set(p, "boards", new LinkedHashMap<String, BoardRuntime>());
        set(p, "polls", new Polls());
        set(p, "rankingTops", new LinkedHashMap<String, Board.Top>());
        set(p, "pollsOn", true);
        set(p, "rankingsOn", true);
        return p;
    }

    private static Lang lang(BoardsPlugin p) throws Exception { return (Lang) get(p, "lang"); }

    @Test void givenBoardsOff_whenAnyBoardCommandRuns_thenItAnswersTheOffMessageAndDoesNothingElse() throws Exception {
        BoardsPlugin p = plugin(Feature.BOARDS);
        CommandSender sender = mock(CommandSender.class);
        doCallRealMethod().when(p).list(any());
        doCallRealMethod().when(p).create(any(), any());
        doCallRealMethod().when(p).info(any(), any());
        doCallRealMethod().when(p).remove(any(), any());
        doCallRealMethod().when(p).render(any());
        doCallRealMethod().when(p).guestbook(any(), any(), any(), any());
        doCallRealMethod().when(p).photos(any(), any(), any());
        p.list(sender);
        p.create(sender, new String[]{"create", "demo", "1", "1"});
        p.info(sender, new String[]{"info", "demo"});
        p.remove(sender, new String[]{"remove", "demo"});
        p.render(sender);
        p.guestbook(sender, null, null, null);
        p.photos(sender, "demo", "gallery");
        verify(lang(p), times(7)).send(sender, "feature-off");
        verifyNoMoreInteractions(lang(p));
    }

    @Test void givenBoardsOn_whenListing_thenTheListIsShown() throws Exception {
        BoardsPlugin p = plugin();
        CommandSender sender = mock(CommandSender.class);
        doCallRealMethod().when(p).list(any());
        p.list(sender);
        verify(lang(p)).send(sender, "list-header", "count", "0");
    }

    @Test void givenSwitchesOff_whenTheCommandTreeIsBuilt_thenTheirSubcommandsAreHiddenAndReloadStays() throws Exception {
        BoardsPlugin p = plugin();
        CommandSpec tree = BoardsPlugin.commandSpec(p);
        assertTrue(tree.children().values().stream().allMatch(sub -> sub.visibleTo(null)));
        assertTrue(tree.children().values().stream().noneMatch(CommandSpec::hidden));
        // the same built tree follows the switch: no second registration needed
        set(p, "switches", Feature.Switches.without(Feature.POLLS));
        assertFalse(tree.children().get("poll").visibleTo(null));
        assertFalse(tree.children().get("deadline").visibleTo(null));
        assertTrue(tree.children().get("guestbook").visibleTo(null));
        set(p, "switches", Feature.Switches.without(Feature.BOARDS));
        for (var sub : tree.children().entrySet()) assertEquals(sub.getKey().equals("reload"), sub.getValue().visibleTo(null), sub.getKey());
        set(p, "switches", Feature.Switches.without());
        assertTrue(tree.children().values().stream().allMatch(sub -> sub.visibleTo(null)));
    }

    @Test void givenASwitchChanged_whenReloaded_thenTheCommandListIsSentAgainAndNothingIsRegistered() throws Exception {
        BoardsPlugin p = BugFixRegressionTest.reloadable(dir);
        p.getConfig().set("render-mode", "entities");
        API core = (API) get(p, "core");
        var commands = mock(Vystorm.vystorm_core._uiMethods.commands.CommandInterface.class);
        when(core.command()).thenReturn(commands);
        call(p, "load", new Class<?>[0]);
        verify(commands, never()).refresh();
        call(p, "load", new Class<?>[0]);
        verify(commands, never()).refresh();
        p.getConfig().set(Feature.POLLS.path, false);
        call(p, "load", new Class<?>[0]);
        verify(commands).refresh();
        verify(commands, never()).register(any(), any(), any());
    }

    @Test void givenRenderDiagnosticsOff_whenRender_thenOffMessage() throws Exception {
        BoardsPlugin p = plugin(Feature.RENDER_INFO);
        CommandSender sender = mock(CommandSender.class);
        doCallRealMethod().when(p).render(any());
        p.render(sender);
        verify(lang(p)).send(sender, "feature-off");
        verifyNoMoreInteractions(lang(p));
    }

    @Test void givenRenderDiagnosticsOn_whenRender_thenStatusShown() throws Exception {
        BoardsPlugin p = plugin();
        set(p, "renderMode", "entities");
        CommandSender sender = mock(CommandSender.class);
        doCallRealMethod().when(p).render(any());
        p.render(sender);
        verify(lang(p)).send(sender, "render-status", "mode", "entities", "configured", "entities", "reason", "");
    }

    // ------------------------------------------------------------------ polls: deadlines

    @Test void givenDeadlinesOff_whenTheDeadlinePassed_thenThePollStaysOpenAndTheCommandIsOff() throws Exception {
        long[] now = {1_000};
        Polls polls = new Polls(() -> now[0]);
        polls.deadline("event", 2_000);
        now[0] = 3_000;
        assertTrue(polls.closed("event"));
        polls.deadlines(false);
        assertFalse(polls.closed("event"));
        assertEquals(Polls.Result.CAST, polls.vote("event", "pvp", UUID.randomUUID(), true));
        assertEquals(2_000, polls.deadline("event"), "the stored deadline is kept");
        polls.deadlines(true);
        assertTrue(polls.closed("event"));

        BoardsPlugin p = plugin(Feature.POLL_DEADLINES);
        CommandSender sender = mock(CommandSender.class);
        doCallRealMethod().when(p).deadline(any(), any());
        p.deadline(sender, new String[]{"deadline", "event", "clear"});
        verify(lang(p)).send(sender, "feature-off");
        verifyNoMoreInteractions(lang(p));
    }

    // ------------------------------------------------------------------ frames, clicks

    private BoardsPlugin clickable(Feature... off) throws Exception {
        BoardsPlugin p = mock(BoardsPlugin.class, CALLS_REAL_METHODS);
        Board board = Board.read("demo", yaml("world: world\nx: 0\ny: 0\nz: 0\nwidth: 1\nheight: 1\nclicks: [{x: 0, y: 0, width: 128, height: 128, command: reward}]\n"));
        Map<String, BoardRuntime> shown = new LinkedHashMap<>();
        shown.put("demo", new BoardRuntime(board, (b, v) -> "", (b, v) -> new byte[16384]));
        set(p, "boardKey", KEY);
        set(p, "boards", shown);
        set(p, "lastClick", new HashMap<UUID, Long>());
        set(p, "lastDrop", new HashMap<UUID, Long>());
        set(p, "cooldowns", new ClickCooldowns());
        set(p, "navigation", new PageNavigation());
        set(p, "core", TestCore.api());
        set(p, "lang", mock(Lang.class));
        set(p, "switches", Feature.Switches.without(off));
        doReturn("VystormBoards").when(p).getName();
        doReturn(true).when(p).isEnabled();
        return p;
    }

    private static ItemFrame boardFrame() {
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(data.has(KEY, PersistentDataType.STRING)).thenReturn(true);
        when(data.get(KEY, PersistentDataType.STRING)).thenReturn("demo");
        ItemFrame frame = mock(ItemFrame.class);
        when(frame.getPersistentDataContainer()).thenReturn(data);
        return frame;
    }

    private static Player lookingAt(ItemFrame frame) {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Steve");
        when(player.getWorld()).thenReturn(world);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getEyeLocation()).thenReturn(new Location(world, 0.75, 0.5, -2, 0, 0));
        when(world.rayTraceEntities(any(Location.class), any(Vector.class), anyDouble(), ArgumentMatchers.<Predicate<? super Entity>>any()))
                .thenReturn(new RayTraceResult(new Vector(0.75, 0.5, 0.9375), frame));
        return player;
    }

    @Test void givenFrameProtectionOff_whenABoardFrameBreaks_thenNothingStopsIt() throws Exception {
        ItemFrame frame = boardFrame();
        for (boolean on : List.of(true, false)) {
            BoardsPlugin p = on ? clickable() : clickable(Feature.FRAME_PROTECTION);
            EventStageTesting stages = EventStageTesting.detached();
            p.handleSharedEvents(stages.stages());
            assertEquals(on, stages.fire(new HangingBreakEvent(frame, HangingBreakEvent.RemoveCause.EXPLOSION)).isCancelled());
        }
    }

    @Test void givenClickAreasOff_whenAPlayerClicksTheBoard_thenOffMessageAndNoCommand() throws Exception {
        ItemFrame frame = boardFrame();
        for (boolean on : List.of(true, false)) {
            BoardsPlugin p = on ? clickable() : clickable(Feature.CLICKS);
            EventStageTesting stages = EventStageTesting.detached();
            p.handleSharedEvents(stages.stages());
            Player player = lookingAt(frame);
            stages.fire(new PlayerAnimationEvent(player, PlayerAnimationType.ARM_SWING));
            verify(player, times(on ? 1 : 0)).performCommand("reward");
            verify(lang(p), times(on ? 0 : 1)).send(player, "feature-off");
        }
    }

    @Test void givenPagesOrPerPlayerOff_whenAPlayerUsesAPageButton_thenItSaysWhichSwitchItNeeds() throws Exception {
        for (Feature needed : List.of(Feature.PAGES, Feature.PER_PLAYER)) {
            BoardsPlugin p = clickable(needed);
            Board board = Board.read("demo", yaml("world: world\nwidth: 1\nheight: 1\npages: [[{type: rect}], [{type: rect}]]\n"
                    + "clicks: [{x: 0, y: 0, width: 128, height: 128, navigate: next}]\n"));
            BoardRuntime runtime = new BoardRuntime(board, (b, v) -> "", (b, v) -> new byte[16384]);
            Player player = lookingAt(boardFrame());
            call(p, "click", new Class<?>[]{Player.class, BoardRuntime.class}, player, runtime);
            verify(lang(p)).send(player, "feature-needs", "switch", needed.path);
        }
    }

    // ------------------------------------------------------------------ drawing

    private static Composer composer(Feature... off) {
        BufferedImage picture = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++) picture.setRGB(x, y, 0xFF0000);
        Composer composer = new Composer(new Composer.Images() {
            @Override public BufferedImage fitted(String file, int frame, int width, int height, boolean dither) { return picture; }
            @Override public int frames(String file) { return 5; }
        }, name -> picture);
        composer.switches(Feature.Switches.without(off));
        return composer;
    }

    private static boolean drawsSomething(Composer composer, String element) {
        Board board = Board.read("demo", yaml("width: 1\nheight: 1\nbackground: '#000000'\nelements: [" + element + "]\n"));
        for (int pixel : composer.render(board, text -> text, "Steve", permission -> true, 0)) if ((pixel & 0xFFFFFF) != 0) return true;
        return false;
    }

    @Test void givenAnElementTypeOff_whenTheBoardIsDrawn_thenThoseElementsAreLeftOutAndOthersStay() {
        Map<Feature, String> elements = new LinkedHashMap<>();
        elements.put(Feature.IMAGES, "{type: image, file: a.png, width: 8, height: 8}");
        elements.put(Feature.TEXT, "{type: text, text: '<white>HELLO', size: 20}");
        elements.put(Feature.RECTANGLES, "{type: rect, color: '#ffffff', width: 32, height: 32}");
        elements.put(Feature.HEADS, "{type: head, size: 16, player: Steve}");
        elements.put(Feature.PROGRESS, "{type: progress, width: 64, height: 8, value: '5', max: '10', color: '#ffffff'}");
        for (var entry : elements.entrySet()) {
            assertTrue(drawsSomething(composer(), entry.getValue()), "on: " + entry.getKey());
            assertFalse(drawsSomething(composer(entry.getKey()), entry.getValue()), "off: " + entry.getKey());
            Feature other = entry.getKey() == Feature.TEXT ? Feature.IMAGES : Feature.TEXT;
            assertTrue(drawsSomething(composer(other), entry.getValue()), entry.getKey() + " with " + other + " off");
        }
    }

    @Test void givenAnimationsOff_whenAGifIsDrawn_thenOnlyItsFirstFrameShows() {
        Set<Integer> on = new HashSet<>(), off = new HashSet<>();
        for (long t = 0; t < 2_000; t += 100) { on.add(composer().frame("a.gif", t, 10)); off.add(composer(Feature.ANIMATIONS).frame("a.gif", t, 10)); }
        assertTrue(on.size() > 1);
        assertEquals(Set.of(0), off);
    }

    @Test void givenPerPlayerOff_whenABoardHasViewerData_thenItIsDrawnOnceForEveryone() {
        Board board = Board.read("demo", yaml("width: 1\nheight: 1\nelements: [{type: text, text: 'Hi {player}'}]\n"));
        assertTrue(new BoardRuntime(board, (b, v) -> "", (b, v) -> new byte[16384], 20, true).perPlayer());
        assertFalse(new BoardRuntime(board, (b, v) -> "", (b, v) -> new byte[16384], 20, false).perPlayer());
    }

    @Test void givenPagesOff_whenAPageIsChosen_thenItIsAlwaysTheFirst() throws Exception {
        Board board = Board.read("demo", yaml("page-seconds: 1\npages: [[{type: rect}], [{type: rect}], [{type: rect}]]\n"));
        BoardsPlugin on = plugin(), off = plugin(Feature.PAGES);
        set(on, "navigation", new PageNavigation());
        set(off, "navigation", new PageNavigation());
        Class<?>[] types = {Board.class, Player.class, long.class};
        assertEquals(2, call(on, "page", types, board, null, 2_500L));
        assertEquals(0, call(off, "page", types, board, null, 2_500L));
    }

    @Test void givenPlaceholdersAndCountdownsOff_whenATextIsFilled_thenTheyStayAsWritten() throws Exception {
        Board board = Board.read("demo", yaml("width: 1\nheight: 1\n"));
        String text = "{board} {countdown:2999-01-01T00:00}";
        BoardsPlugin off = plugin(Feature.PLACEHOLDERS, Feature.COUNTDOWNS);
        doCallRealMethod().when(off).placeholders(anyString(), any(), any());
        assertEquals(text, off.placeholders(text, null, board));

        BoardsPlugin on = plugin();
        set(on, "navigation", new PageNavigation());
        doCallRealMethod().when(on).placeholders(anyString(), any(), any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            String filled = on.placeholders(text, null, board);
            assertTrue(filled.startsWith("demo "), filled);
            assertFalse(filled.contains("{countdown"), filled);
        }
    }

    // ------------------------------------------------------------------ packet mode, tutorial

    @Test void givenPacketModeOff_whenTheRenderModeIsChosen_thenRealItemFramesAreUsedAndCoreIsNotAsked() throws Exception {
        BoardsPlugin p = plugin(Feature.PACKETS);
        API core = mock(API.class);
        set(p, "core", core);
        when(p.getConfig()).thenReturn(yaml("render-mode: packets\n"));
        call(p, "resolveRenderMode", new Class<?>[0]);
        assertFalse((boolean) get(p, "packetMode"));
        verify(core, never()).packages();

        BoardsPlugin on = plugin();
        API asked = mock(API.class);
        PackageInterface packages = mock(PackageInterface.class);
        when(packages.clientDisplays()).thenReturn(mock(Vystorm.vystorm_core.display.ClientDisplaysInterface.class));
        when(asked.packages()).thenReturn(packages);
        set(on, "core", asked);
        when(on.getConfig()).thenReturn(yaml("render-mode: packets\n"));
        when(on.getLogger()).thenReturn(Logger.getLogger("test"));
        call(on, "resolveRenderMode", new Class<?>[0]);
        verify(asked, atLeastOnce()).packages();
    }

    @Test void givenTutorialOff_whenThePluginStarts_thenNoTutorialIsRegistered() throws Exception {
        for (boolean enabled : List.of(true, false)) {
            BoardsPlugin p = mock(BoardsPlugin.class);
            API core = mock(API.class);
            set(p, "core", core);
            when(p.getConfig()).thenReturn(yaml("tutorial: {enabled: " + enabled + "}\n"));
            doCallRealMethod().when(p).registerTutorial();
            p.registerTutorial();
            verify(core, times(enabled ? 1 : 0)).registerTutorial(p);
        }
        assertTrue(Feature.TUTORIAL.needsRestart());
    }

    // ------------------------------------------------------------------ placeholders

    private static final class Source implements BoardsPlaceholders.Source {
        final Set<Feature> off = EnumSet.noneOf(Feature.class);
        final Polls polls = new Polls();
        final Guestbooks guestbooks = new Guestbooks();
        final List<Leaderboards.Ranked> playtime = List.of(new Leaderboards.Ranked(1, "Alex", 7200), new Leaderboards.Ranked(2, "Steve", 3600));
        @Override public boolean on(Feature feature) { return !off.contains(feature); }
        @Override public Polls polls() { return polls; }
        @Override public Guestbooks guestbooks() { return guestbooks; }
        @Override public Collection<String> rankingSources() { return List.of("stat:PLAY_ONE_MINUTE"); }
        @Override public List<Leaderboards.Ranked> ranking(String source) { return source.equals("stat:PLAY_ONE_MINUTE") ? playtime : List.of(); }
        @Override public String format(String source, double value) { return (long) value + " s"; }
        @Override public int boards() { return 3; }
    }

    @Test void givenRegisteredValues_whenResolved_thenPollsGuestbooksRankingsAndBoardsAnswer() {
        PlaceholderTesting testing = PlaceholderTesting.detached();
        Source source = new Source();
        OfflinePlayer alex = PlaceholderTesting.viewer("Alex");
        source.polls.vote("event", "pvp", alex.getUniqueId(), true);
        source.polls.vote("event", "pve", UUID.randomUUID(), true);
        source.guestbooks.add("lobby", UUID.randomUUID(), "Steve", "hello there", 1);
        BoardsPlaceholders.register(testing.placeholders(), PlaceholderTesting.plugin("VystormBoards"), source, key -> key);

        assertEquals("2 1 50 pvp true", testing.resolve(alex, "%vystorm_boards_votes_event% %vystorm_boards_votes_event_pvp% "
                + "%vystorm_boards_votes_percent_event_pvp% %vystorm_boards_vote_event% %vystorm_boards_poll_open_event%"));
        assertEquals("1 Steve hello there", testing.resolve(alex, "{boards:guestbook_count:lobby} {boards:guestbook_name:lobby:1} {boards:guestbook_text:lobby:1}"));
        assertEquals("1 7200 s Steve 3600 s", testing.resolve(alex, "%vystorm_boards_rank_play_one_minute% %vystorm_boards_rank_value_stat_play_one_minute% "
                + "%vystorm_boards_top_name_play_one_minute_2% %vystorm_boards_top_value_play_one_minute_2%"));
        assertEquals("3", testing.resolve(null, "{boards:board_count}"));
        assertEquals("{boards:votes:event}", testing.resolveOffThread(alex, "{boards:votes:event}"), "server thread only");
    }

    @Test void givenFeaturesOff_whenTheirPlaceholdersAreResolved_thenTheyAreEmpty() {
        PlaceholderTesting testing = PlaceholderTesting.detached();
        Source source = new Source();
        source.polls.vote("event", "pvp", UUID.randomUUID(), true);
        source.guestbooks.add("lobby", UUID.randomUUID(), "Steve", "hello", 1);
        source.off.addAll(List.of(Feature.POLLS, Feature.GUESTBOOKS, Feature.RANKINGS));
        BoardsPlaceholders.register(testing.placeholders(), PlaceholderTesting.plugin("VystormBoards"), source, key -> key);
        OfflinePlayer alex = PlaceholderTesting.viewer("Alex");
        assertEquals("[][][][3]", testing.resolve(alex, "[{boards:votes:event}][{boards:guestbook_text:lobby:1}][{boards:rank:play_one_minute}][{boards:board_count}]"));
        source.off.add(Feature.BOARDS);
        assertEquals("[]", testing.resolve(alex, "[{boards:board_count}]"));
    }

    @Test void givenAGuestbookLineWithPlaceholdersAndTags_whenABoardShowsIt_thenOtherViewersSeeItAsWritten() throws Exception {
        Guestbooks books = new Guestbooks();
        books.add("lobby", UUID.randomUUID(), "Mallory", "%vystorm_core_player% {player} <red>hi", 1);
        BoardsPlugin p = plugin();
        set(p, "guestbooks", books);
        set(p, "navigation", new PageNavigation());
        set(p, "clockOff", false);
        doCallRealMethod().when(p).placeholders(anyString(), any(), any());
        doCallRealMethod().when(p).guestbooksOn();
        Board board = Board.read("demo", yaml("width: 1\nheight: 1\n"));
        Player viewer = mock(Player.class);
        when(viewer.getName()).thenReturn("Alex");
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(viewer.getWorld()).thenReturn(world);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            String shown = p.placeholders("{player}: {guestbook:lobby:1:text}", viewer, board);
            assertEquals("Alex: %vystorm_core_player% {player} red hi", shown);
        }
        // the board text goes through StyledText (MiniMessage): the line has no tags left to parse
        assertEquals("%vystorm_core_player% {player} red hi", StyledText.plain(StyledText.parse(Guestbooks.clean("%vystorm_core_player% {player} <red>hi"), 0).getFirst()));
    }

    // ------------------------------------------------------------------ helpers

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(text); } catch (Exception broken) { throw new IllegalArgumentException(broken); }
        return yaml;
    }

    static void set(Object object, String name, Object value) throws Exception {
        Field field = BoardsPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    static Object get(Object object, String name) throws Exception {
        Field field = BoardsPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    static Object call(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        var method = BoardsPlugin.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(object, args); }
        catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }
}
