package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import at.esoren.vystormminigames.api.LeaderboardSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

/** Rankings lowest first, the duration format and course leaderboards read from Vystorm Minigames by reflection. */
class MinigamesLeaderboardsTest {
    private static final String COURSE = "minigames:spawn_run";
    private static final List<String> NAMES = List.of("Ada", "Bob", "Cleo", "Dan", "Eve");

    // ------------------------------------------------------------------ order

    @Test
    void ascendingPutsTheLowestValueFirstForStoredSources() throws Exception {
        Board board = Board.read("a", yaml("""
                elements:
                  - {type: top, source: "stat:DEATHS", order: ascending}
                  - {type: top, source: "stat:DEATHS", order: descending}
                  - {type: top, source: "placeholder:%vault_eco_balance%", order: ASCENDING}
                """));
        List<Board.Top> tops = board.tops();
        assertTrue(tops.get(0).lowest());
        assertFalse(tops.get(1).lowest());
        assertTrue(tops.get(2).lowest());

        Leaderboards boards = new Leaderboards();
        boards.put("stat:DEATHS", UUID.randomUUID(), "Steve", 12, 1, 100);
        boards.put("stat:DEATHS", UUID.randomUUID(), "Alex", 3, 1, 100);
        boards.put("stat:DEATHS", UUID.randomUUID(), "Notch", 7, 1, 100);
        List<String> ascending = boards.ranking("stat:DEATHS", tops.get(0).lowest()).stream().map(Leaderboards.Ranked::name).toList();
        assertEquals(List.of("Alex", "Notch", "Steve"), ascending);
        List<String> descending = boards.ranking("stat:DEATHS", tops.get(1).lowest()).stream().map(Leaderboards.Ranked::name).toList();
        assertEquals(List.of("Steve", "Notch", "Alex"), descending);
    }

    @Test
    void defaultOrderDependsOnTheSourceKind() throws Exception {
        Board board = Board.read("d", yaml("""
                elements:
                  - {type: top, source: "minigames:spawn_run"}
                  - {type: top, source: "stat:PLAY_ONE_MINUTE"}
                  - {type: top, source: "placeholder:%vault_eco_balance%"}
                  - {type: top, source: "objective:kills"}
                  - {type: top, source: "minigames:spawn_run", order: descending}
                  - {type: top, source: "minigames:spawn_run", order: sideways}
                  - {type: top, source: "stat:JUMP", order: lowest}
                  - {type: top, source: "stat:JUMP", order: highest}
                """));
        List<Boolean> lowest = board.tops().stream().map(Board.Top::lowest).toList();
        assertEquals(List.of(true, false, false, false, false, true, true, false), lowest);
    }

    // ------------------------------------------------------------------ format

    @Test
    void durationShowsMillisecondsAsMinutesSecondsAndMillis() {
        assertEquals("1:01.234", Leaderboards.format(61_234, "duration"));
        assertEquals("0:00.999", Leaderboards.format(999, "duration"));
        assertEquals("1:00:00.000", Leaderboards.format(3_600_000, "duration"));
        assertEquals("59:59.999", Leaderboards.format(3_599_999, "duration"));
        assertEquals("1:02:03.456", Leaderboards.format(3_723_456, "Duration"));
        assertEquals("0:00.000", Leaderboards.format(-5, "duration"));
        // the existing formats are unchanged
        assertEquals("1,234", Leaderboards.format(1234, "number"));
        assertEquals("1,234.50", Leaderboards.format(1234.5, "decimal"));
        assertEquals("2h 5m", Leaderboards.format(125 * 60 * 20, "time"));
        assertEquals("45m", Leaderboards.format(45 * 60, "seconds"));
        assertEquals("340 m", Leaderboards.format(34_000, "distance"));
    }

    // ------------------------------------------------------------------ provider

    @Test
    void missingProviderGivesNoDataWithoutAnException() {
        Log log = new Log();
        ServicesManager services = mock(ServicesManager.class);
        when(services.getKnownServices()).thenReturn(Set.<Class<?>>of(Runnable.class));
        MinigamesSource source = new MinigamesSource(() -> services, () -> log.logger);
        source.sample(List.of(COURSE), id -> null);
        source.sample(List.of(COURSE), id -> null);
        assertTrue(source.ranking(COURSE, true).isEmpty());
        assertEquals(1, log.lines.size(), log.lines.toString());
        assertTrue(log.lines.getFirst().contains(COURSE));

        MinigamesSource noServer = new MinigamesSource(() -> null, () -> log.logger);
        noServer.sample(List.of(COURSE), id -> null);
        assertTrue(noServer.ranking(COURSE, true).isEmpty());
        assertTrue(new MinigamesSource(() -> services, () -> log.logger).ranking("minigames:never_sampled", true).isEmpty());
    }

    @Test
    void fakeProviderIsFoundByNameAndItsFiveRowsRenderInOrder() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(UUID.nameUUIDFromBytes(("p" + i).getBytes()));
        List<String> keys = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        LeaderboardSource provider = (key, n) -> {
            keys.add(key);
            sizes.add(n);
            if (!key.equals(COURSE)) return List.of();
            List<LeaderboardSource.Row> rows = new ArrayList<>();
            for (int i = 0; i < 5; i++) rows.add(new LeaderboardSource.Row(ids.get(i), NAMES.get(i), 40_000L + i * 1_500L));
            return List.copyOf(rows);
        };
        Log log = new Log();
        MinigamesSource source = new MinigamesSource(() -> services(provider), () -> log.logger);
        // Eve is online under a new name; everyone else is offline and still listed
        source.sample(List.of(COURSE, "minigames:unknown"), id -> id.equals(ids.get(4)) ? "EveNew" : null);

        assertEquals(Set.of(COURSE, "minigames:unknown"), Set.copyOf(keys));
        assertEquals(List.of(MinigamesSource.ROWS, MinigamesSource.ROWS), sizes);
        List<Leaderboards.Ranked> ranking = source.ranking(COURSE, true);
        assertEquals(List.of("Ada", "Bob", "Cleo", "Dan", "EveNew"), ranking.stream().map(Leaderboards.Ranked::name).toList());
        assertEquals(List.of(1, 2, 3, 4, 5), ranking.stream().map(Leaderboards.Ranked::rank).toList());
        assertEquals("0:40.000", Leaderboards.format(ranking.getFirst().value(), "duration"));
        assertEquals("EveNew", source.ranking(COURSE, false).getFirst().name());
        assertTrue(source.ranking("minigames:unknown", true).isEmpty());
        assertTrue(log.lines.isEmpty(), log.lines.toString());

        // a top element over these rows: five rows in order, the viewer highlighted from the same rows
        Board.Top top = new Board.Top(COURSE, 0, 0, 256, 3, 16, 0xFFFFFF, 0x00FF00, false, true, "duration", true, true, "SansSerif", "");
        List<Leaderboards.Ranked> shown = Composer.rows(source.ranking(COURSE, top.lowest()), top, "Dan");
        assertEquals(List.of("Ada", "Bob", "Cleo", "Dan"), shown.stream().map(Leaderboards.Ranked::name).toList());
        assertEquals(4, shown.getLast().rank());

        Board.Top five = new Board.Top(COURSE, 0, 0, 256, 5, 16, 0xFFFFFF, 0x00FF00, false, true, "duration", true, false, "SansSerif", "");
        Board board = new Board("m", "w", 0, 0, 0, 0, -1, 2, 1, true, 0x000000, 100, List.of(five), List.of(), List.of());
        Composer composer = new Composer((file, frame, w, h, dither) -> null, name -> null);
        int[] empty = composer.render(board, s -> s, "");
        composer.rankings(source::ranking);
        int[] filled = composer.render(board, s -> s, "");
        for (int pixel : empty) assertEquals(0, pixel & 0xFFFFFF);
        int rowsWithValue = 0;
        int rowHeight = (int) Math.round(16 * 1.35);
        for (int row = 0; row < 6; row++) {
            boolean value = false;
            for (int y = row * rowHeight; y < Math.min(128, (row + 1) * rowHeight); y++)
                for (int x = 0; x < 256; x++) if ((filled[y * 256 + x] & 0xFFFFFF) == 0x00FF00) value = true;
            if (value) rowsWithValue++;
        }
        assertEquals(5, rowsWithValue);
    }

    @Test
    void providerThatThrowsGivesNoDataAndOneLogLine() {
        LeaderboardSource provider = (key, n) -> { throw new IllegalStateException("broken course file"); };
        Log log = new Log();
        MinigamesSource source = new MinigamesSource(() -> services(provider), () -> log.logger);
        source.sample(List.of(COURSE), id -> null);
        source.sample(List.of(COURSE), id -> null);
        source.sample(List.of(COURSE), id -> null);
        assertTrue(source.ranking(COURSE, true).isEmpty());
        assertEquals(1, log.lines.size(), log.lines.toString());
        assertTrue(log.lines.getFirst().contains("broken course file"));
    }

    @Test
    void providerThatAppearsLaterIsPickedUpOnTheNextSample() {
        LeaderboardSource provider = (key, n) -> List.of(new LeaderboardSource.Row(UUID.randomUUID(), "Ada", 1234));
        ServicesManager[] current = {services(null)};
        Log log = new Log();
        MinigamesSource source = new MinigamesSource(() -> current[0], () -> log.logger);
        source.sample(List.of(COURSE), id -> null);
        assertTrue(source.ranking(COURSE, true).isEmpty());
        current[0] = services(provider);
        source.sample(List.of(COURSE), id -> null);
        assertEquals("Ada", source.ranking(COURSE, true).getFirst().name());
        // and gone again when Minigames is disabled
        current[0] = services(null);
        source.sample(List.of(COURSE), id -> null);
        assertTrue(source.ranking(COURSE, true).isEmpty());
        assertEquals(1, log.lines.size(), log.lines.toString());
    }

    @Test
    void equalTimesKeepTheProviderOrderAndShareARank() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        LeaderboardSource provider = (key, n) -> List.of(new LeaderboardSource.Row(first, "Zed", 5000), new LeaderboardSource.Row(second, "Abe", 5000));
        MinigamesSource source = new MinigamesSource(() -> services(provider), () -> new Log().logger);
        source.sample(List.of(COURSE), id -> null);
        List<Leaderboards.Ranked> ranking = source.ranking(COURSE, true);
        assertEquals(List.of("Zed", "Abe"), ranking.stream().map(Leaderboards.Ranked::name).toList());
        assertEquals(List.of(1, 1), ranking.stream().map(Leaderboards.Ranked::rank).toList());
    }

    /** Boards' main code may name the Minigames type only as a string: a direct reference would tie the plugins together. */
    @Test
    void mainCodeDoesNotReferToTheMinigamesType() throws IOException {
        assertEquals("at.esoren.vystormminigames.api.LeaderboardSource", MinigamesSource.SERVICE);
        assertEquals(MinigamesSource.SERVICE, LeaderboardSource.class.getName());
        List<String> offending = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            for (Path file : files.filter(p -> Files.isRegularFile(p) && p.toString().matches(".*\\.(java|yml|yaml|json|properties)")).toList()) {
                for (String line : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
                    if (line.contains("vystormminigames") && !line.contains("static final String SERVICE = \"at.esoren.vystormminigames.api.LeaderboardSource\";"))
                        offending.add(file + ": " + line.trim());
                }
            }
        }
        assertEquals(List.of(), offending);
        assertFalse(Files.exists(Path.of("build/classes/java/main/at/esoren/vystormminigames")));
        String pluginYml = Files.readString(Path.of("src/main/resources/plugin.yml"));
        assertFalse(pluginYml.toLowerCase(java.util.Locale.ROOT).contains("minigames"));
    }

    // ------------------------------------------------------------------ helpers

    /** A services manager that knows the stand-in interface by its class (null provider = not registered). */
    private static ServicesManager services(LeaderboardSource provider) {
        ServicesManager services = mock(ServicesManager.class);
        Collection<Class<?>> known = provider == null ? List.of() : List.of(Runnable.class, LeaderboardSource.class);
        when(services.getKnownServices()).thenReturn(known);
        if (provider != null) {
            RegisteredServiceProvider<LeaderboardSource> registration =
                    new RegisteredServiceProvider<>(LeaderboardSource.class, provider, ServicePriority.Normal, mock(Plugin.class));
            when(services.getRegistration(LeaderboardSource.class)).thenAnswer(call -> registration);
        }
        return services;
    }

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    private static final class Log {
        final List<String> lines = new ArrayList<>();
        final Logger logger = Logger.getAnonymousLogger();

        Log() {
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override public void publish(LogRecord record) { lines.add(record.getMessage()); }
                @Override public void flush() { }
                @Override public void close() { }
            });
        }
    }
}
