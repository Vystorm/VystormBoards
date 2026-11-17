package at.esoren.vystormboards;

import Vystorm.vystorm_core.API;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.awt.image.BufferedImage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.*;

/** Detached page editor. Preview maps are owner-only and never replace inventory contents or alter a live board. */
final class BoardEditor implements Listener {
    static final class Session {
        private final BoardDraft draft;
        private final long started;
        private long revision;
        Session(BoardDraft draft, long started) { this.draft = draft; this.started = started; }
        BoardDraft draft() { return draft; }
        long started() { return started; }
        long revision() { return revision; }
    }
    /** Keeps optional Core panel types out of the legacy editor's signatures. */
    interface PanelView {
        boolean open(Player player, int page);
        void forget(UUID player);
        void close();
    }
    private PanelView panel;
    private final BoardsPlugin plugin;
    private final API core;
    private final Path file, previewFile;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Integer> previewIds = new LinkedHashMap<>();
    private final Map<Integer, Preview> previews = new HashMap<>();
    private boolean previewReadOnly;
    BoardEditor(BoardsPlugin plugin, API core, Path file) {
        this.plugin = plugin; this.core = core; this.file = file; this.previewFile = file.resolveSibling("editor-previews.yml");
        if (Files.exists(previewFile)) try {
            YamlConfiguration y = new YamlConfiguration(); y.load(previewFile.toFile());
            var ids = y.getConfigurationSection("maps"); if (ids == null) throw new IllegalArgumentException("missing maps");
            for (String key : ids.getKeys(false)) { int id = ids.getInt(key, -1); if (id < 0 || previewIds.containsValue(id)) throw new IllegalArgumentException("invalid map"); previewIds.put(UUID.fromString(key), id); }
            if (previewIds.size() > 256) throw new IllegalArgumentException("too many preview maps");
            previewIds.forEach((owner, id) -> attach(Bukkit.getMap(id), owner));
        } catch (Exception failure) { previewIds.clear(); previewReadOnly = true; plugin.getLogger().warning("Preview map data retained; previews disabled: " + failure.getMessage()); }
    }
    private boolean allowed(Player p) { return p.isOnline() && p.hasPermission("vystormboards.admin"); }
    Session session(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (!allowed(p) || s == null || System.currentTimeMillis() - s.started() > 30 * 60_000L) { sessions.remove(p.getUniqueId()); return null; }
        return s;
    }
    void registerPanel() {
        try {
            panel = BoardEditorPanel.register(plugin, core.gui().panels(), this);
        } catch (RuntimeException failure) {
            plugin.getLogger().fine("Board panel unavailable; using chest editor: " + failure);
        }
    }
    boolean current(Player p, Session expected, long revision) {
        return expected != null && plugin.isEnabled() && session(p) == expected && expected.revision == revision;
    }
    boolean change(Player p, Session expected, long revision, String operation, int page, int element, Object value) {
        if (!current(p, expected, revision)) return false;
        BoardDraft draft = expected.draft();
        if (page < 0 || page >= draft.count()) return false;
        boolean changed;
        try {
            changed = switch (operation) {
                case "add-page" -> draft.addPage(-1);
                case "duplicate" -> draft.addPage(page);
                case "earlier" -> draft.movePage(page, page - 1);
                case "later" -> draft.movePage(page, page + 1);
                case "remove-page" -> draft.removePage(page);
                case "remove-element" -> draft.removeElement(page, element);
                case "text" -> element >= 0 && draft.text(page, element, (String) value);
                case "add-text" -> draft.text(page, -1, (String) value);
                case "interval" -> {
                    double seconds = ((Number) value).doubleValue();
                    if (!Double.isFinite(seconds) || seconds != Math.rint(seconds)) yield false;
                    draft.interval((int) seconds); yield true;
                }
                default -> false;
            };
        } catch (IllegalArgumentException invalid) { return false; }
        if (changed) expected.revision++;
        return changed;
    }
    void handoff(Player p, Session expected, long revision, String operation, int page) {
        if (!current(p, expected, revision)) return;
        switch (operation) {
            case "save" -> apply(p, expected);
            case "preview" -> { if (page >= 0 && page < expected.draft().count()) preview(p, expected.draft(), page); }
            case "discard" -> { sessions.remove(p.getUniqueId()); clearPreview(p.getUniqueId()); if (panel != null) panel.forget(p.getUniqueId()); }
            default -> { }
        }
    }
    void open(Player p, String name) {
        if (!allowed(p)) return;
        if (name == null) { if (session(p) != null) pages(p); else plugin.lang().send(p, "editor.usage"); return; }
        try {
            BoardDraft draft = new BoardDraft(name, Files.readString(file, StandardCharsets.UTF_8));
            if (panel != null) panel.forget(p.getUniqueId());
            sessions.put(p.getUniqueId(), new Session(draft, System.currentTimeMillis())); pages(p);
        } catch (Exception failure) { plugin.lang().send(p, "editor.unavailable"); }
    }
    private void prompt(Player p, String key, String initial, Consumer<String> callback) {
        Session expected = session(p); p.closeInventory();
        core.dialogs().prompt(plugin, p, plugin.lang().ref("editor.title"), plugin.lang().ref("editor." + key), initial,
                text -> { if (session(p) == expected && expected != null) callback.accept(text); }, () -> {}, this::allowed);
    }
    private void pages(Player p) {
        if (!openPanel(p, -1)) openChest(p, -1);
    }
    private void page(Player p, int index) {
        if (!openPanel(p, index)) openChest(p, index);
    }
    private boolean openPanel(Player p, int index) {
        if (panel != null) try { return panel.open(p, index); }
        catch (RuntimeException failure) { plugin.getLogger().fine("Board panel failed; using chest editor: " + failure); }
        return false;
    }
    void openChest(Player p, int index) { if (index < 0) pagesChest(p); else pageChest(p, index); }
    private void pagesChest(Player p) {
        Session s = session(p); if (s == null) return; BoardDraft d = s.draft();
        Holder h = holder(p, s, plugin.lang().component(p, "editor.title"));
        for (int i = 0; i < d.count(); i++) {
            int at = i; h.set(i, item(Material.PAPER, plugin.lang().component(p, "editor.page", "page", i + 1),
                    plugin.lang().component(p, "editor.elements", "count", d.elements(i).size())), who -> page(who, at));
        }
        h.set(45, item(p, Material.WRITABLE_BOOK, "add-page"), who -> { if (!d.addPage(-1)) plugin.lang().send(who, "editor.invalid"); pages(who); });
        h.set(49, item(Material.CLOCK, plugin.lang().component(p, "editor.interval", "seconds", d.interval())), who -> prompt(who, "interval-prompt", Integer.toString(d.interval()), text -> {
            try { d.interval(Integer.parseInt(text.strip())); } catch (RuntimeException invalid) { plugin.lang().send(who, "editor.invalid"); } pages(who);
        }));
        h.set(51, item(p, Material.EMERALD, "save"), this::save);
        h.set(53, item(p, Material.BARRIER, "discard"), who -> { sessions.remove(who.getUniqueId()); clearPreview(who.getUniqueId()); who.closeInventory(); });
        p.openInventory(h.inventory);
    }
    private void pageChest(Player p, int index) {
        Session s = session(p); if (s == null || index < 0 || index >= s.draft().count()) return; BoardDraft d = s.draft();
        Holder h = holder(p, s, plugin.lang().component(p, "editor.page", "page", index + 1)); h.page = index;
        var elements = d.elements(index);
        for (int i = 0; i < elements.size(); i++) {
            var e = elements.get(i); String type = Objects.toString(e.getOrDefault("type", "text"));
            h.inventory.setItem(i, item(type.equalsIgnoreCase("text") ? Material.NAME_TAG : Material.PAINTING,
                    Component.text(type), Component.text(Objects.toString(e.getOrDefault("text", e.getOrDefault("file", "")))), plugin.lang().component(p, "editor.element-hint")));
            h.elements.add(i);
        }
        h.set(45, item(p, Material.ARROW, "back"), this::pages);
        h.set(46, item(p, Material.NAME_TAG, "add-text"), who -> prompt(who, "text-prompt", "", text -> { if (!d.text(index, -1, text)) plugin.lang().send(who, "editor.invalid"); page(who, index); }));
        h.set(47, item(p, Material.PAPER, "duplicate"), who -> { if (!d.addPage(index)) plugin.lang().send(who, "editor.invalid"); pages(who); });
        h.set(48, item(p, Material.ARROW, "earlier"), who -> { d.movePage(index, index - 1); pages(who); });
        h.set(49, item(p, Material.ARROW, "later"), who -> { d.movePage(index, index + 1); pages(who); });
        h.set(50, item(p, Material.BARRIER, "remove-page"), who -> { if (!d.removePage(index)) plugin.lang().send(who, "editor.invalid"); pages(who); });
        h.set(51, item(p, Material.FILLED_MAP, "preview"), who -> preview(who, d, index));
        h.set(52, item(p, Material.EMERALD, "save"), this::save);
        p.openInventory(h.inventory);
    }
    private void save(Player p) {
        Session expected = session(p); if (expected == null) return;
        p.closeInventory(); core.dialogs().confirm(plugin, p, plugin.lang().ref("editor.save"), plugin.lang().ref("editor.confirm"), () -> {
            apply(p, expected);
        }, () -> {}, this::allowed);
    }
    private void apply(Player p, Session expected) {
        if (session(p) != expected) return;
        BoardDraft.Save result = expected.draft().save(core.data().files(), file);
        plugin.lang().send(p, "editor.save-" + result.name().toLowerCase(Locale.ROOT));
        if (result == BoardDraft.Save.SAVED) {
            sessions.remove(p.getUniqueId()); clearPreview(p.getUniqueId());
            if (panel != null) panel.forget(p.getUniqueId());
            plugin.reload(p);
        }
    }
    private void preview(Player p, BoardDraft draft, int page) {
        if (!allowed(p) || previewReadOnly || session(p) == null || session(p).draft() != draft) return;
        Integer id = previewIds.get(p.getUniqueId());
        boolean hasCopy = id != null && Arrays.stream(p.getInventory().getContents()).filter(Objects::nonNull).anyMatch(i -> i.getItemMeta() instanceof MapMeta m && m.hasMapView() && m.getMapView() != null && m.getMapView().getId() == id);
        if (!hasCopy && p.getInventory().firstEmpty() < 0) { plugin.lang().send(p, "editor.inventory-full"); return; }
        try {
            int mapId;
            if (id == null) {
                if (previewIds.size() >= 256) { plugin.lang().send(p, "editor.unavailable"); return; }
                MapView map = Bukkit.createMap(p.getWorld()); mapId = map.getId();
                YamlConfiguration y = new YamlConfiguration(); previewIds.forEach((owner, saved) -> y.set("maps." + owner, saved)); y.set("maps." + p.getUniqueId(), mapId);
                core.data().files().write(previewFile, y); previewIds.put(p.getUniqueId(), mapId); attach(map, p.getUniqueId());
            } else { mapId = id; attach(Bukkit.getMap(mapId), p.getUniqueId()); }
            Preview display = previews.get(mapId); if (display == null) throw new IllegalStateException("preview map unavailable");
            Board b = draft.board(); int[] rgb = plugin.editorPixels(b, p, page);
            display.show(thumbnail(rgb, b.pixelWidth(), b.pixelHeight()));
            if (!hasCopy) {
                ItemStack item = item(p, Material.FILLED_MAP, "preview-item"); MapMeta meta = (MapMeta) item.getItemMeta(); meta.setMapView(Bukkit.getMap(mapId)); item.setItemMeta(meta);
                if (!p.getInventory().addItem(item).isEmpty()) throw new IllegalStateException("inventory changed");
            }
            p.closeInventory(); p.sendMap(Bukkit.getMap(mapId)); plugin.lang().send(p, "editor.preview-ready");
        } catch (Exception failure) { plugin.getLogger().warning("Board preview failed: " + failure.getMessage()); plugin.lang().send(p, "editor.unavailable"); }
    }
    static BufferedImage thumbnail(int[] pixels, int width, int height) {
        BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB); source.setRGB(0, 0, width, height, pixels, 0, width);
        BufferedImage out = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB); var g = out.createGraphics();
        try { double scale = Math.min(128.0 / width, 128.0 / height); int w = Math.max(1, (int) (width * scale)), h = Math.max(1, (int) (height * scale));
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR); g.drawImage(source, (128 - w) / 2, (128 - h) / 2, w, h, null);
        } finally { g.dispose(); } return out;
    }
    private void attach(MapView view, UUID owner) {
        if (view == null || previews.containsKey(view.getId())) return;
        for (MapRenderer renderer : new ArrayList<>(view.getRenderers())) view.removeRenderer(renderer);
        Preview renderer = new Preview(owner); previews.put(view.getId(), renderer); view.addRenderer(renderer);
    }
    private void clearPreview(UUID owner) { Integer id = previewIds.get(owner); if (id != null && previews.get(id) != null) previews.get(id).show(null); }
    /** Editor switched off (page-editor.enabled): open drafts and previews end; boards.yml and saved map ids stay. */
    void endSessions() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (core.compat().inventory().top(player.getOpenInventory()).getHolder() instanceof Holder) player.closeInventory();
        }
        for (UUID owner : sessions.keySet()) { clearPreview(owner); if (panel != null) panel.forget(owner); }
        sessions.clear();
    }
    void close() {
        if (panel != null) { panel.close(); panel = null; }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (core.compat().inventory().top(player.getOpenInventory()).getHolder() instanceof Holder) player.closeInventory();
        }
        sessions.clear(); previews.forEach((id, renderer) -> { MapView view = Bukkit.getMap(id); if (view != null) view.removeRenderer(renderer); }); previews.clear();
    }
    @EventHandler public void initialized(MapInitializeEvent e) { previewIds.forEach((owner, id) -> { if (id == e.getMap().getId()) attach(e.getMap(), owner); }); }
    @EventHandler public void quit(PlayerQuitEvent e) { if (panel != null) panel.forget(e.getPlayer().getUniqueId()); sessions.remove(e.getPlayer().getUniqueId()); clearPreview(e.getPlayer().getUniqueId()); previews.values().forEach(p -> p.sent.remove(e.getPlayer().getUniqueId())); }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(core.compat().inventory().top(event.getView()).getHolder() instanceof Holder h)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p) || !h.viewer.equals(p.getUniqueId()) || session(p) != h.session || event.getClickedInventory() != h.inventory) return;
        int slot = event.getSlot(); var d = h.session.draft();
        h.session.revision++;
        if (h.elements.contains(slot)) {
            if (event.isShiftClick()) { d.removeElement(h.page, slot); page(p, h.page); }
            else {
                var element = d.elements(h.page).get(slot);
                if ("text".equalsIgnoreCase(Objects.toString(element.getOrDefault("type", "text")))) prompt(p, "text-prompt", Objects.toString(element.get("text"), ""), text -> { if (!d.text(h.page, slot, text)) plugin.lang().send(p, "editor.invalid"); page(p, h.page); });
            }
        } else { var action = h.actions.get(slot); if (action != null) action.accept(p); }
    }
    @EventHandler public void drag(InventoryDragEvent event) { if (core.compat().inventory().top(event.getView()).getHolder() instanceof Holder) event.setCancelled(true); }
    private ItemStack item(Player p, Material type, String key) { return item(type, plugin.lang().component(p, "editor." + key)); }
    private ItemStack item(Material type, Component title, Component... lore) { var platform = core.compat().platform(); ItemStack i = new ItemStack(type); platform.editMeta(i, m -> { platform.displayName(m, title.decoration(TextDecoration.ITALIC, false)); platform.lore(m, Arrays.asList(lore)); }); return i; }
    private Holder holder(Player p, Session s, Component title) { Holder h = new Holder(p.getUniqueId(), s); h.inventory = core.compat().platform().createInventory(h, 54, title); return h; }
    private static final class Holder implements InventoryHolder {
        final UUID viewer; final Session session; final Map<Integer, Consumer<Player>> actions = new HashMap<>(); final Set<Integer> elements = new HashSet<>(); Inventory inventory; int page;
        Holder(UUID viewer, Session session) { this.viewer = viewer; this.session = session; }
        void set(int at, ItemStack icon, Consumer<Player> action) { inventory.setItem(at, icon); actions.put(at, action); }
        @Override public Inventory getInventory() { return inventory; }
    }
    private static final class Preview extends MapRenderer {
        final UUID owner; final Map<UUID, Long> sent = new HashMap<>(); BufferedImage image; long expires, version = 1;
        Preview(UUID owner) { super(true); this.owner = owner; }
        void show(BufferedImage image) { this.image = image; expires = System.currentTimeMillis() + 120_000; version++; }
        @Override public void render(MapView view, MapCanvas canvas, Player player) {
            boolean visible = image != null && player.getUniqueId().equals(owner) && player.hasPermission("vystormboards.admin") && System.currentTimeMillis() < expires;
            long state = visible ? version : 0; if (Objects.equals(sent.get(player.getUniqueId()), state)) return;
            if (visible) canvas.drawImage(0, 0, image); else canvas.drawImage(0, 0, new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB));
            sent.put(player.getUniqueId(), state);
        }
    }
}
