package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.commands.CommandRegistration;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.commands.CommandTesting;
import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** /boards through Core's command tree with the real language files: names, routing, completion and help. */
class BoardsCommandsTest {
    private static final String[] FILES = {"en.yml", "de.yml", "zh_cn.yml"};
    private final List<String> chat = new ArrayList<>();

    private static String read(String name) throws Exception {
        try (InputStream in = BoardsCommandsTest.class.getResourceAsStream("/lang/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private CommandRegistration registration(BoardsPlugin handlers) throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn("VystormBoards");
        Lang lang = Lang.create("VystormBoards", Map.of("en", read("en.yml"), "de", read("de.yml"), "zh_cn", read("zh_cn.yml")));
        return CommandTesting.detached(plugin, lang, BoardsPlugin.commandSpec(handlers));
    }

    private <T extends CommandSender> T capture(T sender) {
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(sender).sendMessage(any(Component.class));
        return sender;
    }

    private Player player(Locale locale, String... permissions) {
        Player p = mock(Player.class);
        when(p.locale()).thenReturn(locale);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.hasPermission(anyString())).thenAnswer(i -> List.of(permissions).contains((String) i.getArgument(0)));
        return capture(p);
    }

    private ConsoleCommandSender console() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        return capture(console);
    }

    @Test
    void givenLanguages_whenCompleting_thenSubcommandsInPlayersLanguage() throws Exception {
        CommandRegistration r = registration(null);
        String[] none = {""};
        assertEquals(List.of("edit", "create", "info", "list", "deadline", "poll", "guestbook", "photos", "reload", "remove", "render", "top", "help"),
                r.suggest(player(Locale.US, "vystormboards.admin"), "boards", none));
        assertEquals(List.of("edit", "erstellen", "info", "liste", "frist", "umfrage", "guestbook", "fotos", "neuladen", "entfernen", "darstellung", "rangliste", "hilfe"),
                r.suggest(player(Locale.GERMANY, "vystormboards.admin"), "boards", none));
        assertEquals(List.of("edit", "创建", "信息", "列表", "截止", "投票", "guestbook", "照片", "重载", "移除", "渲染", "排行", "帮助"),
                r.suggest(player(Locale.SIMPLIFIED_CHINESE, "vystormboards.admin"), "boards", none));
        assertEquals(List.of(), r.suggest(player(Locale.US), "boards", none), "without vystormboards.admin nothing");
        assertEquals(List.of("schließen", "öffnen", "zurücksetzen"),
                r.suggest(player(Locale.GERMANY, "vystormboards.admin"), "boards", new String[]{"umfrage", "wahl", ""}));
    }

    @Test
    void deadlinesRouteLocalizedCommandsAndRequireAdmin() throws Exception {
        BoardsPlugin p = mock(BoardsPlugin.class);
        CommandRegistration r = registration(p);
        Player german = player(Locale.GERMANY, "vystormboards.admin");
        r.execute(german, "boards", new String[]{"frist", "event", "2026-12-24T18:00"});
        verify(p).deadline(eq(german), aryEq(new String[]{"deadline", "event", "2026-12-24T18:00"}));
        Player chinese = player(Locale.SIMPLIFIED_CHINESE, "vystormboards.admin");
        r.execute(chinese, "boards", new String[]{"截止", "event", "clear"});
        verify(p).deadline(eq(chinese), aryEq(new String[]{"deadline", "event", "clear"}));
        ConsoleCommandSender console = console();
        r.execute(console, "boards", new String[]{"deadline", "event"});
        verify(p).deadline(eq(console), aryEq(new String[]{"deadline", "event"}));
        Player guest = player(Locale.US);
        r.execute(guest, "boards", new String[]{"deadline", "event", "clear"});
        verify(p, never()).deadline(eq(guest), any());
    }

    @Test
    void givenOldEnglishCommands_whenRun_thenSameHandlersWithSameArguments() throws Exception {
        BoardsPlugin p = mock(BoardsPlugin.class);
        CommandRegistration r = registration(p);
        Player admin = player(Locale.US, "vystormboards.admin");
        r.execute(admin, "boards", new String[]{"reload"});
        verify(p).reload(admin);
        r.execute(admin, "boards", new String[]{"LIST"});
        verify(p).list(admin);
        r.execute(admin, "boards", new String[]{"create", "Lobby", "4", "3"});
        verify(p).create(eq(admin), aryEq(new String[]{"create", "Lobby", "4", "3"}));
        r.execute(admin, "boards", new String[]{"info", "lobby"});
        verify(p).info(eq(admin), aryEq(new String[]{"info", "lobby"}));
        r.execute(admin, "boards", new String[]{"remove", "lobby"});
        verify(p).remove(eq(admin), aryEq(new String[]{"remove", "lobby"}));
        r.execute(admin, "boards", new String[]{"render"});
        verify(p).render(admin);
        r.execute(admin, "boards", new String[]{"top"});
        verify(p).top(eq(admin), aryEq(new String[]{"top"}));
        r.execute(admin, "boards", new String[]{"top", "kills", "reset", "confirm"});
        verify(p).top(eq(admin), aryEq(new String[]{"top", "kills", "reset", "confirm"}));
        r.execute(admin, "boards", new String[]{"poll"});
        verify(p).poll(eq(admin), aryEq(new String[]{"poll"}));
        r.execute(admin, "boards", new String[]{"poll", "vote", "close"});
        verify(p).poll(eq(admin), aryEq(new String[]{"poll", "vote", "close"}));
        r.execute(admin, "boards", new String[]{"poll", "vote", "open"});
        verify(p).poll(eq(admin), aryEq(new String[]{"poll", "vote", "open"}));
        r.execute(admin, "boards", new String[]{"poll", "vote", "reset", "confirm"});
        verify(p).poll(eq(admin), aryEq(new String[]{"poll", "vote", "reset", "confirm"}));
    }

    @Test
    void givenLocalizedCommands_whenRun_thenEnglishKeywordsReachTheHandler() throws Exception {
        BoardsPlugin p = mock(BoardsPlugin.class);
        CommandRegistration r = registration(p);
        Player german = player(Locale.GERMANY, "vystormboards.admin");
        r.execute(german, "anzeigetafeln", new String[]{"umfrage", "vote", "zurücksetzen", "bestätigen"});
        verify(p).poll(eq(german), aryEq(new String[]{"poll", "vote", "reset", "confirm"}));
        Player chinese = player(Locale.SIMPLIFIED_CHINESE, "vystormboards.admin");
        r.execute(chinese, "看板", new String[]{"排行", "kills", "重置"});
        verify(p).top(eq(chinese), aryEq(new String[]{"top", "kills", "reset"}));
        r.execute(chinese, "看板", new String[]{"liste"});
        verify(p).list(chinese);
    }

    @Test
    void givenConsole_whenCreatingWithPosition_thenAllArgumentsPassed() throws Exception {
        BoardsPlugin p = mock(BoardsPlugin.class);
        CommandRegistration r = registration(p);
        ConsoleCommandSender console = console();
        String[] args = {"create", "spawn", "2", "2", "world", "10", "64", "-5", "north"};
        r.execute(console, "boards", args);
        verify(p).create(eq(console), aryEq(args));
        r.execute(console, "boards", new String[]{"list"});
        verify(p).list(console);
    }

    @Test
    void givenNoPermission_whenRun_thenRefusedAndNothingRuns() throws Exception {
        BoardsPlugin p = mock(BoardsPlugin.class);
        registration(p).execute(player(Locale.GERMANY), "boards", new String[]{"reload"});
        verify(p, never()).reload(any());
        assertEquals("Dafür hast du keine Berechtigung.", chat.get(0));
    }

    @Test
    void givenHelp_whenGerman_thenLocalizedUsageAndDescriptions() throws Exception {
        registration(null).execute(player(Locale.GERMANY, "vystormboards.admin"), "anzeigetafeln", new String[]{"hilfe"});
        registration(null).execute(player(Locale.GERMANY, "vystormboards.admin"), "anzeigetafeln", new String[]{"hilfe", "2"});
        String all = String.join("\n", chat);
        assertTrue(all.contains("/anzeigetafeln liste – Alle Tafeln mit Größe und Position"), all);
        assertTrue(all.contains("/anzeigetafeln entfernen NAME – Eine Tafel samt ihren Rahmen entfernen"), all);
    }

    @Test
    void givenRootAliases_whenRead_thenGermanAndChinese() throws Exception {
        Lang lang = registration(null).lang();
        assertEquals("anzeigetafeln", lang.raw("de", "commands.boards.aliases"));
        assertEquals("看板", lang.raw("zh_cn", "commands.boards.aliases"));
    }

    @Test
    void givenTree_whenChecked_thenNoNameConflicts() throws Exception {
        assertEquals(List.of(), registration(null).conflicts());
    }

    @Test
    void givenLanguageFiles_whenCompared_thenSameCommandKeysAndEveryNodeDescribed() {
        Set<String> english = null;
        List<CommandSpec> nodes = new ArrayList<>();
        collect(BoardsPlugin.commandSpec(null), nodes);
        for (String file : FILES) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + file));
            Set<String> keys = new TreeSet<>(yaml.getConfigurationSection("commands").getKeys(true));
            if (english == null) english = keys; else assertEquals(english, keys, file);
            for (CommandSpec node : nodes) {
                assertFalse(yaml.getString(node.langPath() + ".description", "").isBlank(), file + " " + node.langPath());
                assertTrue(yaml.contains(node.langPath() + ".aliases"), file + " " + node.langPath());
            }
        }
    }

    private static void collect(CommandSpec node, List<CommandSpec> out) {
        out.add(node);
        for (CommandSpec child : node.children().values()) collect(child, out);
    }
}
