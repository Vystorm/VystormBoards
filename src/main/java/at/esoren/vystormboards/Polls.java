package at.esoren.vystormboards;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Board polls (no server, unit-tested): click areas with {@code vote: poll:option} record one vote per player and poll,
 * texts and progress bars show the results through {@code {votes:...}} placeholders. Saved in polls.yml.
 */
public final class Polls {
    public enum Result { CAST, CHANGED, SAME, LOCKED, CLOSED }

    /** {votes:poll} total, {votes:poll:option} count, {votes:poll:option:%} percent, {vote:poll} the viewer's choice. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(votes?):([a-z0-9_-]{1,32})(?::([a-z0-9_-]{1,32}))?(:%|:percent)?}", Pattern.CASE_INSENSITIVE);
    public static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    private static final class Poll {
        boolean closed;
        long deadline;
        final Map<UUID, String> votes = new LinkedHashMap<>();
    }

    private final Map<String, Poll> polls = new LinkedHashMap<>();
    private boolean dirty;
    private final java.util.function.LongSupplier now;

    public Polls() { this(System::currentTimeMillis); }

    Polls(java.util.function.LongSupplier now) { this.now = now; }

    /** "event:pvp" → ["event", "pvp"] (lower case), null when it is not a valid poll:option pair. */
    public static String[] target(String vote) {
        if (vote == null) return null;
        String[] parts = vote.trim().toLowerCase(Locale.ROOT).split(":", -1);
        return parts.length == 2 && ID.matcher(parts[0]).matches() && ID.matcher(parts[1]).matches() ? parts : null;
    }

    public Result vote(String poll, String option, UUID player, boolean allowChange) {
        Poll state = polls.computeIfAbsent(poll, id -> new Poll());
        if (closed(poll)) return Result.CLOSED;
        String old = state.votes.get(player);
        if (option.equals(old)) return Result.SAME;
        if (old != null && !allowChange) return Result.LOCKED;
        state.votes.put(player, option);
        dirty = true;
        return old == null ? Result.CAST : Result.CHANGED;
    }

    public String choice(String poll, UUID player) {
        Poll state = polls.get(poll);
        return state == null || player == null ? null : state.votes.get(player);
    }

    public int count(String poll, String option) {
        Poll state = polls.get(poll);
        if (state == null) return 0;
        int count = 0;
        for (String vote : state.votes.values()) if (vote.equals(option)) count++;
        return count;
    }

    public int total(String poll) {
        Poll state = polls.get(poll);
        return state == null ? 0 : state.votes.size();
    }

    /** Whole percent of all votes in the poll (0 without votes). */
    public int percent(String poll, String option) {
        int total = total(poll);
        return total == 0 ? 0 : (int) Math.round(count(poll, option) * 100.0 / total);
    }

    /** Options with their vote counts, most votes first. */
    public Map<String, Integer> results(String poll) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Poll state = polls.get(poll);
        if (state != null) for (String vote : state.votes.values()) counts.merge(vote, 1, Integer::sum);
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        Map<String, Integer> out = new LinkedHashMap<>();
        for (var entry : list) out.put(entry.getKey(), entry.getValue());
        return out;
    }

    public boolean exists(String poll) { return polls.containsKey(poll); }

    public boolean closed(String poll) {
        Poll state = polls.get(poll);
        return state != null && (state.closed || state.deadline > 0 && now.getAsLong() >= state.deadline);
    }

    public long deadline(String poll) { Poll state = polls.get(poll); return state == null ? 0 : state.deadline; }

    /** Sets a deadline in epoch milliseconds; zero removes it. The manual closed flag and votes stay unchanged. */
    public void deadline(String poll, long millis) {
        Poll state = polls.computeIfAbsent(poll, id -> new Poll());
        state.deadline = Math.max(0, millis);
        dirty = true;
    }

    /** Opens or closes a poll (creating it if needed). Opening clears its deadline; false means nothing changed. */
    public boolean setClosed(String poll, boolean closed) {
        boolean created = !polls.containsKey(poll);
        if (created) { polls.put(poll, new Poll()); dirty = true; }
        Poll state = polls.computeIfAbsent(poll, id -> new Poll());
        if (state.closed == closed && (closed || state.deadline == 0)) return created;
        state.closed = closed;
        if (!closed) state.deadline = 0;
        dirty = true;
        return true;
    }

    /** Removes all votes; returns how many there were. The open/closed state stays. */
    public int reset(String poll) {
        Poll state = polls.get(poll);
        if (state == null) return 0;
        int removed = state.votes.size();
        state.votes.clear();
        if (removed > 0) dirty = true;
        return removed;
    }

    public Set<String> ids() { return polls.keySet(); }

    public boolean dirty() { return dirty; }
    void markDirty() { dirty = true; }

    /** Fills the poll placeholders in {@code text}; {@code viewer} may be null (then {vote:...} is empty). */
    public String apply(String text, UUID viewer) {
        if (!text.toLowerCase(Locale.ROOT).contains("{vote")) return text;
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String poll = m.group(2).toLowerCase(Locale.ROOT);
            String option = m.group(3) == null ? null : m.group(3).toLowerCase(Locale.ROOT);
            String value;
            if (m.group(1).equalsIgnoreCase("vote")) {
                String choice = choice(poll, viewer);
                value = choice == null ? "-" : choice;
            } else if (option == null) {
                value = Integer.toString(total(poll));
            } else {
                value = m.group(4) != null ? Integer.toString(percent(poll, option)) : Integer.toString(count(poll, option));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ------------------------------------------------------------------ storage (polls.yml)

    public void load(YamlConfiguration yaml) {
        polls.clear();
        ConfigurationSection root = yaml.getConfigurationSection("polls");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null || !ID.matcher(id).matches()) continue;
                Poll poll = new Poll();
                poll.closed = section.getBoolean("closed", false);
                poll.deadline = Math.max(0, section.getLong("deadline", 0));
                ConfigurationSection votes = section.getConfigurationSection("votes");
                if (votes != null) {
                    for (String key : votes.getKeys(false)) {
                        String option = votes.getString(key, "");
                        try {
                            if (ID.matcher(option).matches()) poll.votes.put(UUID.fromString(key), option);
                        } catch (IllegalArgumentException broken) {
                            // not a UUID: skip
                        }
                    }
                }
                polls.put(id, poll);
            }
        }
        dirty = false;
    }

    public YamlConfiguration save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Vystorm Boards – poll votes (one per player). Manage with /boards poll."));
        yaml.set("version", 1);
        yaml.createSection("polls");
        for (var entry : polls.entrySet()) {
            String base = "polls." + entry.getKey();
            yaml.set(base + ".closed", entry.getValue().closed);
            if (entry.getValue().deadline > 0) yaml.set(base + ".deadline", entry.getValue().deadline);
            Map<String, Object> votes = new LinkedHashMap<>();
            for (var vote : entry.getValue().votes.entrySet()) votes.put(vote.getKey().toString(), vote.getValue());
            yaml.createSection(base + ".votes", votes);
        }
        dirty = false;
        return yaml;
    }
}
