package localization;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommandLanguageRegressionTest {
    @ParameterizedTest
    @ValueSource(strings = {"en", "de", "zh_cn", "fr", "pt_br", "it", "ru"})
    void commandHelpUsesAcceptedKeywordsAndTranslatedButtons(String language) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/lang/" + language + ".yml"), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        }
        assertEquals("north|east|south|west", yaml.getString("commands.boards.sub.create.args.facing"));
        assertTrue(yaml.getString("commands.boards.sub.deadline.args.time").endsWith("|clear"));
        assertTrue(yaml.getString("poll-deadline-invalid").contains("clear"));
        String choices = yaml.getString("commands.boards.sub.poll.args.action");
        for (String action : List.of("close", "open", "reset")) {
            Object value = yaml.get("commands.boards.values." + action);
            String keyword = value instanceof List<?> list ? list.getFirst().toString() : value.toString();
            assertTrue(List.of(choices.split("\\|")).contains(keyword), language + ": missing " + keyword);
        }
        if (!language.equals("en")) {
            for (String leftover : List.of("[Confirm]", "[Close poll]", "[Open poll]", "[Reset votes]", "[Refresh]")) {
                assertFalse(yaml.saveToString().contains(leftover), language + ": " + leftover);
            }
        }
    }
}
