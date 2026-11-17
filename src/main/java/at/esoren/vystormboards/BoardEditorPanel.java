package at.esoren.vystormboards;

import Vystorm.vystorm_core.panel.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Detached editor rendered from a snapshot; all mutations go through BoardEditor and BoardDraft. */
final class BoardEditorPanel implements BoardEditor.PanelView {
    static final String NAME = "editor";
    record Element(String type, String content) {
        boolean editable() { return "text".equalsIgnoreCase(type); }
    }
    record Page(List<Element> elements) {}
    record Overview(String board, List<Page> pages, int interval, int page, int element, String tab,
                    BoardEditor.Session session, long revision) {}
    interface Actions {
        Result run(PanelAction action, Overview view, String operation, Object value);
    }

    private final BoardsPlugin plugin;
    private final BoardEditor editor;
    private final Panels panels;
    private final Map<UUID, String> selectedPages = new HashMap<>();
    private final Map<UUID, String> selectedElements = new HashMap<>();
    private final Map<UUID, String> tabs = new HashMap<>();

    private BoardEditorPanel(BoardsPlugin plugin, BoardEditor editor, Panels panels) {
        this.plugin = plugin; this.editor = editor; this.panels = panels;
    }

    /** @param panelsPart {@code api.gui().panels()} */
    static BoardEditorPanel register(BoardsPlugin plugin, PanelsInterface panelsPart, BoardEditor editor) {
        Panels panels = panelsPart.of(plugin);
        BoardEditorPanel panel = new BoardEditorPanel(plugin, editor, panels);
        panels.register(NAME, PanelSpec.builder(plugin.lang().ref("editor.title"))
                .access(player -> plugin.isEnabled() && editor.session(player) != null)
                .render(panel::render)
                .fallback(player -> editor.openChest(player, "elements".equals(panel.tabs.get(player.getUniqueId()))
                        ? index(panel.selectedPages.get(player.getUniqueId()), 32) : -1))
                .build());
        return panel;
    }

    @Override public boolean open(Player player, int page) {
        if (editor.session(player) == null) return false;
        if (page >= 0) {
            selectedPages.put(player.getUniqueId(), Integer.toString(page));
            selectedElements.remove(player.getUniqueId());
        }
        tabs.put(player.getUniqueId(), page < 0 ? "pages" : "elements");
        return panels.open(player, NAME) != PanelOpenResult.NONE;
    }

    @Override public void forget(UUID player) {
        selectedPages.remove(player); selectedElements.remove(player); tabs.remove(player);
    }

    @Override public void close() {
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) panels.close(player, NAME);
        panels.unregister(NAME);
        selectedPages.clear(); selectedElements.clear(); tabs.clear();
    }

    Panel render(PanelContext ctx) {
        BoardEditor.Session session = editor.session(ctx.player());
        if (session == null) return Panel.page(ctx.text("editor.title")).child(Ui.text(ctx.text("editor.unavailable")).id("unavailable"));
        BoardDraft draft = session.draft();
        List<Page> pages = new ArrayList<>();
        for (int i = 0; i < draft.count(); i++) {
            List<Element> elements = draft.elements(i).stream().map(e -> new Element(
                    Objects.toString(e.getOrDefault("type", "text")),
                    Objects.toString(e.getOrDefault("text", e.getOrDefault("file", ""))))).toList();
            pages.add(new Page(elements));
        }
        UUID id = ctx.player().getUniqueId();
        int page = index(selectedPages.get(id), pages.size());
        int element = index(selectedElements.get(id), pages.get(page).elements().size());
        return panel(ctx, new Overview(draft.name, List.copyOf(pages), draft.interval(), page, element,
                tabs.getOrDefault(id, "pages"), session, session.revision()), this::run);
    }

    static int index(String key, int size) {
        try { return Math.clamp(Integer.parseInt(key), 0, Math.max(0, size - 1)); }
        catch (NumberFormatException invalid) { return 0; }
    }

    Result run(PanelAction action, Overview view, String operation, Object value) {
        Player player = action.player();
        if (!editor.current(player, view.session(), view.revision())) return Result.error(plugin.lang().ref("editor.unavailable"));
        UUID id = player.getUniqueId();
        switch (operation) {
            case "select-page" -> {
                if (!validIndex(value, view.session().draft().count())) return invalid();
                selectedPages.put(id, value.toString()); selectedElements.remove(id);
            }
            case "select-element" -> {
                if (!validIndex(value, view.session().draft().elements(view.page()).size())) return invalid();
                selectedElements.put(id, value.toString());
            }
            case "tab" -> {
                if (!List.of("pages", "elements").contains(value)) return invalid();
                tabs.put(id, value.toString());
            }
            case "save", "preview", "discard" -> {
                action.closeAndThen(next -> editor.handoff(next, view.session(), view.revision(), operation, view.page()));
                return Result.ok();
            }
            default -> {
                if (!editor.change(player, view.session(), view.revision(), operation, view.page(), view.element(), value)) return invalid();
                if (operation.equals("earlier") || operation.equals("later"))
                    selectedPages.put(id, Integer.toString(view.page() + (operation.equals("earlier") ? -1 : 1)));
                if (operation.equals("remove-page")) selectedElements.remove(id);
                if (operation.equals("add-text")) selectedElements.put(id, Integer.toString(view.session().draft().elements(view.page()).size() - 1));
            }
        }
        action.refresh();
        return Result.ok();
    }

    private Result invalid() { return Result.error(plugin.lang().ref("editor.invalid")); }
    private static boolean validIndex(Object value, int size) {
        try { int index = Integer.parseInt(Objects.toString(value)); return index >= 0 && index < size; }
        catch (NumberFormatException invalid) { return false; }
    }

    /** Pure layout used by production and PanelTesting. No Bukkit inventory or live draft access. */
    static Panel panel(PanelContext ctx, Overview view, Actions actions) {
        Panel panel = Panel.page(ctx.text("editor.title")).subtitle(view.board())
                .icon(Ui.item(Material.PAINTING)).size(Panel.Size.LARGE);
        Ui.ListView pages = Ui.list("pages").selected(Integer.toString(view.page())).grow(2)
                .onSelect((a, value) -> actions.run(a, view, "select-page", value));
        for (int i = 0; i < view.pages().size(); i++) {
            pages.item(Ui.listItem(Integer.toString(i), ctx.text("editor.page", "page", i + 1))
                    .subtitle(ctx.text("editor.elements", "count", view.pages().get(i).elements().size())).icon(Ui.item(Material.PAPER)));
        }
        Page page = view.pages().get(view.page());
        Ui.Box details = Ui.col().grow(3).children(
                Ui.heading(ctx.text("editor.page", "page", view.page() + 1)),
                Ui.badge(ctx.text("editor.elements", "count", page.elements().size())),
                Ui.kv().row(ctx.text("panel.pages"), view.pages().size()),
                button(ctx, view, actions, "edit-page", "panel.edit-page", Material.WRITABLE_BOOK, "tab", "elements").primary(),
                Ui.row().wrap().children(
                        button(ctx, view, actions, "duplicate", "editor.duplicate", Material.PAPER).disabled(view.pages().size() >= 32),
                        button(ctx, view, actions, "earlier", "editor.earlier", Material.ARROW).disabled(view.page() == 0),
                        button(ctx, view, actions, "later", "editor.later", Material.ARROW).disabled(view.page() == view.pages().size() - 1)),
                button(ctx, view, actions, "remove-page", "editor.remove-page", Material.BARRIER).danger()
                        .disabled(view.pages().size() <= 1).confirm(ctx.text("editor.remove-page"), ctx.text("panel.remove-page-confirm")));
        Ui.Box overview = Ui.col().children(Ui.row().children(pages, details),
                button(ctx, view, actions, "add-page", "editor.add-page", Material.WRITABLE_BOOK).disabled(view.pages().size() >= 32),
                Ui.number("interval", ctx.text("editor.interval-prompt")).range(1, 3600, 1).value(view.interval()).decimals(0)
                        .onChange((a, value) -> actions.run(a, view, "interval", value)));

        Ui.ListView elements = Ui.list("elements").grow(2).empty(ctx.text("panel.empty"))
                .onSelect((a, value) -> actions.run(a, view, "select-element", value));
        for (int i = 0; i < page.elements().size(); i++) {
            Element element = page.elements().get(i);
            elements.item(Ui.listItem(Integer.toString(i), ctx.text("panel.element", "number", i + 1))
                    .subtitle(element.content()).icon(Ui.item(element.editable() ? Material.NAME_TAG : Material.PAINTING)));
        }
        Ui.Box elementDetails = Ui.col().grow(3);
        if (!page.elements().isEmpty()) {
            elements.selected(Integer.toString(view.element()));
            Element element = page.elements().get(view.element());
            String type = element.type().toLowerCase(Locale.ROOT);
            if (!List.of("text", "image", "rect", "head", "progress", "bar", "top", "leaderboard").contains(type)) type = "other";
            elementDetails.child(Ui.kv().row(ctx.text("panel.type"), ctx.text("panel.type-" + type)));
            if (element.editable()) elementDetails.child(Ui.input("text", ctx.text("editor.text-prompt"))
                    .value(element.content()).maxLength(240).submitOnEnter()
                    .onChange((a, value) -> actions.run(a, view, "text", value)));
            else elementDetails.child(Ui.text(element.content()));
            elementDetails.child(button(ctx, view, actions, "remove-element", "panel.remove-element", Material.BARRIER).danger()
                    .confirm(ctx.text("panel.remove-element"), ctx.text("panel.remove-element-confirm")));
        } else elementDetails.child(Ui.text(ctx.text("panel.empty")));
        Ui.Box editing = Ui.col().children(Ui.heading(ctx.text("editor.page", "page", view.page() + 1)),
                Ui.row().children(elements, elementDetails),
                Ui.input("add-text", ctx.text("editor.add-text")).value("").maxLength(240).submitOnEnter()
                        .disabled(page.elements().size() >= 45).onChange((a, value) -> actions.run(a, view, "add-text", value)));

        return panel.child(Ui.tabs("editor-tabs").select(view.tab())
                        .tab("pages", ctx.text("panel.pages"), overview)
                        .tab("elements", ctx.text("panel.edit-page"), editing)
                        .onTab((a, value) -> actions.run(a, view, "tab", value)))
                .footer(button(ctx, view, actions, "back", "editor.back", Material.ARROW, "tab", "pages").ghost(),
                        button(ctx, view, actions, "preview", "editor.preview", Material.FILLED_MAP).ghost(),
                        button(ctx, view, actions, "save", "editor.save", Material.EMERALD).primary()
                                .confirm(ctx.text("editor.save"), ctx.text("editor.confirm")),
                        button(ctx, view, actions, "discard", "editor.discard", Material.BARRIER).danger()
                                .confirm(ctx.text("editor.discard"), ctx.text("panel.discard-confirm")));
    }

    private static Ui.Button button(PanelContext ctx, Overview view, Actions actions, String id, String key, Material icon) {
        return button(ctx, view, actions, id, key, icon, id, null);
    }
    private static Ui.Button button(PanelContext ctx, Overview view, Actions actions, String id, String key, Material icon,
                                    String operation, Object value) {
        return Ui.button(id).label(ctx.text(key)).icon(Ui.item(icon)).onClick(a -> actions.run(a, view, operation, value));
    }
}
