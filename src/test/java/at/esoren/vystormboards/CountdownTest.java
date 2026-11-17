package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CountdownTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final Instant NOW = Instant.parse("2026-12-21T13:47:51Z");

    private static String apply(String text) { return Countdown.apply(text, NOW, UTC, d -> d + "d"); }

    @Test void countsDownWithDaysInFront() {
        assertEquals("Christmas in 3d 04:12:09!", apply("Christmas in {countdown:2026-12-24T18:00}!"));
        assertEquals("02:12:09", apply("{countdown:2026-12-21T16:00}"));
        assertEquals("10:12:09", apply("{countdown:2026-12-22}"), "a date alone counts to its midnight");
    }

    @Test void afterTheMomentTheFallbackOrZero() {
        assertEquals("Merry Christmas!", apply("{countdown:2026-12-20T18:00|Merry Christmas!}"));
        assertEquals("00:00:00", apply("{countdown:2026-12-21T13:47:51}"));
        assertEquals("01:00:00 left", apply("{countdown:2026-12-21T14:47:51|over} left"));
    }

    @Test void typosAndOtherTextsStay() {
        assertEquals("{countdown:2026-13-40T99:00}", apply("{countdown:2026-13-40T99:00}"));
        assertEquals("no placeholder", apply("no placeholder"));
        assertNull(Countdown.apply(null, NOW, UTC, d -> ""));
    }

    @Test void secondsAreRoundedUp() {
        assertEquals("00:00:01", Countdown.format(Duration.ofMillis(10), d -> d + "d"));
        assertEquals("1d 00:00:00", Countdown.format(Duration.ofDays(1), d -> d + "d"));
    }
}
