package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LangTest {
    private static final String[] FILES = {"en.yml", "de.yml", "zh_cn.yml"};
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[a-z_-]+}|%[^%\\s]+%");

    private static YamlConfiguration load(String name) { return YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + name)); }

    private static Set<String> placeholders(String text) {
        Set<String> found = new TreeSet<>();
        PLACEHOLDER.matcher(text).results().forEach(match -> found.add(match.group()));
        return found;
    }

    @Test
    void everyLanguageHasTheSameKeysAndPlaceholders() {
        YamlConfiguration en = load("en.yml");
        for (String file : FILES) {
            YamlConfiguration lang = load(file);
            assertEquals(en.getKeys(true), lang.getKeys(true), file);
            for (String key : en.getKeys(true))
                assertEquals(placeholders(en.getString(key, "")), placeholders(lang.getString(key, "")), file + " " + key);
        }
    }

    @Test
    void noRawArgumentTags() {
        for (String file : FILES) {
            YamlConfiguration lang = load(file);
            for (String key : lang.getKeys(true)) {
                String text = lang.getString(key, "");
                assertFalse(text.matches(".*<(?!/?(gray|dark_gray|gold|yellow|green|red|white)>)[a-z_-]+>.*"), file + " " + key + ": " + text);
            }
        }
    }
}
