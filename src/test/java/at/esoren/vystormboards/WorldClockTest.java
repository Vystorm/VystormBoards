package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class WorldClockTest {
    @Test void formatsMinecraftDayBoundaries() {
        assertEquals("06:00", WorldClock.format(0));
        assertEquals("12:00", WorldClock.format(6_000));
        assertEquals("18:00", WorldClock.format(12_000));
        assertEquals("00:00", WorldClock.format(18_000));
        assertEquals("05:59", WorldClock.format(23_999));
        assertEquals("06:00", WorldClock.format(24_000));
    }

    @Test void normalizesNegativeAndLargeTickValuesWithoutOverflow() {
        assertEquals("05:59", WorldClock.format(-1));
        assertEquals(WorldClock.format(Math.floorMod(Long.MAX_VALUE, 24_000L)), WorldClock.format(Long.MAX_VALUE));
        assertEquals(WorldClock.format(Math.floorMod(Long.MIN_VALUE, 24_000L)), WorldClock.format(Long.MIN_VALUE));
    }
}
