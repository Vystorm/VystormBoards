package at.esoren.vystormboards;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

/**
 * Course leaderboards of Vystorm Minigames for {@code top} elements with {@code source: "minigames:<course>"}.
 *
 * <p>Boards has no build or load dependency on Minigames: the Bukkit service is found by the NAME of its interface in
 * {@link ServicesManager#getKnownServices()} and called by reflection ({@code docs/leaderboard-source.md} in the
 * Minigames repository). The lookup runs again on every sample, so a Minigames enabled after Boards (or reloaded) is
 * picked up on the next one. Rows are read only by {@link #sample} on the sampling schedule and kept here; drawing
 * reads the kept rows. A missing provider, a provider that throws and anything unexpected give an empty ranking (the
 * element's normal "no data" look) and at most one log line per source for as long as the server runs.
 */
final class MinigamesSource {
    static final String SERVICE = "at.esoren.vystormminigames.api.LeaderboardSource";
    /** The provider caps n at 100; asking for all of them lets the viewer's own place appear below the list. */
    static final int ROWS = 100;

    private final Supplier<ServicesManager> services;
    private final Supplier<Logger> logger;
    /** Sources that already logged a line; never cleared on reload ("one line per source per server run"). */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final Map<String, List<Leaderboards.Entry>> rows = new ConcurrentHashMap<>();
    private final Map<String, List<Leaderboards.Ranked>> sorted = new ConcurrentHashMap<>();
    private Class<?> rowType;
    private Method player, name, value;

    MinigamesSource(Supplier<ServicesManager> services, Supplier<Logger> logger) {
        this.services = services;
        this.logger = logger;
    }

    /**
     * Reads the current rows of every source (server thread, on the sampling timer). {@code onlineName} gives the
     * current name of an online player (or null), so a player who changed their name is still highlighted.
     */
    void sample(Collection<String> sources, Function<UUID, String> onlineName) {
        if (sources.isEmpty()) return;
        Object provider = null;
        Method top = null;
        String missing = "Vystorm Minigames is not running or offers no leaderboards";
        try {
            ServicesManager manager = services.get();
            if (manager != null) {
                for (Class<?> type : manager.getKnownServices()) {
                    if (!type.getName().equals(SERVICE)) continue;
                    RegisteredServiceProvider<?> registration = manager.getRegistration(type);
                    if (registration == null || registration.getProvider() == null) break;
                    top = type.getMethod("top", String.class, int.class);
                    provider = registration.getProvider();
                    break;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            provider = null;
            missing = "the Vystorm Minigames leaderboards cannot be used: " + failure;
        }
        for (String source : new HashSet<>(sources)) {
            List<Leaderboards.Entry> read;
            if (provider == null) {
                read = List.of();
                warnOnce(source, missing);
            } else {
                try {
                    read = read(provider, top, source, onlineName);
                } catch (InvocationTargetException failure) {
                    read = List.of();
                    warnOnce(source, "Vystorm Minigames failed to give it: " + failure.getCause());
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    read = List.of();
                    warnOnce(source, "the Vystorm Minigames leaderboards cannot be used: " + failure);
                }
            }
            List<Leaderboards.Entry> old = rows.put(source, read);
            if (!read.equals(old)) sorted.keySet().removeIf(key -> key.startsWith(source + '\u0000'));
        }
    }

    private List<Leaderboards.Entry> read(Object provider, Method top, String source, Function<UUID, String> onlineName) throws ReflectiveOperationException {
        Object result = top.invoke(provider, source, ROWS);
        if (!(result instanceof List<?> list)) return List.of();
        List<Leaderboards.Entry> out = new ArrayList<>(list.size());
        for (Object row : list) {
            if (row == null) continue;
            if (row.getClass() != rowType) {
                Class<?> type = row.getClass();
                player = type.getMethod("player");
                name = type.getMethod("name");
                value = type.getMethod("value");
                rowType = type;
            }
            UUID id = (UUID) player.invoke(row);
            Object raw = name.invoke(row);
            Number number = (Number) value.invoke(row);
            if (number == null) continue;
            String current = id == null ? null : onlineName.apply(id);
            String shown = current != null ? current : raw == null ? "" : raw.toString();
            if (shown.isBlank() && id != null) shown = id.toString().substring(0, 8);
            out.add(new Leaderboards.Entry(id, shown, number.doubleValue(), out.size()));
        }
        return List.copyOf(out);
    }

    private void warnOnce(String source, String why) {
        if (!warned.add(source)) return;
        Logger log = logger.get();
        if (log != null) log.warning("Ranking source '" + source + "' shows no data: " + why);
    }

    /** The kept ranking of a source (any thread); empty before the first sample and without data. */
    List<Leaderboards.Ranked> ranking(String source, boolean lowest) {
        List<Leaderboards.Entry> entries = rows.get(source);
        if (entries == null || entries.isEmpty()) return List.of();
        return sorted.computeIfAbsent(source + '\u0000' + lowest, key -> Leaderboards.rankInOrder(entries, lowest));
    }

    /** Forgets sources no board uses any more (after a reload). */
    void retain(Collection<String> sources) {
        rows.keySet().retainAll(sources);
        sorted.keySet().removeIf(key -> !sources.contains(key.substring(0, key.indexOf('\u0000'))));
    }
}
