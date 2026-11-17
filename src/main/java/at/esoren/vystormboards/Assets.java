package at.esoren.vystormboards;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Images from {@code plugins/VystormBoards/images/} (fitted and dithered once per size, then cached) and player
 * faces (skins downloaded asynchronously from the texture server; until then nothing is drawn).
 */
final class Assets implements Composer.Images {
    private record Key(String file, int frame, int width, int height, boolean dither) {}

    /** A loaded image: one frame for stills, all composed frames for animated GIFs. */
    record Loaded(List<BufferedImage> frames, List<Integer> delays) {
        Loaded(List<BufferedImage> frames, int delay) { this(frames, java.util.Collections.nCopies(frames.size(), delay)); }
        int delay() { return delays.getFirst(); }
    }

    private final Plugin plugin;
    private final Path folder;
    private final MapColors colors;
    private final Logger logger;
    private final Map<Key, BufferedImage> fitted = new ConcurrentHashMap<>();
    private final Map<String, Loaded> originals = new ConcurrentHashMap<>();
    private final Map<String, Boolean> missingLogged = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> faces = new ConcurrentHashMap<>();
    private final Map<String, Object> loadingFaces = new ConcurrentHashMap<>();
    private final Runnable changed;
    private final Vystorm.vystorm_core.compat.PlatformCompatInterface platform;

    Assets(Plugin plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface platform, Path folder, MapColors colors, Runnable changed) {
        this.plugin = plugin;
        this.platform = platform;
        this.folder = folder;
        this.colors = colors;
        this.logger = plugin.getLogger();
        this.changed = changed;
    }

    void clear() {
        fitted.clear();
        originals.clear();
        snapshotRead.clear();
        missingLogged.clear();
    }

    /** Resolves {@code name} inside the images folder; null for paths that try to leave it. */
    static Path inside(Path folder, String name) {
        Path resolved = folder.resolve(name).normalize();
        return resolved.startsWith(folder.normalize()) ? resolved : null;
    }

    /** When each snapshot: source was last read; they are read again after a minute so new photos appear. */
    private final Map<String, Long> snapshotRead = new ConcurrentHashMap<>();

    private Loaded snapshot(String file) {
        if (!plugin.getConfig().getBoolean("snapshot-photos.enabled", true)) return null; // switched off: nothing read or drawn
        long now = System.currentTimeMillis();
        Long read = snapshotRead.get(file);
        if (read != null && now - read < 60_000) return originals.get(file);
        snapshotRead.put(file, now);
        fitted.keySet().removeIf(key -> key.file().equals(file));
        var config = plugin.getConfig();
        Path photos = plugin.getDataFolder().toPath().getParent().resolve("VystormSnapshot").resolve("photos");
        Loaded loaded = SnapshotPhotos.load(photos, file, Math.clamp(config.getInt("snapshot-photos.max", 30), 1, 120), config.getInt("snapshot-photos.seconds", 8));
        if (loaded == null) { originals.remove(file); if (missingLogged.putIfAbsent(file, true) == null) logger.info("No Snapshot photos for board image " + file + " yet"); }
        else { originals.put(file, loaded); missingLogged.remove(file); }
        return loaded;
    }

    private Loaded original(String file) {
        if (SnapshotPhotos.source(file)) return snapshot(file);
        Loaded known = originals.get(file);
        if (known != null) return known;
        try {
            Path path = inside(folder, file);
            Loaded image = path == null || !Files.isRegularFile(path) ? null : read(path);
            if (image == null) {
                if (missingLogged.putIfAbsent(file, true) == null) logger.warning("Board image not found or unreadable: images/" + file);
                return null;
            }
            originals.put(file, image);
            return image;
        } catch (IOException | RuntimeException failure) {
            if (missingLogged.putIfAbsent(file, true) == null) logger.warning("Board image " + file + " could not be read: " + failure.getMessage());
            return null;
        }
    }

    /** Reads stills with ImageIO; GIFs frame by frame, composed on a canvas of the GIF's full size (max. 120 frames). */
    static Loaded read(Path path) throws IOException {
        if (!path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".gif")) {
            BufferedImage still = ImageIO.read(path.toFile());
            return still == null ? null : new Loaded(List.of(still), 100);
        }
        try (javax.imageio.stream.ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            var readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) return null;
            javax.imageio.ImageReader reader = readers.next();
            try {
            reader.setInput(input, false);
            int count = Math.min(120, reader.getNumImages(true));
            BufferedImage first = reader.read(0);
            int width = first.getWidth(), height = first.getHeight(), delay = 100;
            javax.imageio.metadata.IIOMetadata stream = reader.getStreamMetadata();
            if (stream != null) {
                org.w3c.dom.Node screen = child((org.w3c.dom.Node) stream.getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
                if (screen != null) {
                    width = Math.max(width, attribute(screen, "logicalScreenWidth", width));
                    height = Math.max(height, attribute(screen, "logicalScreenHeight", height));
                }
            }
            List<BufferedImage> frames = new java.util.ArrayList<>();
            List<Integer> delays = new java.util.ArrayList<>();
            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int i = 0; i < count; i++) {
                BufferedImage frame = i == 0 ? first : reader.read(i);
                org.w3c.dom.Node tree = (org.w3c.dom.Node) reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                org.w3c.dom.Node descriptor = child(tree, "ImageDescriptor"), control = child(tree, "GraphicControlExtension");
                int left = descriptor == null ? 0 : attribute(descriptor, "imageLeftPosition", 0), top = descriptor == null ? 0 : attribute(descriptor, "imageTopPosition", 0);
                delays.add(control == null ? delay : Math.max(20, attribute(control, "delayTime", 10) * 10));
                String disposal = control == null ? "" : ((org.w3c.dom.Element) control).getAttribute("disposalMethod");
                BufferedImage previous = null;
                if (disposal.equals("restoreToPrevious")) {
                    previous = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D saved = previous.createGraphics();
                    saved.drawImage(canvas, 0, 0, null); saved.dispose();
                }
                Graphics2D g = canvas.createGraphics();
                g.drawImage(frame, left, top, null);
                g.dispose();
                BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D c = copy.createGraphics();
                c.drawImage(canvas, 0, 0, null);
                c.dispose();
                frames.add(copy);
                if (previous != null) canvas = previous;
                else if (disposal.equals("restoreToBackgroundColor")) {
                    Graphics2D clear = canvas.createGraphics();
                    clear.setComposite(java.awt.AlphaComposite.Clear);
                    clear.fillRect(left, top, frame.getWidth(), frame.getHeight());
                    clear.dispose();
                }
            }
            return new Loaded(frames, delays);
            } finally { reader.dispose(); }
        }
    }

    private static org.w3c.dom.Node child(org.w3c.dom.Node node, String name) {
        for (org.w3c.dom.Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) if (name.equals(c.getNodeName())) return c;
        return null;
    }

    private static int attribute(org.w3c.dom.Node node, String name, int fallback) {
        try { return Integer.parseInt(((org.w3c.dom.Element) node).getAttribute(name)); } catch (RuntimeException wrong) { return fallback; }
    }

    @Override
    public int frames(String file) {
        Loaded loaded = file == null || file.isBlank() ? null : original(file);
        return loaded == null ? 1 : loaded.frames().size();
    }

    @Override
    public int delay(String file) {
        Loaded loaded = file == null || file.isBlank() ? null : original(file);
        return loaded == null ? 100 : loaded.delay();
    }

    @Override
    public int frame(String file, long now, int maxFps) {
        Loaded loaded = file == null || file.isBlank() ? null : original(file);
        if (loaded == null || loaded.frames().size() <= 1) return 0;
        long minimum = 1000L / Math.max(1, maxFps), duration = 0;
        for (int delay : loaded.delays()) duration += Math.max(delay, minimum);
        long position = Math.floorMod(now, duration);
        for (int i = 0; i < loaded.delays().size(); i++) {
            position -= Math.max(loaded.delays().get(i), minimum);
            if (position < 0) return i;
        }
        return 0;
    }

    @Override
    public BufferedImage fitted(String file, int frame, int width, int height, boolean dither) {
        if (file == null || file.isBlank() || width <= 0 || height <= 0) return null;
        Loaded loaded = original(file);
        if (loaded == null) return null;
        int index = Math.floorMod(frame, loaded.frames().size());
        Key key = new Key(file, index, width, height, dither);
        BufferedImage cached = fitted.get(key);
        if (cached != null) return cached;
        BufferedImage source = loaded.frames().get(index);
        double scale = Math.min(width / (double) source.getWidth(), height / (double) source.getHeight());
        int dw = Math.max(1, (int) Math.round(source.getWidth() * scale)), dh = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scale < 1 ? RenderingHints.VALUE_INTERPOLATION_BICUBIC : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, dw, dh, null);
        g.dispose();
        BufferedImage result = dither ? colors.dither(scaled) : scaled;
        fitted.put(key, result);
        return result;
    }

    /** 8×8 face (with hat layer) for a player name, or null while it is still loading or unknown. */
    BufferedImage face(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.toLowerCase(java.util.Locale.ROOT);
        BufferedImage known = faces.get(key);
        if (known != null) return known;
        Object request = new Object();
        if (loadingFaces.putIfAbsent(key, request) != null) return null;
        org.bukkit.profile.PlayerProfile profile;
        try {
            Player online = Bukkit.getPlayerExact(name);
            profile = online != null ? platform.getPlayerProfile(online) : Bukkit.createPlayerProfile(name);
        } catch (IllegalArgumentException invalidName) {
            return null; // scoreboard entries need not be valid player names
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var complete = profile.getTextures().getSkin() != null ? profile : profile.update().get();
                URL skin = complete.getTextures().getSkin();
                if (skin == null) return;
                BufferedImage image = ImageIO.read(URI.create(skin.toString()).toURL());
                if (image == null || image.getWidth() < 64) return;
                BufferedImage face = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = face.createGraphics();
                g.drawImage(image.getSubimage(8, 8, 8, 8), 0, 0, null);
                g.drawImage(image.getSubimage(40, 8, 8, 8), 0, 0, null);
                g.dispose();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (loadingFaces.get(key) != request) return;
                    faces.put(key, face);
                    changed.run();
                });
            } catch (Exception failure) {
                logger.fine("Skin for " + name + " not available: " + failure);
            }
        });
        return null;
    }

    void forgetFace(UUID ignored, String name) {
        if (name == null) return;
        String key = name.toLowerCase(java.util.Locale.ROOT);
        faces.remove(key);
        loadingFaces.remove(key);
    }
}
