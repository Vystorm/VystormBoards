package at.esoren.vystormboards;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Every feature of the plugin with its on/off key in config.yml. All are on by default, so a config written before a
 * key existed behaves as before. The description shown in {@code /vadmin features} is the language key
 * {@code switches.<id>} (see {@link #description()}).
 */
enum Feature {
    /** Master switch: off shows no board, takes no click and answers every command but reload with the off message. */
    BOARDS("boards.enabled"),
    FRAME_PROTECTION("frame-protection.enabled"),
    IMAGES("images.enabled"),
    ANIMATIONS("animated-images.enabled"),
    TEXT("text.enabled"),
    RECTANGLES("rectangles.enabled"),
    HEADS("player-heads.enabled"),
    PROGRESS("progress-bars.enabled"),
    PLACEHOLDERS("placeholders.enabled"),
    COUNTDOWNS("countdowns.enabled"),
    /** Older key that keeps its name; declared as a feature. */
    PLACEHOLDERAPI("placeholderapi"),
    PER_PLAYER("per-player.enabled"),
    PAGES("pages.enabled"),
    CLICKS("click-areas.enabled"),
    PACKETS("packet-mode.enabled"),
    RANKINGS("leaderboards.enabled"),
    /** Older key that keeps its name; declared as a feature. */
    RANKINGS_MENU("leaderboards.player-menu"),
    /** Older key that keeps its name; declared as a feature. */
    OFFLINE_STATS("leaderboards.seed-offline-stats"),
    POLLS("polls.enabled"),
    /** Older key that keeps its name; declared as a feature. */
    VOTE_CHANGE("polls.allow-change"),
    POLL_DEADLINES("polls.deadlines.enabled"),
    GUESTBOOKS("guestbooks.enabled"),
    PHOTOS("snapshot-photos.enabled"),
    CLOCK("world-clock.enabled"),
    NAVIGATION("page-navigation.enabled"),
    EDITOR("page-editor.enabled"),
    RENDER_INFO("render-diagnostics.enabled"),
    /** Read once when the plugin starts. */
    TUTORIAL("tutorial.enabled"),
    /** Older key that keeps its name; declared as a feature. */
    GLOW("defaults.glow");

    final String path;

    Feature(String path) { this.path = path; }

    /** Language key of the one-line description. */
    String description() { return "switches." + name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    /** Applies only after a server restart. */
    boolean needsRestart() { return this == TUTORIAL; }

    /** The switches as read from a config; a missing key counts as on. */
    static final class Switches {
        private final Set<Feature> off = EnumSet.noneOf(Feature.class);

        static Switches read(ConfigurationSection config) {
            Switches switches = new Switches();
            if (config != null) for (Feature feature : values()) if (!config.getBoolean(feature.path, true)) switches.off.add(feature);
            return switches;
        }

        /** Everything on, except the given features. */
        static Switches without(Feature... off) {
            Switches switches = new Switches();
            switches.off.addAll(List.of(off));
            return switches;
        }

        boolean on(Feature feature) { return !off.contains(feature); }

        Set<Feature> off() { return off.isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(off); }

        @Override public boolean equals(Object other) { return other instanceof Switches s && s.off.equals(off); }

        @Override public int hashCode() { return off.hashCode(); }
    }
}
