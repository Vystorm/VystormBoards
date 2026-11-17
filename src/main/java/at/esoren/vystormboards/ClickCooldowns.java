package at.esoren.vystormboards;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per player and click area cooldowns ({@code cooldown} in a click area), so reward buttons cannot be spammed. */
final class ClickCooldowns {
    private final Map<UUID, Map<String, Long>> until = new HashMap<>();
    private long nextPrune;

    /** Seconds left (rounded up) before {@code player} may use {@code area} again, 0 when it is free. */
    long remaining(UUID player, String area, long nowMillis) {
        Long end = until.getOrDefault(player, Map.of()).get(area);
        return end == null || end <= nowMillis ? 0 : (end - nowMillis + 999) / 1000;
    }

    void start(UUID player, String area, long nowMillis, int seconds) {
        if (nowMillis >= nextPrune) {
            until.values().forEach(areas -> areas.values().removeIf(end -> end <= nowMillis));
            until.values().removeIf(Map::isEmpty);
            nextPrune = nowMillis + 60_000;
        }
        if (seconds > 0) until.computeIfAbsent(player, id -> new HashMap<>()).put(area, nowMillis + seconds * 1000L);
    }

    /** Drops the player's finished cooldowns (on quit); running ones stay, so relogging does not reset them. */
    void prune(UUID player, long nowMillis) {
        Map<String, Long> areas = until.get(player);
        if (areas == null) return;
        areas.values().removeIf(end -> end <= nowMillis);
        if (areas.isEmpty()) until.remove(player);
    }

    boolean tracks(UUID player) { return until.containsKey(player); }
}
