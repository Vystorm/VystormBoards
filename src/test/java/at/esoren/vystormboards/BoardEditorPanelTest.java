package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.*;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class BoardEditorPanelTest {
    static Lang lang() throws Exception {
        Map<String, String> files = new HashMap<>();
        for (String code : List.of("de", "en", "fr", "it", "pt_br", "ru", "zh_cn")) {
            try (var in = BoardEditorPanelTest.class.getResourceAsStream("/lang/" + code + ".yml")) {
                files.put(code, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return Lang.create("vystormboards", files);
    }

    static PanelContext context(Player player, Lang lang, String code) {
        return new PanelContext() {
            public Player player() { return player; }
            public String arg() { return ""; }
            public String text(String key, Object... placeholders) { return lang.plain(code, key, placeholders); }
            public net.kyori.adventure.text.Component component(String key, Object... placeholders) { return net.kyori.adventure.text.Component.text(text(key, placeholders)); }
            public PanelSession session() { return null; }
        };
    }

    private BoardEditorPanel.Overview overview(int count, int selected, List<BoardEditorPanel.Element> elements, int element) {
        List<BoardEditorPanel.Page> pages = new ArrayList<>();
        for (int i = 0; i < count; i++) pages.add(new BoardEditorPanel.Page(i == selected ? elements : List.of()));
        return new BoardEditorPanel.Overview("Welcome", pages, 12, selected, element, "elements", null, 0);
    }

    private JsonObject render(BoardEditorPanel.Overview overview) throws Exception {
        return render(overview, "en");
    }
    private JsonObject render(BoardEditorPanel.Overview overview, String language) throws Exception {
        return PanelTesting.render(BoardEditorPanel.panel(context(mock(Player.class), lang(), language), overview,
                (action, view, operation, value) -> { fail("Rendering must not perform actions"); return Result.ok(); }));
    }

    @Test void givenSelectedPageAndText_whenRendered_thenSelectionDetailsAndAllEditorControlsAreShown() throws Exception {
        var doc = render(overview(3, 1, List.of(new BoardEditorPanel.Element("text", "Hello traveller")), 0));
        var ids = PanelTesting.ids(doc);
        assertTrue(ids.containsAll(List.of("editor-tabs", "pages", "elements", "text", "add-text", "interval", "edit-page",
                "add-page", "duplicate", "earlier", "later", "remove-page", "remove-element", "back", "preview", "save", "discard")));
        assertEquals("1", PanelTesting.find(doc, "pages").get("selected").getAsString());
        assertEquals("0", PanelTesting.find(doc, "elements").get("selected").getAsString());
        assertEquals("Hello traveller", PanelTesting.find(doc, "text").get("value").getAsString());
        assertEquals("Review and apply", PanelTesting.plain(PanelTesting.find(doc, "save").get("label")));
        assertTrue(doc.toString().contains("Page 2"));
        assertTrue(doc.toString().contains("Welcome"));
    }

    @Test void givenDestructiveActions_whenRendered_thenConfirmationIsRequired() throws Exception {
        var doc = render(overview(2, 0, List.of(new BoardEditorPanel.Element("text", "Hi")), 0));
        for (String id : List.of("save", "discard", "remove-page", "remove-element"))
            assertTrue(PanelTesting.find(doc, id).has("confirm"), id);
    }

    @Test void givenOnlyEmptyPage_whenRendered_thenEmptyStateAndBoundaryControlsAreCorrect() throws Exception {
        var doc = render(overview(1, 0, List.of(), 0));
        assertTrue(doc.toString().contains("No elements on this page."));
        assertNull(PanelTesting.find(doc, "text"));
        assertNull(PanelTesting.find(doc, "remove-element"));
        for (String id : List.of("remove-page", "earlier", "later")) assertTrue(PanelTesting.find(doc, id).get("disabled").getAsBoolean());
        var interval = PanelTesting.find(doc, "interval");
        assertEquals(1, interval.get("min").getAsInt());
        assertEquals(3600, interval.get("max").getAsInt());
        assertEquals(12, interval.get("value").getAsInt());
    }

    @Test void givenNonTextElement_whenRendered_thenContentIsVisibleWithoutTextEditing() throws Exception {
        var doc = render(overview(2, 0, List.of(new BoardEditorPanel.Element("image", "logo.png")), 0));
        assertTrue(doc.toString().contains("logo.png"));
        assertTrue(doc.toString().contains("Image"));
        assertNull(PanelTesting.find(doc, "text"));
        assertNotNull(PanelTesting.find(doc, "remove-element"));
    }

    @Test void givenMaximumDraft_whenRendered_thenSchemaAcceptsAllRowsAndAdditionsAreDisabled() throws Exception {
        var elements = new ArrayList<BoardEditorPanel.Element>();
        for (int i = 0; i < 45; i++) elements.add(new BoardEditorPanel.Element("text", "Element content " + i));
        var doc = render(overview(32, 31, elements, 44));
        for (String id : List.of("add-page", "duplicate", "add-text", "later")) assertTrue(PanelTesting.find(doc, id).get("disabled").getAsBoolean(), id);
        assertEquals("44", PanelTesting.find(doc, "elements").get("selected").getAsString());
        assertEquals("Element content 44", PanelTesting.find(doc, "text").get("value").getAsString());
    }

    @Test void givenEveryLanguage_whenRendered_thenPanelLabelsResolveInThatLanguage() throws Exception {
        var view = overview(2, 0, List.of(new BoardEditorPanel.Element("text", "Hi")), 0);
        for (String code : List.of("de", "en", "fr", "it", "pt_br", "ru", "zh_cn")) {
            var doc = render(view, code);
            assertEquals(lang().plain(code, "panel.edit-page"), PanelTesting.plain(PanelTesting.find(doc, "edit-page").get("label")));
            assertEquals(lang().plain(code, "panel.remove-element"), PanelTesting.plain(PanelTesting.find(doc, "remove-element").get("label")));
            assertFalse(doc.toString().contains("panel."), code);
        }
    }
}
