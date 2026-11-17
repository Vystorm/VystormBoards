package at.esoren.vystormboards;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Photo slideshow boards: an image element whose file is {@code snapshot:gallery} (photos on show in VystormSnapshot's
 * public gallery), {@code snapshot:newest} (the newest photos) or {@code snapshot:player:NAME} (one photographer's
 * photos) shows those photos one after another, like the frames of an animated image. Photos are read from
 * {@code plugins/VystormSnapshot/photos/} (PNG files, album.yml, gallery.yml); parts of big photos are skipped.
 * Read-only; no dependency on the Snapshot plugin itself.
 */
final class SnapshotPhotos {
    static final String PREFIX = "snapshot:";

    private SnapshotPhotos() {}

    static boolean source(String file) { return file != null && file.toLowerCase(Locale.ROOT).startsWith(PREFIX); }

    /** Map ids for a source, newest (highest id) first, at most {@code max}. */
    static List<Integer> select(ConfigurationSection album, ConfigurationSection gallery, String file, int max) {
        String source = file.substring(PREFIX.length()).strip();
        String lower = source.toLowerCase(Locale.ROOT);
        TreeSet<Integer> ids = new TreeSet<>(Comparator.reverseOrder());
        if (lower.equals("gallery")) {
            ConfigurationSection exhibits = gallery == null ? null : gallery.getConfigurationSection("exhibits");
            if (exhibits != null) for (String key : exhibits.getKeys(false))
                if (exhibits.getBoolean(key + ".published", true)) number(key).ifPresent(ids::add);
        } else {
            String player = lower.startsWith("player:") ? source.substring("player:".length()).strip() : null;
            if (!lower.equals("newest") && player == null) return List.of();
            ConfigurationSection photos = album == null ? null : album.getConfigurationSection("photos");
            if (photos != null) for (String key : photos.getKeys(false)) {
                if (!photos.getString(key + ".part", "").isEmpty()) continue;
                if (player != null && !player.equalsIgnoreCase(photos.getString(key + ".name", ""))) continue;
                number(key).ifPresent(ids::add);
            }
        }
        return ids.stream().limit(Math.max(0, max)).toList();
    }

    private static Optional<Integer> number(String key) {
        try { return Optional.of(Integer.parseInt(key)); } catch (NumberFormatException no) { return Optional.empty(); }
    }

    /** The photos of {@code file} as frames, each shown {@code seconds}; null when there are none. */
    static Assets.Loaded load(Path photos, String file, int max, int seconds) {
        if (photos == null || !Files.isDirectory(photos)) return null;
        YamlConfiguration album = YamlConfiguration.loadConfiguration(photos.resolve("album.yml").toFile());
        YamlConfiguration gallery = YamlConfiguration.loadConfiguration(photos.resolve("gallery.yml").toFile());
        List<BufferedImage> frames = new ArrayList<>();
        for (int id : select(album, gallery, file, max)) {
            Path png = photos.resolve(id + ".png");
            if (!Files.isRegularFile(png)) continue;
            try { BufferedImage image = ImageIO.read(png.toFile()); if (image != null) frames.add(image); }
            catch (java.io.IOException | RuntimeException unreadable) { /* skip this photo */ }
        }
        if (frames.isEmpty()) return null;
        return new Assets.Loaded(List.copyOf(frames), Math.clamp(seconds, 2, 600) * 1000);
    }
}
