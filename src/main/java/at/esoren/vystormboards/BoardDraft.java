package at.esoren.vystormboards;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntUnaryOperator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Edits a detached YAML document. Saving refuses any concurrent file edit instead of overwriting it. */
final class BoardDraft {
    enum Save { SAVED, CONFLICT, FAILED }
    final String name;
    private final String original;
    private final YamlConfiguration document;
    private final ConfigurationSection section;
    private final List<List<Map<String, Object>>> pages = new ArrayList<>();
    BoardDraft(String name, String original) throws Exception {
        this.original = original;
        document = new YamlConfiguration(); document.loadFromString(original);
        ConfigurationSection boards = document.getConfigurationSection("boards");
        this.name = boards == null ? name : boards.getKeys(false).stream().filter(name::equalsIgnoreCase).findFirst().orElse(name);
        section = document.getConfigurationSection("boards." + this.name);
        if (section == null) throw new IllegalArgumentException("Unknown board");
        Object raw = section.get("pages");
        if (raw != null && !(raw instanceof List<?>)) throw new IllegalArgumentException("Invalid pages");
        for (Object page : section.getList("pages", List.of())) {
            if (!(page instanceof List<?> list) || list.size() > 45) throw new IllegalArgumentException("Invalid page");
            List<Map<String, Object>> elements = new ArrayList<>();
            for (Object element : list) {
                if (!(element instanceof Map<?, ?> map)) throw new IllegalArgumentException("Invalid element");
                elements.add(copy(map));
            }
            pages.add(elements);
        }
        if (pages.size() > 32) throw new IllegalArgumentException("Too many pages for the editor");
        if (pages.isEmpty()) pages.add(new ArrayList<>());
    }
    private static Map<String, Object> copy(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>(); map.forEach((k, v) -> out.put(k.toString(), v)); return out;
    }
    int count() { return pages.size(); }
    int interval() { return Math.clamp(section.getInt("page-seconds", 10), 1, 3600); }
    void interval(int value) { if (value < 1 || value > 3600) throw new IllegalArgumentException(); section.set("page-seconds", value); }
    List<Map<String, Object>> elements(int page) { return pages.get(page).stream().map(e -> Collections.unmodifiableMap(new LinkedHashMap<>(e))).toList(); }
    boolean addPage(int clone) {
        if (pages.size() >= 32 || clone >= pages.size()) return false;
        pages.add(clone < 0 ? new ArrayList<>() : new ArrayList<>(pages.get(clone).stream().map(BoardDraft::copy).toList())); return true;
    }
    boolean text(int page, int element, String text) {
        if (page < 0 || page >= count() || text == null || text.codePointCount(0, text.length()) > 240 || text.codePoints().anyMatch(Character::isISOControl)) return false;
        List<Map<String, Object>> items = pages.get(page);
        if (element < 0) {
            if (items.size() >= 45) return false;
            items.add(new LinkedHashMap<>(Map.of("type", "text", "text", text, "x", 8, "y", 8 + items.size() * 22, "size", 18, "color", "white"))); return true;
        }
        if (element >= items.size() || !"text".equalsIgnoreCase(Objects.toString(items.get(element).getOrDefault("type", "text")))) return false;
        items.get(element).put("text", text); return true;
    }
    boolean removeElement(int page, int element) {
        if (page < 0 || page >= count() || element < 0 || element >= pages.get(page).size()) return false;
        pages.get(page).remove(element); return true;
    }
    boolean removePage(int index) {
        if (count() <= 1 || index < 0 || index >= count()) return false;
        remap(old -> old == index + 1 ? -1 : old > index + 1 ? old - 1 : old);
        pages.remove(index); return true;
    }
    boolean movePage(int index, int to) {
        if (index < 0 || to < 0 || index >= count() || to >= count()) return false;
        remap(old -> old == index + 1 ? to + 1 : old == to + 1 ? index + 1 : old);
        Collections.swap(pages, index, to); return true;
    }
    /** Keep click bindings attached to their page, including explicit numeric navigation targets. */
    private void remap(IntUnaryOperator mapping) {
        List<Map<String, Object>> clicks = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList("clicks")) {
            Map<String, Object> click = copy(raw); int page = number(click.get("page"), 0);
            int next = page > 0 ? mapping.applyAsInt(page) : page;
            if (next < 0) continue;
            if (page > 0) click.put("page", next);
            String nav = Objects.toString(click.get("navigate"), "").trim();
            if (nav.matches("[1-9][0-9]*")) {
                int target = number(nav, 0); if (target <= 0) throw new IllegalArgumentException("Invalid navigation target");
                target = mapping.applyAsInt(target); if (target < 0) continue; click.put("navigate", Integer.toString(target));
            }
            clicks.add(click);
        }
        section.set("clicks", clicks);
    }
    private static int number(Object value, int fallback) { try { return Integer.parseInt(Objects.toString(value, "")); } catch (NumberFormatException ignored) { return fallback; } }
    Board board() { section.set("pages", pages); return Board.read(name, section); }
    String yaml() { board(); return document.saveToString(); }
    Save save(AtomicFilesInterface files, Path file) {
        try {
            if (!Files.readString(file, StandardCharsets.UTF_8).equals(original)) return Save.CONFLICT;
            files.write(file, yaml().getBytes(StandardCharsets.UTF_8)); return Save.SAVED;
        } catch (IOException failure) { return Save.FAILED; }
    }
}
