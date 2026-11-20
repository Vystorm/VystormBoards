package at.esoren.vystormboards;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Remembered ranking values for the {@code top} board element (no server, unit-tested). Sources are
 * {@code stat:NAME[:QUALIFIER]} and {@code placeholder:%...%}, sampled from online players and kept for offline ones;
 * {@code objective:NAME} rankings come live from the scoreboard and {@code minigames:COURSE} rankings from Vystorm
 * Minigames ({@link MinigamesSource}); both only use the sorting helpers here.
 */
public final class Leaderboards {
    /** One player's value in a source; {@code seen} is when it was last sampled (oldest entries go first at the cap). */
    public record Entry(UUID id, String name, double value, long seen) {}

    /** A place in a ranking: equal values share a rank (1, 2, 2, 4). */
    public record Ranked(int rank, String name, double value) {}

    private final Map<String, Map<UUID, Entry>> sources = new LinkedHashMap<>();
    private final Map<String, List<Ranked>> sorted = new HashMap<>();
    private final Set<String> seeded = new HashSet<>();
    private boolean dirty;

    /** "stat", "placeholder", "objective", "minigames" or "" for an unknown source. */
    public static String kind(String source) {
        if (source == null) return "";
        int colon = source.indexOf(':');
        if (colon <= 0 || colon == source.length() - 1) return "";
        String kind = source.substring(0, colon).toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "stat", "placeholder", "objective", "minigames" -> kind;
            default -> "";
        };
    }

    /** Part after "kind:" ("PLAY_ONE_MINUTE", "%vault_eco_balance%", "kills"). */
    public static String argument(String source) { return source.substring(source.indexOf(':') + 1); }

    /** Records a value; returns true if it changed. Stored sources are capped at {@code max} players (least recently seen dropped). */
    public boolean put(String source, UUID id, String name, double value, long now, int max) {
        Map<UUID, Entry> entries = sources.computeIfAbsent(source, s -> new HashMap<>());
        Entry old = entries.get(id);
        if (old != null && old.seen() > now) return false;
        entries.put(id, new Entry(id, name, value, now));
        boolean changed = old == null || old.value() != value || !old.name().equals(name);
        if (old == null || old.seen() != now) dirty = true;
        while (max > 0 && entries.size() > max) {
            entries.values().stream().min(Comparator.comparingLong(Entry::seen)).ifPresent(oldest -> entries.remove(oldest.id()));
            changed = true;
        }
        if (!changed) return false;
        sorted.keySet().removeIf(key -> key.startsWith(source + '\u0000'));
        dirty = true;
        return true;
    }

    /** Whole ranking of a stored source, best first ({@code lowest}: smallest value first, e.g. fastest times). */
    public List<Ranked> ranking(String source, boolean lowest) {
        return sorted.computeIfAbsent(source + '\u0000' + lowest, key -> rank(sources.getOrDefault(source, Map.of()).values(), lowest));
    }

    /** Sorts entries by value (ties by name) and gives equal values the same rank. */
    public static List<Ranked> rank(Collection<Entry> entries, boolean lowest) {
        Comparator<Entry> byValue = Comparator.comparingDouble(Entry::value);
        if (!lowest) byValue = byValue.reversed();
        List<Entry> list = new ArrayList<>(entries);
        list.sort(byValue.thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT)));
        return places(list);
    }

    /**
     * Like {@link #rank} for rows that arrive already in a meaningful order (Minigames: of equal times the earlier one
     * first): sorted by value only, so equal values keep their order; they still share a rank.
     */
    public static List<Ranked> rankInOrder(List<Entry> entries, boolean lowest) {
        Comparator<Entry> byValue = Comparator.comparingDouble(Entry::value);
        List<Entry> list = new ArrayList<>(entries);
        list.sort(lowest ? byValue : byValue.reversed());
        return places(list);
    }

    private static List<Ranked> places(List<Entry> list) {
        List<Ranked> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Entry entry = list.get(i);
            int rank = i > 0 && list.get(i - 1).value() == entry.value() ? out.get(i - 1).rank() : i + 1;
            out.add(new Ranked(rank, entry.name(), entry.value()));
        }
        return List.copyOf(out);
    }

    /** Place of {@code name} (case-insensitive) in a ranking, or null. */
    public static Ranked find(List<Ranked> ranking, String name) {
        if (name == null || name.isBlank()) return null;
        for (Ranked ranked : ranking) if (ranked.name().equalsIgnoreCase(name)) return ranked;
        return null;
    }

    public int size(String source) { return sources.getOrDefault(source, Map.of()).size(); }

    /** Forgets a source (admin reset); its offline statistics are read again. */
    public void clear(String source) {
        if (sources.remove(source) != null) dirty = true;
        if (seeded.remove(source)) dirty = true;
        sorted.keySet().removeIf(key -> key.startsWith(source + '\u0000'));
    }

    /** Whether the saved statistics of offline players were already read for this stat source. */
    public boolean seeded(String source) { return seeded.contains(source); }

    public void markSeeded(String source) { if (seeded.add(source)) dirty = true; }

    public Set<String> sources() { return sources.keySet(); }

    public boolean dirty() { return dirty; }
    void markDirty() { dirty = true; }

    // ------------------------------------------------------------------ formatting

    /**
     * Value as shown on the board: {@code number} (1,234), {@code decimal} (1,234.56), {@code time} (statistic ticks →
     * "3d 4h", "2h 5m", "45m"), {@code seconds} (same from seconds), {@code distance} (centimetres → "1.2 km", "340 m"),
     * {@code duration} (milliseconds → "1:01.234", from one hour on "1:02:03.456"; course times of Vystorm Minigames).
     */
    public static String format(double value, String format) {
        String kind = format == null ? "number" : format.toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "decimal" -> String.format(Locale.ROOT, "%,.2f", value);
            case "time" -> duration((long) (value / 20));
            case "seconds" -> duration((long) value);
            case "duration" -> millis(Math.round(value));
            case "distance" -> value >= 100_000 ? String.format(Locale.ROOT, "%,.1f km", value / 100_000) : String.format(Locale.ROOT, "%,d m", (long) (value / 100));
            default -> value == Math.rint(value) ? String.format(Locale.ROOT, "%,d", (long) value) : String.format(Locale.ROOT, "%,.1f", value);
        };
    }

    /** Milliseconds as m:ss.SSS, or h:mm:ss.SSS from one hour on. */
    static String millis(long millis) {
        long ms = Math.max(0, millis), hours = ms / 3_600_000, minutes = ms / 60_000 % 60, seconds = ms / 1000 % 60, rest = ms % 1000;
        if (hours > 0) return String.format(Locale.ROOT, "%d:%02d:%02d.%03d", hours, minutes, seconds, rest);
        return String.format(Locale.ROOT, "%d:%02d.%03d", minutes, seconds, rest);
    }

    static String duration(long seconds) {
        long minutes = Math.max(0, seconds) / 60, hours = minutes / 60, days = hours / 24;
        if (days > 0) return days + "d " + hours % 24 + "h";
        if (hours > 0) return hours + "h " + minutes % 60 + "m";
        return minutes + "m";
    }

    /** Readable name of a source when no label is set: "stat:PLAY_ONE_MINUTE" → "Play one minute". */
    public static String label(String source) {
        String argument = kind(source).isEmpty() ? source : argument(source);
        String plain = argument.replace("%", "").replace(':', ' ').replace('_', ' ').trim().toLowerCase(Locale.ROOT);
        return plain.isEmpty() ? source : Character.toUpperCase(plain.charAt(0)) + plain.substring(1);
    }

    // ------------------------------------------------------------------ storage (leaderboards.yml)

    /** Reads leaderboards.yml; unknown or broken rows are skipped. */
    public void load(YamlConfiguration yaml) {
        sources.clear();
        sorted.clear();
        seeded.clear();
        seeded.addAll(yaml.getStringList("seeded"));
        for (Map<?, ?> raw : yaml.getMapList("sources")) {
            String source = String.valueOf(raw.get("source"));
            if (kind(source).isEmpty() || !(raw.get("players") instanceof Map<?, ?> players)) continue;
            Map<UUID, Entry> entries = sources.computeIfAbsent(source, s -> new HashMap<>());
            for (var player : players.entrySet()) {
                try {
                    UUID id = UUID.fromString(String.valueOf(player.getKey()));
                    Map<?, ?> row = player.getValue() instanceof Map<?, ?> map ? map
                            : player.getValue() instanceof ConfigurationSection section ? section.getValues(false) : Map.of();
                    Object name = row.get("name"), value = row.get("value"), seen = row.get("seen");
                    if (name == null || !(value instanceof Number number)) continue;
                    entries.put(id, new Entry(id, name.toString(), number.doubleValue(), seen instanceof Number s ? s.longValue() : 0));
                } catch (IllegalArgumentException broken) {
                    // not a UUID: skip the row
                }
            }
        }
        dirty = false;
    }

    public YamlConfiguration save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Vystorm Boards – remembered ranking values for \"top\" elements. Do not edit while the server runs."));
        yaml.set("version", 1);
        yaml.set("seeded", new ArrayList<>(seeded));
        List<Map<String, Object>> list = new ArrayList<>();
        for (var source : sources.entrySet()) {
            Map<String, Object> players = new LinkedHashMap<>();
            for (Entry entry : source.getValue().values()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", entry.name());
                row.put("value", entry.value());
                row.put("seen", entry.seen());
                players.put(entry.id().toString(), row);
            }
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("source", source.getKey());
            section.put("players", players);
            list.add(section);
        }
        yaml.set("sources", list);
        dirty = false;
        return yaml;
    }
}
