package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PageNavigationTest {
    private Board board(String name) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                page-seconds: 10
                pages:
                  - [{type: rect, color: red, width: 128, height: 128}]
                  - [{type: rect, color: blue, width: 128, height: 128}]
                  - [{type: rect, color: green, width: 128, height: 128}]
                clicks:
                  - {navigate: next, page: 2, permission: vip, cooldown: 3}
                  - {navigate: previous}
                """);
        return Board.read(name, yaml);
    }

    @Test void selectionsArePersonalAndStayPinned() throws Exception {
        Board board = board("rules");
        PageNavigation nav = new PageNavigation();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertTrue(nav.navigate(board, a, "next", 10_000));
        assertEquals(2, nav.page(board, a, 30_000));
        assertEquals(0, nav.page(board, b, 30_000));
        assertEquals(0, nav.page(board("other"), a, 30_000));
        assertEquals(0, nav.page(board, null, 30_000));
    }

    @Test void wrapsAndAcceptsOneBasedTargets() throws Exception {
        Board board = board("rules");
        PageNavigation nav = new PageNavigation();
        UUID id = UUID.randomUUID();
        assertTrue(nav.navigate(board, id, "previous", 0));
        assertEquals(2, nav.page(board, id, 0));
        assertTrue(nav.navigate(board, id, "next", 0));
        assertEquals(0, nav.page(board, id, 0));
        assertTrue(nav.navigate(board, id, "2", 0));
        assertEquals(1, nav.page(board, id, 0));
    }

    @Test void autoQuitAndReloadRestoreRotation() throws Exception {
        Board board = board("rules");
        PageNavigation nav = new PageNavigation();
        UUID id = UUID.randomUUID();
        nav.navigate(board, id, "3", 0);
        assertTrue(nav.navigate(board, id, "auto", 10_000));
        assertEquals(1, nav.page(board, id, 10_000));
        nav.navigate(board, id, "3", 0);
        nav.forget(id);
        assertEquals(0, nav.page(board, id, 0));
        nav.navigate(board, id, "3", 0);
        nav.clear();
        assertEquals(0, nav.page(board, id, 0));
    }

    @Test void invalidActionsDoNotChangeSelection() throws Exception {
        Board board = board("rules");
        PageNavigation nav = new PageNavigation();
        UUID id = UUID.randomUUID();
        nav.navigate(board, id, "2", 0);
        for (String action : new String[]{"", "0", "4", "-1", "2147483648", "unknown"}) {
            assertFalse(nav.navigate(board, id, action, 0));
            assertEquals(1, nav.page(board, id, 0));
        }
        assertFalse(nav.navigate(board, null, "next", 0));
        assertFalse(nav.navigate(Board.read("empty", new YamlConfiguration()), id, "next", 0));
    }

    @Test void parsingDefaultsAndClickGatesUseSelectedPage() throws Exception {
        Board board = board("rules");
        assertTrue(board.perPlayer());
        assertEquals("next", board.clicks.getFirst().navigate());
        assertEquals(3, board.clicks.getFirst().cooldown());
        assertEquals(0, board.clickAtPage(1, 1, 1, permission -> true));
        assertEquals(1, board.clickAtPage(1, 1, 0, permission -> true));
        assertEquals(1, board.clickAtPage(1, 1, 1, permission -> false));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("clicks: [{command: help}]");
        Board legacy = Board.read("old", yaml);
        assertEquals("", legacy.clicks.getFirst().navigate());
        assertFalse(legacy.perPlayer());
        assertEquals("next", board.withMaps(java.util.List.of(1)).clicks.getFirst().navigate());
    }

    @Test void composerDrawsSelectedPageInsteadOfRotatingPage() throws Exception {
        Board board = board("rules");
        Composer composer = new Composer((file, frame, width, height, dither) -> null, name -> null);
        int[] automatic = composer.render(board, text -> text, "", permission -> true, 0);
        int[] selected = composer.render(board, text -> text, "", permission -> true, 0, 1);
        assertEquals(0xFFFF5555, automatic[0]);
        assertEquals(0xFF5555FF, selected[0]);
    }

    @Test void animationsKeepRealTimeOnPinnedPages() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("pages: [[{type: image, file: animation.gif}], [{type: rect}]]");
        Board board = Board.read("animated", yaml);
        java.util.List<Integer> frames = new java.util.ArrayList<>();
        Composer composer = new Composer(new Composer.Images() {
            public java.awt.image.BufferedImage fitted(String file, int frame, int width, int height, boolean dither) {
                frames.add(frame);
                return null;
            }
            public int frames(String file) { return 4; }
        }, name -> null);
        composer.render(board, text -> text, "", permission -> true, 0, 0);
        composer.render(board, text -> text, "", permission -> true, 500, 0);
        assertEquals(java.util.List.of(0, 2), frames);
    }

    @Test void runtimeRecomposesOnlyTheNavigatingViewer() throws Exception {
        Board board = board("rules");
        PageNavigation nav = new PageNavigation();
        org.bukkit.entity.Player a = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        org.bukkit.entity.Player b = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        org.mockito.Mockito.when(a.getUniqueId()).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.when(b.getUniqueId()).thenReturn(UUID.randomUUID());
        BoardRuntime runtime = new BoardRuntime(board,
                (shown, viewer) -> Integer.toString(nav.page(shown, viewer.getUniqueId(), 0)),
                (shown, viewer) -> new byte[]{(byte) nav.page(shown, viewer.getUniqueId(), 0)});
        BoardRuntime.Picture beforeA = runtime.current(a), beforeB = runtime.current(b);
        nav.navigate(board, a.getUniqueId(), "next", 0);
        runtime.forget(a.getUniqueId());
        assertEquals(1, runtime.current(a).pixels()[0]);
        assertTrue(runtime.current(a).version() > beforeA.version());
        assertSame(beforeB, runtime.current(b));
        assertTrue(runtime.tile(0).isContextual());
    }
}
