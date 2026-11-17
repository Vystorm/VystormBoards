package at.esoren.vystormboards;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.map.MapPalette;

/**
 * Converts RGB to Minecraft map colour indices. Matching is cached in a flat table on 5-bit-per-channel keys; images can be
 * Floyd–Steinberg dithered once on load so photos and logos keep their gradients on the limited map palette.
 */
public final class MapColors {
    private final byte[] table = new byte[1 << 15];
    private final int[] palette;

    @SuppressWarnings({"deprecation", "removal"})
    public MapColors() {
        List<Integer> colors = new ArrayList<>();
        for (int i = 4; i < 256; i++) {
            try {
                java.awt.Color color = MapPalette.getColor((byte) i);
                if (color.getAlpha() == 255) colors.add(color.getRGB() & 0xFFFFFF);
            } catch (RuntimeException outOfRange) {
                break;
            }
        }
        palette = colors.stream().mapToInt(Integer::intValue).toArray();
    }

    @SuppressWarnings({"deprecation", "removal"})
    public byte match(int rgb) {
        // 5 bits per channel = 32768 slots; 0 (transparent) never comes out of matchColor, so it marks "not known yet".
        // Byte writes are atomic and idempotent, so concurrent callers at worst compute a slot twice.
        int slot = slot(rgb);
        byte known = table[slot];
        if (known != 0) return known;
        int key = (rgb & 0xF8F8F8) | 0x040404;
        byte index = MapPalette.matchColor((key >> 16) & 0xFF, (key >> 8) & 0xFF, key & 0xFF);
        table[slot] = index;
        return index;
    }

    /** Table slot of a colour: its top 5 bits per channel. */
    static int slot(int rgb) { return ((rgb >> 9) & 0x7C00) | ((rgb >> 6) & 0x03E0) | ((rgb >> 3) & 0x001F); }

    public byte[] convert(int[] rgb) {
        byte[] out = new byte[rgb.length];
        for (int i = 0; i < rgb.length; i++) out[i] = match(rgb[i]);
        return out;
    }

    /** Nearest palette colour (exact RGB), used while dithering. */
    int nearest(int r, int g, int b) {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int color : palette) {
            int dr = ((color >> 16) & 0xFF) - r, dg = ((color >> 8) & 0xFF) - g, db = (color & 0xFF) - b;
            long distance = 3L * dr * dr + 4L * dg * dg + 2L * db * db;
            if (distance < bestDistance) { bestDistance = distance; best = color; }
        }
        return best;
    }

    /** Copy of {@code source} reduced to palette colours with Floyd–Steinberg error diffusion. */
    public BufferedImage dither(BufferedImage source) {
        int w = source.getWidth(), h = source.getHeight();
        float[][] r = new float[h][w], g = new float[h][w], b = new float[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int rgb = source.getRGB(x, y);
            r[y][x] = (rgb >> 16) & 0xFF; g[y][x] = (rgb >> 8) & 0xFF; b[y][x] = rgb & 0xFF;
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int alpha = (source.getRGB(x, y) >>> 24);
            int cr = clamp(r[y][x]), cg = clamp(g[y][x]), cb = clamp(b[y][x]);
            int chosen = nearest(cr, cg, cb);
            out.setRGB(x, y, (alpha << 24) | chosen);
            float er = cr - ((chosen >> 16) & 0xFF), eg = cg - ((chosen >> 8) & 0xFF), eb = cb - (chosen & 0xFF);
            spread(r, g, b, x + 1, y, er, eg, eb, 7 / 16f);
            spread(r, g, b, x - 1, y + 1, er, eg, eb, 3 / 16f);
            spread(r, g, b, x, y + 1, er, eg, eb, 5 / 16f);
            spread(r, g, b, x + 1, y + 1, er, eg, eb, 1 / 16f);
        }
        return out;
    }

    private static void spread(float[][] r, float[][] g, float[][] b, int x, int y, float er, float eg, float eb, float f) {
        if (y >= r.length || x < 0 || x >= r[0].length) return;
        r[y][x] += er * f; g[y][x] += eg * f; b[y][x] += eb * f;
    }

    private static int clamp(float v) { return Math.max(0, Math.min(255, Math.round(v))); }
}
