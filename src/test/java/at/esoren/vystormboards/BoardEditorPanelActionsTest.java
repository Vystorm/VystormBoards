package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.panel.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class BoardEditorPanelActionsTest {
    @TempDir Path dir;

    private BoardsPlugin plugin() throws Exception {
        BoardsPlugin plugin = mock(BoardsPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.lang()).thenReturn(BoardEditorPanelTest.lang());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        return plugin;
    }
    private Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("vystormboards.admin")).thenReturn(true);
        return player;
    }
    private BoardEditor editor(BoardsPlugin plugin) throws Exception {
        Path file = dir.resolve("boards.yml");
        Files.writeString(file, "boards:\n  demo:\n    pages:\n      - [{type: text, text: Welcome}]\n      - [{type: image, file: logo.png}]\n");
        return new BoardEditor(plugin, TestCore.api(), file);
    }
    private BoardEditorPanel panel(BoardEditor editor) throws Exception {
        var field = BoardEditor.class.getDeclaredField("panel"); field.setAccessible(true);
        return (BoardEditorPanel) field.get(editor);
    }
    private BoardEditorPanel.Overview view(BoardEditor.Session session) {
        return new BoardEditorPanel.Overview("demo", List.of(), session.draft().interval(), 0, 0, "pages", session, session.revision());
    }

    @Test void givenSupportedCore_whenRegisteredAndOpened_thenSpecGuardsAccessAndOffersOriginalChestFallback() throws Exception {
        BoardsPlugin plugin = plugin(); Player player = player(); BoardEditor editor = editor(plugin);
        Panels panels = mock(Panels.class);
        when(panels.open(player, BoardEditorPanel.NAME)).thenReturn(PanelOpenResult.NATIVE);
        try (var core = mockStatic(CoreApi.class); var factory = mockStatic(Panels.class)) {
            core.when(() -> CoreApi.has(CoreApi.Capability.PANELS)).thenReturn(true);
            factory.when(() -> Panels.of(plugin)).thenReturn(panels);
            editor.registerPanel();
            var spec = ArgumentCaptor.forClass(PanelSpec.class);
            verify(panels).register(eq(BoardEditorPanel.NAME), spec.capture());
            assertFalse(spec.getValue().access().test(player));
            editor.open(player, "demo");
            verify(panels).open(player, BoardEditorPanel.NAME);
            assertTrue(spec.getValue().access().test(player));
            assertNotNull(spec.getValue().fallback());
            assertNotNull(PanelTesting.find(PanelTesting.render(spec.getValue(), player, ""), "pages"));
            when(player.hasPermission("vystormboards.admin")).thenReturn(false);
            assertFalse(spec.getValue().access().test(player));
        }
    }

    @Test void givenPanelRegistrationFailure_whenRegistering_thenChestFallbackRemainsAvailable() throws Exception {
        BoardsPlugin plugin = plugin(); BoardEditor editor = editor(plugin);
        try (var core = mockStatic(CoreApi.class); var factory = mockStatic(Panels.class)) {
            core.when(() -> CoreApi.has(CoreApi.Capability.PANELS)).thenReturn(true);
            factory.when(() -> Panels.of(plugin)).thenThrow(new IllegalStateException("disabled"));
            assertDoesNotThrow(editor::registerPanel);
            assertNull(panel(editor));
        }
    }

    @Test void givenLiveDraft_whenActionsRun_thenMutationsRefreshAndStaleActionsAreRejected() throws Exception {
        BoardsPlugin plugin = plugin(); Player player = player(); BoardEditor editor = editor(plugin);
        Panels panels = mock(Panels.class);
        when(panels.open(player, BoardEditorPanel.NAME)).thenReturn(PanelOpenResult.NATIVE);
        try (var core = mockStatic(CoreApi.class); var factory = mockStatic(Panels.class)) {
            core.when(() -> CoreApi.has(CoreApi.Capability.PANELS)).thenReturn(true);
            factory.when(() -> Panels.of(plugin)).thenReturn(panels);
            editor.registerPanel(); editor.open(player, "demo");
            BoardEditorPanel panel = panel(editor);
            BoardEditor.Session session = editor.session(player);
            var original = view(session);
            PanelAction action = mock(PanelAction.class); when(action.player()).thenReturn(player);
            assertTrue(panel.run(action, original, "text", "Updated").success());
            assertEquals("Updated", session.draft().elements(0).getFirst().get("text"));
            verify(action).refresh();
            assertFalse(panel.run(action, original, "remove-element", null).success());
            assertEquals(1, session.draft().elements(0).size());
            assertFalse(panel.run(action, view(session), "interval", 1.5).success());
            assertFalse(panel.run(action, view(session), "interval", 3601).success());
            assertTrue(panel.run(action, view(session), "interval", 30.0).success());
            assertEquals(30, session.draft().interval());
            assertTrue(panel.run(action, view(session), "duplicate", null).success());
            assertEquals(3, session.draft().count());
            when(player.hasPermission("vystormboards.admin")).thenReturn(false);
            assertFalse(panel.run(action, view(session), "add-page", null).success());
            assertEquals(3, session.draft().count());
        }
    }

    @Test void givenFailedPanelOpen_whenOpeningEditor_thenOriginalChestIsOpened() throws Exception {
        BoardsPlugin plugin = plugin(); Player player = player(); BoardEditor editor = spy(editor(plugin));
        doNothing().when(editor).openChest(eq(player), anyInt());
        Panels panels = mock(Panels.class);
        try (var core = mockStatic(CoreApi.class); var factory = mockStatic(Panels.class)) {
            core.when(() -> CoreApi.has(CoreApi.Capability.PANELS)).thenReturn(true);
            factory.when(() -> Panels.of(plugin)).thenReturn(panels);
            editor.registerPanel();
            when(panels.open(player, BoardEditorPanel.NAME)).thenReturn(PanelOpenResult.NONE)
                    .thenThrow(new IllegalStateException("renderer failed"))
                    .thenReturn(PanelOpenResult.FALLBACK);
            editor.open(player, "demo");
            editor.open(player, null);
            verify(editor, times(2)).openChest(player, -1);
            editor.open(player, null);
            verify(editor, times(2)).openChest(player, -1); // Core already handled FALLBACK.
            var spec = ArgumentCaptor.forClass(PanelSpec.class);
            verify(panels).register(eq(BoardEditorPanel.NAME), spec.capture());
            panel(editor).open(player, 1);
            spec.getValue().fallback().accept(player);
            verify(editor).openChest(player, 1);
        }
    }

    @Test void givenTwoPlayers_whenSelectionsChange_thenSelectionsAreIndependentAndForgetResetsThem() throws Exception {
        BoardsPlugin plugin = plugin(); Player first = player(), second = player(); BoardEditor editor = editor(plugin);
        Panels panels = mock(Panels.class);
        when(panels.open(any(), eq(BoardEditorPanel.NAME))).thenReturn(PanelOpenResult.NATIVE);
        try (var core = mockStatic(CoreApi.class); var factory = mockStatic(Panels.class)) {
            core.when(() -> CoreApi.has(CoreApi.Capability.PANELS)).thenReturn(true);
            factory.when(() -> Panels.of(plugin)).thenReturn(panels);
            editor.registerPanel(); editor.open(first, "demo"); editor.open(second, "demo");
            BoardEditorPanel panel = panel(editor);
            PanelAction action = mock(PanelAction.class); when(action.player()).thenReturn(first);
            assertFalse(panel.run(action, view(editor.session(first)), "select-page", "99").success());
            assertTrue(panel.run(action, view(editor.session(first)), "select-page", "1").success());
            var firstDoc = PanelTesting.render(panel.render(BoardEditorPanelTest.context(first, plugin.lang(), "en")));
            var secondDoc = PanelTesting.render(panel.render(BoardEditorPanelTest.context(second, plugin.lang(), "en")));
            assertEquals("1", PanelTesting.find(firstDoc, "pages").get("selected").getAsString());
            assertEquals("0", PanelTesting.find(secondDoc, "pages").get("selected").getAsString());
            panel.forget(first.getUniqueId());
            var reset = PanelTesting.render(panel.render(BoardEditorPanelTest.context(first, plugin.lang(), "en")));
            assertEquals("0", PanelTesting.find(reset, "pages").get("selected").getAsString());
        }
    }

    @Test void givenDeferredDiscard_whenAnotherDraftOpens_thenHandoffCannotDiscardTheNewDraft() throws Exception {
        BoardsPlugin plugin = plugin(); Player player = player(); BoardEditor editor = editor(plugin);
        Panels panels = mock(Panels.class);
        when(panels.open(player, BoardEditorPanel.NAME)).thenReturn(PanelOpenResult.NATIVE);
        try (var core = mockStatic(CoreApi.class); var factory = mockStatic(Panels.class)) {
            core.when(() -> CoreApi.has(CoreApi.Capability.PANELS)).thenReturn(true);
            factory.when(() -> Panels.of(plugin)).thenReturn(panels);
            editor.registerPanel(); editor.open(player, "demo");
            PanelAction action = mock(PanelAction.class); when(action.player()).thenReturn(player);
            assertTrue(panel(editor).run(action, view(editor.session(player)), "discard", null).success());
            @SuppressWarnings("unchecked") ArgumentCaptor<Consumer<Player>> next = ArgumentCaptor.forClass(Consumer.class);
            verify(action).closeAndThen(next.capture());
            editor.open(player, "demo");
            BoardEditor.Session replacement = editor.session(player);
            next.getValue().accept(player);
            assertSame(replacement, editor.session(player));
            assertTrue(panel(editor).run(action, view(replacement), "discard", null).success());
            verify(action, times(2)).closeAndThen(next.capture());
            next.getValue().accept(player);
            assertNull(editor.session(player));
        }
    }
}
