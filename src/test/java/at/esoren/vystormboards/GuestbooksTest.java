package at.esoren.vystormboards;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GuestbooksTest {
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();

    @Test
    void givenEntries_whenRendered_thenNewestFirst() {
        Guestbooks g = new Guestbooks();
        assertTrue(g.add("spawn", A, "Ann", "Hello!", 1));
        assertTrue(g.add("spawn", B, "Ben", "Nice build", 2));
        assertEquals("Ben: Nice build", g.apply("{guestbook:spawn:1}"));
        assertEquals("Ann", g.apply("{guestbook:spawn:2:name}"));
        assertEquals("Hello!", g.apply("{guestbook:spawn:2:text}"));
        assertEquals("2", g.apply("{guestbook:spawn:count}"));
        assertEquals("", g.apply("{guestbook:spawn:9}"));
        assertEquals("0 / ", g.apply("{guestbook:other:count} / {guestbook:other:1}"));
    }

    @Test
    void givenUnsafeText_whenAdded_thenCleanedAndLimited() {
        Guestbooks g = new Guestbooks();
        assertFalse(g.add("spawn", A, "Ann", "   ", 1));
        assertFalse(g.add("Bad Id!", A, "Ann", "hi", 1));
        g.add("spawn", A, "Ann", "<red>boom</red>\nline", 1);
        assertEquals(" red boom /red line".strip(), g.entries("spawn").getFirst().text());
        assertEquals(Guestbooks.MAX_TEXT, Guestbooks.clean("x".repeat(100)).length());
    }

    @Test
    void givenManyEntries_whenAdded_thenOldestDropped() {
        Guestbooks g = new Guestbooks();
        for (int i = 0; i < Guestbooks.MAX_ENTRIES + 5; i++) g.add("spawn", A, "Ann", "msg " + i, i);
        assertEquals(Guestbooks.MAX_ENTRIES, g.entries("spawn").size());
        assertEquals("msg " + (Guestbooks.MAX_ENTRIES + 4), g.entries("spawn").getFirst().text());
    }

    @Test
    void givenBook_whenSavedLoadedRemovedCleared_thenConsistent() {
        Guestbooks g = new Guestbooks();
        g.add("spawn", A, "Ann", "one", 1);
        g.add("spawn", B, "Ben", "two", 2);
        Guestbooks back = new Guestbooks();
        back.load(g.save());
        assertEquals(2, back.entries("spawn").size());
        assertEquals("two", back.entries("spawn").getFirst().text());
        assertTrue(back.remove("spawn", 1));
        assertFalse(back.remove("spawn", 5));
        assertEquals("one", back.entries("spawn").getFirst().text());
        assertEquals(1, back.clear("spawn"));
        assertEquals(0, back.clear("spawn"));
    }
}
