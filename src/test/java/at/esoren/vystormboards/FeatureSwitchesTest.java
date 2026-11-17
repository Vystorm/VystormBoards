package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.util.List;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** Every feature has its own switch in config.yml; a switched-off feature answers "feature-off" and changes nothing. */
class FeatureSwitchesTest {
    private static Board pages() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                page-seconds: 10
                pages:
                  - [{type: rect, color: red, width: 128, height: 128}]
                  - [{type: rect, color: blue, width: 128, height: 128}]
                  - [{type: rect, color: green, width: 128, height: 128}]
                """);
        return Board.read("rules", yaml);
    }

    private static BoardsPlugin plugin(String offField) throws Exception {
        BoardsPlugin p = mock(BoardsPlugin.class);
        BugFixRegressionTest.set(p, "lang", mock(Lang.class));
        BugFixRegressionTest.set(p, offField, true);
        return p;
    }

    @Test void givenPackagedConfig_whenRead_thenEveryFeatureSwitchIsOnByDefault() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        for (String key : List.of("leaderboards.enabled", "polls.enabled", "guestbooks.enabled", "snapshot-photos.enabled",
                "world-clock.enabled", "page-navigation.enabled", "page-editor.enabled")) {
            assertTrue(config.isBoolean(key), key);
            assertTrue(config.getBoolean(key), key);
        }
    }

    @Test void givenClockOn_whenApplied_thenWorldTimeIsFilled() {
        assertEquals("It is 12:00", WorldClock.apply("It is {world_time}", true, () -> 6_000));
    }

    @Test void givenClockOff_whenApplied_thenPlaceholderIsBlankAndWorldIsNotRead() {
        assertEquals("It is ", WorldClock.apply("It is {world_time}", false, () -> { throw new AssertionError("world time read"); }));
        assertEquals("{time}", WorldClock.apply("{time}", false, () -> 0));
    }

    @Test void givenNavigationOff_whenNavigating_thenRefusedAndViewerFollowsRotation() throws Exception {
        Board board = pages();
        PageNavigation nav = new PageNavigation();
        UUID id = UUID.randomUUID();
        assertTrue(nav.navigate(board, id, "3", 0));
        nav.enabled(false);
        assertFalse(nav.enabled());
        assertEquals(0, nav.page(board, id, 0));
        assertEquals(1, nav.page(board, id, 10_000));
        assertFalse(nav.navigate(board, id, "next", 0));
        assertFalse(nav.navigate(board, id, "auto", 0));
    }

    @Test void givenNavigationOnAgain_whenNavigating_thenWorksWithoutOldSelections() throws Exception {
        Board board = pages();
        PageNavigation nav = new PageNavigation();
        UUID id = UUID.randomUUID();
        nav.navigate(board, id, "3", 0);
        nav.enabled(false);
        nav.enabled(true);
        assertTrue(nav.enabled());
        assertEquals(0, nav.page(board, id, 0));
        assertTrue(nav.navigate(board, id, "next", 0));
        assertEquals(1, nav.page(board, id, 0));
    }

    @Test void givenEditorOff_whenEditing_thenFeatureOffMessageAndEditorNotOpened() throws Exception {
        BoardsPlugin p = plugin("editorOff");
        BoardEditor editor = mock(BoardEditor.class);
        BugFixRegressionTest.set(p, "editor", editor);
        Player admin = mock(Player.class);
        doCallRealMethod().when(p).edit(admin, "demo");
        p.edit(admin, "demo");
        verify((Lang) BugFixRegressionTest.get(p, "lang")).send(admin, "feature-off");
        verifyNoInteractions(editor);
    }

    @Test void givenEditorOn_whenEditing_thenEditorOpens() throws Exception {
        BoardsPlugin p = mock(BoardsPlugin.class);
        BoardEditor editor = mock(BoardEditor.class);
        BugFixRegressionTest.set(p, "editor", editor);
        Player admin = mock(Player.class);
        doCallRealMethod().when(p).edit(admin, "demo");
        p.edit(admin, "demo");
        verify(editor).open(admin, "demo");
    }

    @Test void givenPhotosOff_whenTurningBoardIntoSlideshow_thenFeatureOffMessageAndBoardsFileUntouched() throws Exception {
        BoardsPlugin p = plugin("photosOff");
        CommandSender sender = mock(CommandSender.class);
        doCallRealMethod().when(p).photos(sender, "demo", "gallery");
        p.photos(sender, "demo", "gallery");
        verify((Lang) BugFixRegressionTest.get(p, "lang")).send(sender, "feature-off");
        verifyNoMoreInteractions((Lang) BugFixRegressionTest.get(p, "lang"));
    }
}
