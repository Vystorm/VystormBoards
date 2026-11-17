package at.esoren.vystormboards;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.function.LongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Board placeholder {@code {countdown:2026-12-24T18:00}} (server time zone): the time left as {@code 04:12:09}, with
 * days in front ({@code 3d 04:12:09}, the day word from the language files). After the moment it shows the text after
 * a {@code |} ({@code {countdown:2026-12-24T18:00|Merry Christmas!}}) or {@code 00:00:00}. A date alone counts to
 * midnight at its start. Unreadable dates stay as they are, so the admin sees the typo on the board.
 */
final class Countdown {
    private static final Pattern PATTERN = Pattern.compile("\\{countdown:([0-9T:.\\-]+)(?:\\|([^}]*))?}");

    private Countdown() {}

    static String apply(String text, Instant now, ZoneId zone, LongFunction<String> days) {
        if (text == null || !text.contains("{countdown:")) return text;
        Matcher matcher = PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Instant target = parse(matcher.group(1), zone);
            String replacement;
            if (target == null) replacement = matcher.group();
            else if (!now.isBefore(target)) replacement = matcher.group(2) != null ? matcher.group(2) : "00:00:00";
            else replacement = format(Duration.between(now, target), days);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static Instant parse(String value, ZoneId zone) {
        try {
            if (value.contains("T")) return LocalDateTime.parse(value).atZone(zone).toInstant();
            return LocalDate.parse(value).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException wrong) {
            return null;
        }
    }

    /** {@code HH:MM:SS}, with {@code days.apply(n) + " "} in front from one day on; seconds are rounded up. */
    static String format(Duration left, LongFunction<String> days) {
        long seconds = (left.toMillis() + 999) / 1000;
        long d = seconds / 86_400, h = seconds / 3600 % 24, m = seconds / 60 % 60, s = seconds % 60;
        String clock = String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", h, m, s);
        return d > 0 ? days.apply(d) + " " + clock : clock;
    }
}
