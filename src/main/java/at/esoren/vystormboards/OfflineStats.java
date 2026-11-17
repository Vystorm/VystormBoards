package at.esoren.vystormboards;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;

/**
 * Reads the saved statistics of offline players straight from the vanilla stats files
 * ({@code <world>/players/stats/<uuid>.json}, older worlds {@code <world>/stats/}) without the Bukkit API, so it can run
 * off the server thread. {@link #key} maps a {@code stat:} argument to the category and id used in those files.
 */
final class OfflineStats {
    private OfflineStats() {}

    /** One value in a stats file: {@code stats.<category>.<id>}, e.g. {@code minecraft:custom} / {@code minecraft:play_time}. */
    record Key(String category, String id) {}

    /** Bukkit statistic names whose vanilla id is different (everything else is the lower-case name). */
    private static final Map<Statistic, String> RENAMED = new HashMap<>();

    static {
        rename("ARMOR_CLEANED", "clean_armor");
        rename("BANNER_CLEANED", "clean_banner");
        rename("DROP_COUNT", "drop");
        rename("CAKE_SLICES_EATEN", "eat_cake_slice");
        rename("ITEM_ENCHANTED", "enchant_item");
        rename("CAULDRON_FILLED", "fill_cauldron");
        rename("CAULDRON_USED", "use_cauldron");
        rename("DISPENSER_INSPECTED", "inspect_dispenser");
        rename("DROPPER_INSPECTED", "inspect_dropper");
        rename("HOPPER_INSPECTED", "inspect_hopper");
        rename("BEACON_INTERACTION", "interact_with_beacon");
        rename("BREWINGSTAND_INTERACTION", "interact_with_brewingstand");
        rename("CRAFTING_TABLE_INTERACTION", "interact_with_crafting_table");
        rename("FURNACE_INTERACTION", "interact_with_furnace");
        rename("CHEST_OPENED", "open_chest");
        rename("ENDERCHEST_OPENED", "open_enderchest");
        rename("SHULKER_BOX_OPENED", "open_shulker_box");
        rename("NOTEBLOCK_PLAYED", "play_noteblock");
        rename("NOTEBLOCK_TUNED", "tune_noteblock");
        rename("RECORD_PLAYED", "play_record");
        rename("PLAY_ONE_MINUTE", "play_time");
        rename("FLOWER_POTTED", "pot_flower");
        rename("TRAPPED_CHEST_TRIGGERED", "trigger_trapped_chest");
    }

    private static void rename(String statistic, String id) {
        try { RENAMED.put(Statistic.valueOf(statistic), id); } catch (IllegalArgumentException notInThisVersion) { }
    }

    /** {@code NAME} or {@code NAME:MATERIAL} / {@code NAME:ENTITY}, same rules as the online lookup; throws when it is invalid. */
    static Key key(String argument) {
        String[] parts = argument.split(":", 2);
        Statistic statistic = Statistic.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
        return switch (statistic.getType()) {
            case UNTYPED -> new Key("minecraft:custom", "minecraft:" + RENAMED.getOrDefault(statistic, statistic.name().toLowerCase(Locale.ROOT)));
            case BLOCK, ITEM -> {
                Material material = parts.length < 2 ? null : Material.matchMaterial(parts[1].trim());
                if (material == null) throw new IllegalArgumentException(statistic + " needs a block or item, e.g. " + statistic + ":DIAMOND_ORE");
                yield new Key(category(statistic), material.getKey().toString());
            }
            case ENTITY -> {
                if (parts.length < 2) throw new IllegalArgumentException(statistic + " needs a mob, e.g. " + statistic + ":ZOMBIE");
                yield new Key(category(statistic), EntityType.valueOf(parts[1].trim().toUpperCase(Locale.ROOT)).getKey().toString());
            }
        };
    }

    private static String category(Statistic statistic) {
        return "minecraft:" + switch (statistic.name()) {
            case "MINE_BLOCK" -> "mined";
            case "BREAK_ITEM" -> "broken";
            case "CRAFT_ITEM" -> "crafted";
            case "USE_ITEM" -> "used";
            case "PICKUP" -> "picked_up";
            case "DROP" -> "dropped";
            case "KILL_ENTITY" -> "killed";
            case "ENTITY_KILLED_BY" -> "killed_by";
            default -> throw new IllegalArgumentException("Statistic " + statistic + " cannot be read from saved stats");
        };
    }

    /** The stats folder of a world folder: {@code players/stats} (26.1+), else the older {@code stats}. */
    static Path folder(Path worldFolder) {
        Path modern = worldFolder.resolve("players").resolve("stats");
        return Files.isDirectory(modern) ? modern : worldFolder.resolve("stats");
    }

    /** The values of {@code keys} in one player's stats file; missing values are 0, a missing file gives an empty map. */
    static Map<Key, Long> read(Path folder, UUID player, Collection<Key> keys) throws IOException {
        Path file = folder.resolve(player + ".json");
        Map<Key, Long> values = new HashMap<>();
        if (!Files.isRegularFile(file)) return values;
        JsonElement root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader);
        } catch (RuntimeException broken) {
            throw new IOException("unreadable stats file " + file.getFileName() + ": " + broken.getMessage(), broken);
        }
        JsonObject stats = root.isJsonObject() && root.getAsJsonObject().get("stats") instanceof JsonObject object ? object : null;
        if (stats == null) return values;
        for (Key key : keys) {
            if (!(stats.get(key.category()) instanceof JsonObject category)) continue;
            JsonElement value = category.get(key.id());
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) values.put(key, value.getAsLong());
        }
        return values;
    }
}
