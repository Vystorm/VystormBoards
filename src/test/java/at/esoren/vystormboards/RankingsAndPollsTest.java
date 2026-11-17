package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class RankingsAndPollsTest {
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID NOTCH = UUID.fromString("00000000-0000-0000-0000-000000000003");

    // ------------------------------------------------------------------ rankings

    @Test
    void sourceKinds() {
        assertEquals("stat", Leaderboards.kind("stat:PLAY_ONE_MINUTE"));
        assertEquals("placeholder", Leaderboards.kind("placeholder:%vault_eco_balance%"));
        assertEquals("objective", Leaderboards.kind("Objective:kills"));
        assertEquals("", Leaderboards.kind("kills"));
        assertEquals("", Leaderboards.kind("stat:"));
        assertEquals("", Leaderboards.kind(null));
        assertEquals("MINE_BLOCK:DIAMOND_ORE", Leaderboards.argument("stat:MINE_BLOCK:DIAMOND_ORE"));
    }

    @Test
    void rankingSortsAndSharesRanksOnTies() {
        Leaderboards boards = new Leaderboards();
        boards.put("stat:JUMP", STEVE, "Steve", 50, 1, 100);
        boards.put("stat:JUMP", ALEX, "Alex", 80, 1, 100);
        boards.put("stat:JUMP", NOTCH, "Notch", 50, 1, 100);
        List<Leaderboards.Ranked> high = boards.ranking("stat:JUMP", false);
        assertEquals(List.of(new Leaderboards.Ranked(1, "Alex", 80), new Leaderboards.Ranked(2, "Notch", 50), new Leaderboards.Ranked(2, "Steve", 50)), high);
        List<Leaderboards.Ranked> low = boards.ranking("stat:JUMP", true);
        assertEquals(1, low.getFirst().rank());
        assertEquals(3, low.getLast().rank());
        assertEquals("Alex", low.getLast().name());
        assertEquals(2, Leaderboards.find(high, "steve").rank());
        assertNull(Leaderboards.find(high, "Herobrine"));
    }

    @Test
    void putRefreshesTheSortedCacheAndCapsPlayers() {
        Leaderboards boards = new Leaderboards();
        boards.put("stat:JUMP", STEVE, "Steve", 10, 1, 2);
        boards.put("stat:JUMP", ALEX, "Alex", 5, 2, 2);
        assertEquals("Steve", boards.ranking("stat:JUMP", false).getFirst().name());
        assertTrue(boards.put("stat:JUMP", ALEX, "Alex", 20, 3, 2));
        assertEquals("Alex", boards.ranking("stat:JUMP", false).getFirst().name());
        assertFalse(boards.put("stat:JUMP", ALEX, "Alex", 20, 4, 2));
        boards.put("stat:JUMP", NOTCH, "Notch", 1, 5, 2);   // third player: Steve was seen longest ago
        assertEquals(2, boards.size("stat:JUMP"));
        assertNull(Leaderboards.find(boards.ranking("stat:JUMP", false), "Steve"));
    }

    @Test
    void rankingsSurviveSaveAndLoad() throws Exception {
        Leaderboards boards = new Leaderboards();
        boards.put("placeholder:%vault_eco_balance%", STEVE, "Steve", 1234.5, 99, 100);
        boards.put("stat:PLAY_ONE_MINUTE", ALEX, "Alex", 72_000, 98, 100);
        boards.markSeeded("stat:PLAY_ONE_MINUTE");
        assertTrue(boards.dirty());
        String text = boards.save().saveToString();
        assertFalse(boards.dirty());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        Leaderboards loaded = new Leaderboards();
        loaded.load(yaml);
        assertEquals(1234.5, loaded.ranking("placeholder:%vault_eco_balance%", false).getFirst().value(), 1e-9);
        assertEquals("Alex", loaded.ranking("stat:PLAY_ONE_MINUTE", false).getFirst().name());
        assertTrue(loaded.seeded("stat:PLAY_ONE_MINUTE"));
        assertFalse(loaded.dirty());
        loaded.clear("stat:PLAY_ONE_MINUTE");
        assertFalse(loaded.seeded("stat:PLAY_ONE_MINUTE"));
        assertTrue(loaded.ranking("stat:PLAY_ONE_MINUTE", false).isEmpty());
    }

    @Test
    void brokenRowsAreSkipped() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                sources:
                  - source: "stat:JUMP"
                    players:
                      not-a-uuid: {name: X, value: 3}
                      00000000-0000-0000-0000-000000000001: {name: Steve, value: 7}
                      00000000-0000-0000-0000-000000000002: {name: Alex, value: lots}
                  - source: "nonsense"
                    players: {}
                """);
        Leaderboards boards = new Leaderboards();
        boards.load(yaml);
        assertEquals(List.of(new Leaderboards.Ranked(1, "Steve", 7)), boards.ranking("stat:JUMP", false));
        assertEquals(1, boards.sources().size());
    }

    @Test
    void valueFormats() {
        assertEquals("1,234", Leaderboards.format(1234, "number"));
        assertEquals("12.5", Leaderboards.format(12.5, null));
        assertEquals("1,234.57", Leaderboards.format(1234.567, "decimal"));
        assertEquals("2h 5m", Leaderboards.format(125 * 60 * 20, "time"));
        assertEquals("3d 4h", Leaderboards.format((3 * 24 + 4) * 3600, "seconds"));
        assertEquals("45m", Leaderboards.format(45 * 60, "seconds"));
        assertEquals("340 m", Leaderboards.format(34_000, "distance"));
        assertEquals("1.5 km", Leaderboards.format(150_000, "distance"));
        assertEquals("Play one minute", Leaderboards.label("stat:PLAY_ONE_MINUTE"));
        assertEquals("Vault eco balance", Leaderboards.label("placeholder:%vault_eco_balance%"));
    }

    @Test
    void topElementIsReadAndShowsTheViewerBelowTheList() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                width: 2
                height: 1
                elements:
                  - {type: top, source: "stat:PLAY_ONE_MINUTE", x: 8, y: 8, rows: 2, format: time, label: Playtime}
                pages:
                  - [{type: top, source: "objective:kills", rows: 3, order: lowest, highlight: true, permission: vip}]
                """);
        Board board = Board.read("t", yaml);
        List<Board.Top> tops = board.tops();
        assertEquals(2, tops.size());
        assertEquals("Playtime", tops.getFirst().label());
        assertFalse(tops.getFirst().lowest());
        assertTrue(tops.get(1).lowest());
        assertTrue(board.perPlayer());
        Board shared = Board.read("s", yamlOf("""
                elements:
                  - {type: top, source: "stat:JUMP"}
                """));
        assertFalse(shared.perPlayer());

        List<Leaderboards.Ranked> ranking = List.of(new Leaderboards.Ranked(1, "A", 9), new Leaderboards.Ranked(2, "B", 8), new Leaderboards.Ranked(3, "Steve", 7));
        Board.Top top = tops.getFirst();
        assertEquals(2, Composer.rows(ranking, top, "Steve").size());
        Board.Top mine = new Board.Top("stat:JUMP", 0, 0, 0, 2, 16, 0xFFFFFF, 0xFFFFFF, false, true, "number", false, true, "SansSerif", "");
        assertEquals("Steve", Composer.rows(ranking, mine, "Steve").getLast().name());
        assertEquals(2, Composer.rows(ranking, mine, "A").size());
    }

    @Test
    void composerDrawsRankingRows() {
        Board.Top top = new Board.Top("stat:JUMP", 0, 0, 128, 3, 16, 0xFFFFFF, 0x00FF00, false, true, "number", false, false, "SansSerif", "");
        Board board = new Board("r", "w", 0, 0, 0, 0, -1, 1, 1, true, 0x000000, 100, List.of(top), List.of(), List.of());
        Composer composer = new Composer((file, frame, w, h, dither) -> null, name -> null);
        int[] empty = composer.render(board, s -> s, "");
        composer.rankings((source, lowest) -> List.of(new Leaderboards.Ranked(1, "Steve", 42)));
        int[] filled = composer.render(board, s -> s, "");
        boolean gold = false, value = false;
        for (int y = 0; y < 22; y++) for (int x = 0; x < 128; x++) {
            int rgb = filled[y * 128 + x] & 0xFFFFFF;
            if (rgb == 0xFFD700) gold = true;     // rank 1 in medal gold
            if (rgb == 0x00FF00) value = true;    // value colour, right aligned
        }
        assertTrue(gold);
        assertTrue(value);
        for (int pixel : empty) assertEquals(0, pixel & 0xFFFFFF);
    }

    private static YamlConfiguration yamlOf(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    // ------------------------------------------------------------------ polls

    @Test
    void voteTargets() {
        assertArrayEquals(new String[]{"event", "pvp"}, Polls.target(" Event:PvP "));
        assertNull(Polls.target("event"));
        assertNull(Polls.target("a:b:c"));
        assertNull(Polls.target("bad poll:x"));
        assertNull(Polls.target(null));
    }

    @Test
    void oneVotePerPlayerAndPoll() {
        Polls polls = new Polls();
        assertEquals(Polls.Result.CAST, polls.vote("event", "pvp", STEVE, true));
        assertEquals(Polls.Result.SAME, polls.vote("event", "pvp", STEVE, true));
        assertEquals(Polls.Result.CAST, polls.vote("event", "build", ALEX, true));
        assertEquals(Polls.Result.CAST, polls.vote("event", "build", NOTCH, false));
        assertEquals(Polls.Result.LOCKED, polls.vote("event", "pvp", NOTCH, false));
        assertEquals(Polls.Result.CHANGED, polls.vote("event", "build", STEVE, true));
        assertEquals(3, polls.count("event", "build"));
        assertEquals(0, polls.count("event", "pvp"));
        assertEquals(100, polls.percent("event", "build"));
        assertEquals(Polls.Result.CAST, polls.vote("other", "yes", STEVE, true));   // polls are independent
        assertTrue(polls.setClosed("event", true));
        assertFalse(polls.setClosed("event", true));
        assertEquals(Polls.Result.CLOSED, polls.vote("event", "pvp", ALEX, true));
        assertEquals(3, polls.reset("event"));
        assertEquals(0, polls.total("event"));
        assertTrue(polls.closed("event"));
    }

    @Test
    void pollPlaceholders() {
        Polls polls = new Polls();
        polls.vote("event", "pvp", STEVE, true);
        polls.vote("event", "pvp", ALEX, true);
        polls.vote("event", "build", NOTCH, true);
        assertEquals("PvP: 2 (67 %) of 3", polls.apply("PvP: {votes:event:pvp} ({votes:event:pvp:%} %) of {votes:event}", null));
        assertEquals("33", polls.apply("{votes:Event:Build:percent}", null));
        assertEquals("you: build / -", polls.apply("you: {vote:event} / {vote:other}", NOTCH));
        assertEquals("-", polls.apply("{vote:event}", null));
        assertEquals("{player} {votes} {vote:Bad Id}", polls.apply("{player} {votes} {vote:Bad Id}", STEVE));
        assertEquals(Map.of("pvp", 2, "build", 1), polls.results("event"));
        assertEquals("pvp", polls.results("event").keySet().iterator().next());
    }

    @Test
    void votesSurviveSaveAndLoad() throws Exception {
        Polls polls = new Polls();
        polls.vote("event", "pvp", STEVE, true);
        polls.vote("map", "desert", ALEX, true);
        polls.setClosed("map", true);
        String text = polls.save().saveToString();
        Polls loaded = new Polls();
        loaded.load(yamlOf(text));
        assertEquals("pvp", loaded.choice("event", STEVE));
        assertTrue(loaded.closed("map"));
        assertEquals(1, loaded.total("map"));
        assertFalse(loaded.dirty());
        Polls broken = new Polls();
        broken.load(yamlOf("polls:\n  event:\n    votes:\n      nope: pvp\n      00000000-0000-0000-0000-000000000001: 'bad option'\n"));
        assertEquals(0, broken.total("event"));
    }

    @Test
    void voteAreasAndPollMentionsAreRead() throws Exception {
        Board board = Board.read("v", yamlOf("""
                elements:
                  - {type: text, text: "PvP {votes:event:pvp}"}
                  - {type: progress, value: "{votes:event:build}", max: "{votes:event}"}
                clicks:
                  - {x: 0, y: 0, width: 64, height: 64, vote: "event:pvp"}
                  - {x: 64, y: 0, width: 64, height: 64, command: "spawn"}
                """));
        assertEquals("event:pvp", board.clicks.getFirst().vote());
        assertEquals("", board.clicks.get(1).vote());
        assertTrue(board.mentions("{votes:event"));
        assertFalse(board.mentions("{votes:map"));
        assertEquals("||||||....", BoardsPlugin.bar(62));
        assertNotEquals(BoardsPlugin.bar(0), BoardsPlugin.bar(100));
    }
}
