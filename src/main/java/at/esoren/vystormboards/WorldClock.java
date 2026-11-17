package at.esoren.vystormboards;

import java.util.Locale;

/** Minecraft time: tick zero is 06:00, with 24,000 ticks per day. */
final class WorldClock {
    private WorldClock() {}

    static String format(long ticks) {
        long minutes = (Math.floorMod(ticks, 24_000L) * 60 / 1_000 + 360) % 1_440;
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** Fills {@code {world_time}}; with the clock switched off (world-clock.enabled) the placeholder is left blank. */
    static String apply(String text, boolean enabled, java.util.function.LongSupplier ticks) {
        if (!text.contains("{world_time}")) return text;
        return text.replace("{world_time}", enabled ? format(ticks.getAsLong()) : "");
    }
}
