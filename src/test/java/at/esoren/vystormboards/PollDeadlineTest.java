package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PollDeadlineTest {
    private final AtomicLong now = new AtomicLong(1_000);
    private final Polls polls = new Polls(now::get);
    private final UUID player = UUID.randomUUID();

    @Test
    void closesExactlyAtDeadlineForNewAndChangedVotes() {
        polls.deadline("event", 2_000);
        now.set(1_999);
        assertFalse(polls.closed("event"));
        assertEquals(Polls.Result.CAST, polls.vote("event", "a", player, true));
        now.set(2_000);
        assertTrue(polls.closed("event"));
        assertEquals(Polls.Result.CLOSED, polls.vote("event", "b", player, true));
        assertEquals(Polls.Result.CLOSED, polls.vote("event", "a", UUID.randomUUID(), true));
        assertEquals("a", polls.choice("event", player));
        assertEquals(1, polls.total("event"));
        assertFalse(polls.closed("other"));
    }

    @Test
    void survivesRestartPastDeadline() throws Exception {
        polls.vote("event", "a", player, true);
        polls.deadline("event", 2_000);
        assertTrue(polls.dirty());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(polls.save().saveToString());
        now.set(3_000);
        Polls loaded = new Polls(now::get);
        loaded.load(yaml);
        assertFalse(loaded.dirty());
        assertEquals(2_000, loaded.deadline("event"));
        assertEquals("a", loaded.choice("event", player));
        assertEquals(Polls.Result.CLOSED, loaded.vote("event", "b", player, true));
    }

    @Test
    void openingClearsDeadlineButResetKeepsIt() {
        polls.vote("event", "a", player, true);
        polls.deadline("event", 2_000);
        now.set(2_000);
        assertEquals(1, polls.reset("event"));
        assertEquals(2_000, polls.deadline("event"));
        assertTrue(polls.closed("event"));
        assertTrue(polls.setClosed("event", false));
        assertEquals(0, polls.deadline("event"));
        assertEquals(Polls.Result.CAST, polls.vote("event", "b", player, true));
    }

    @Test
    void clearingOrExtendingDeadlineDoesNotUndoManualClose() {
        polls.setClosed("event", true);
        polls.deadline("event", 9_000);
        assertTrue(polls.closed("event"));
        polls.save();
        polls.deadline("event", 0);
        assertTrue(polls.dirty());
        assertTrue(polls.closed("event"));
        assertEquals(0, polls.deadline("event"));
    }

    @Test
    void clearingExpiredDeadlineReopensUnlessManuallyClosed() {
        polls.deadline("event", 1_000);
        assertTrue(polls.closed("event"));
        polls.deadline("event", 0);
        assertFalse(polls.closed("event"));
        assertEquals(Polls.Result.CAST, polls.vote("event", "a", player, true));
    }

    @Test
    void oldFilesKeepTheirOpenAndClosedStates() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("polls:\n  open:\n    closed: false\n  closed:\n    closed: true\n");
        polls.load(yaml);
        assertEquals(0, polls.deadline("open"));
        assertFalse(polls.closed("open"));
        assertTrue(polls.closed("closed"));
        assertEquals(0, polls.deadline("unknown"));
    }
}
