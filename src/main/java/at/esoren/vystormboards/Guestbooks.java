package at.esoren.vystormboards;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Guestbook boards (no server, unit-tested): a click area with {@code guestbook: ID} lets players leave a short line;
 * texts show the newest entries through {@code {guestbook:ID:N}} (N = 1 for the newest), {@code {guestbook:ID:N:name}},
 * {@code {guestbook:ID:N:text}} and {@code {guestbook:ID:count}}. Plain text only (MiniMessage tags are escaped).
 * At most {@link #MAX_ENTRIES} entries per book, oldest dropped first. Saved in guestbooks.yml.
 */
public final class Guestbooks {
    public static final Pattern ID = Polls.ID;
    public static final int MAX_ENTRIES = 50, MAX_TEXT = 60;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{guestbook:([a-z0-9_-]{1,32}):(count|\\d{1,2})(?::(name|text))?}", Pattern.CASE_INSENSITIVE);

    public record Entry(UUID player, String name, String text, long time) {}

    private final Map<String, Deque<Entry>> books = new LinkedHashMap<>();
    private boolean dirty;

    /** Plain, single-line text: no control characters, no '<' or '>' (so it cannot inject formatting), at most {@link #MAX_TEXT}. */
    public static String clean(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        for (char c : raw.toCharArray()) out.append(Character.isISOControl(c) || c == '<' || c == '>' || c == '§' ? ' ' : c);
        String s = out.toString().replaceAll("\\s{2,}", " ").strip();
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT).strip() : s;
    }

    /** Adds an entry (newest first); false when the text is empty after cleaning or the id is invalid. */
    public boolean add(String book, UUID player, String name, String text, long now) {
        String clean = clean(text);
        if (clean.isEmpty() || book == null || !ID.matcher(book).matches()) return false;
        Deque<Entry> entries = books.computeIfAbsent(book, b -> new ArrayDeque<>());
        entries.addFirst(new Entry(player, name, clean, now));
        while (entries.size() > MAX_ENTRIES) entries.removeLast();
        dirty = true;
        return true;
    }

    /** Newest first. */
    public List<Entry> entries(String book) { return List.copyOf(books.getOrDefault(book, new ArrayDeque<>())); }

    /** Removes the N-th newest entry (1-based); false when there is none. */
    public boolean remove(String book, int index) {
        Deque<Entry> entries = books.get(book);
        if (entries == null || index < 1 || index > entries.size()) return false;
        List<Entry> list = new ArrayList<>(entries);
        list.remove(index - 1);
        entries.clear();
        entries.addAll(list);
        dirty = true;
        return true;
    }

    public int clear(String book) {
        Deque<Entry> entries = books.remove(book);
        if (entries == null) return 0;
        dirty = true;
        return entries.size();
    }

    public Set<String> ids() { return books.keySet(); }
    public boolean dirty() { return dirty; }
    public void markDirty() { dirty = true; }

    /** Replaces the guestbook placeholders in a board text. */
    public String apply(String text) {
        if (text == null || !text.contains("{guestbook:")) return text;
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String book = m.group(1).toLowerCase(Locale.ROOT), what = m.group(2).toLowerCase(Locale.ROOT), part = m.group(3);
            List<Entry> entries = entries(book);
            String value;
            if (what.equals("count")) value = Integer.toString(entries.size());
            else {
                int n = Integer.parseInt(what);
                Entry e = n >= 1 && n <= entries.size() ? entries.get(n - 1) : null;
                value = e == null ? "" : part == null ? e.name() + ": " + e.text() : part.equalsIgnoreCase("name") ? e.name() : e.text();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    public void load(YamlConfiguration yaml) {
        books.clear();
        ConfigurationSection all = yaml.getConfigurationSection("guestbooks");
        if (all == null) return;
        for (String book : all.getKeys(false)) {
            if (!ID.matcher(book).matches()) continue;
            Deque<Entry> entries = new ArrayDeque<>();
            for (Map<?, ?> raw : all.getMapList(book)) {
                try {
                    entries.addLast(new Entry(UUID.fromString(String.valueOf(raw.get("player"))), String.valueOf(raw.get("name")),
                            clean(String.valueOf(raw.get("text"))), Long.parseLong(String.valueOf(raw.get("time")))));
                } catch (RuntimeException skipped) { /* broken entry */ }
                if (entries.size() >= MAX_ENTRIES) break;
            }
            if (!entries.isEmpty()) books.put(book, entries);
        }
        dirty = false;
    }

    public YamlConfiguration save() {
        YamlConfiguration yaml = new YamlConfiguration();
        books.forEach((book, entries) -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Entry e : entries) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("player", e.player().toString());
                m.put("name", e.name());
                m.put("text", e.text());
                m.put("time", e.time());
                list.add(m);
            }
            yaml.set("guestbooks." + book, list);
        });
        dirty = false;
        return yaml;
    }
}
