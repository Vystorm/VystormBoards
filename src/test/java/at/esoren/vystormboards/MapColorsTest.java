package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MapColorsTest {
    @Test
    void colourTableSlotsAreOnePerFiveBitKey() {
        Set<Integer> slots = new HashSet<>();
        for (int r = 0; r < 256; r += 8) for (int g = 0; g < 256; g += 8) for (int b = 0; b < 256; b += 8) {
            int rgb = 0xFF000000 | r << 16 | g << 8 | b;
            int slot = MapColors.slot(rgb);
            assertTrue(slot >= 0 && slot < 1 << 15);
            assertTrue(slots.add(slot), "two keys share a slot");
            // the low 3 bits per channel and the alpha byte do not change the slot (same as the old 5-bit cache key)
            assertEquals(slot, MapColors.slot((rgb & 0xFFFFFF) | 0x070707));
        }
        assertEquals(1 << 15, slots.size());
    }
}
