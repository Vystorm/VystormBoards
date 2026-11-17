package at.esoren.vystormboards;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Paints a board into one RGB image with Java2D: background, then every element in order. Works headless; all
 * outside data (placeholders, image files, player faces) comes in through the three functions.
 */
public final class Composer {
    /** Image files already fitted into a box (aspect kept) and, if wanted, dithered; null if missing. */
    public interface Images {
        BufferedImage fitted(String file, int frame, int width, int height, boolean dither);

        /** Frames of an animated image (GIF), 1 for still images. */
        default int frames(String file) { return 1; }

        /** Milliseconds per frame of an animated image. */
        default int delay(String file) { return 100; }

        default int frame(String file, long now, int maxFps) {
            int count = frames(file);
            if (count <= 1) return 0;
            long step = Math.max(delay(file), 1000L / Math.max(1, maxFps));
            return (int) Math.floorMod(now / step, count);
        }
    }

    /** Sorted rankings for {@code top} elements (best first); empty when the source is unknown or rankings are off. */
    public interface Rankings {
        List<Leaderboards.Ranked> ranking(String source, boolean lowest);
    }

    private final Images images;
    private final Function<String, BufferedImage> faces;
    private Rankings rankings = (source, lowest) -> List.of();

    public void rankings(Rankings rankings) { this.rankings = rankings; }

    /**
     * @param images image files fitted to their boxes
     * @param faces  player name (empty = viewer, already resolved by the caller) → 8×8 face, or null if unknown
     */
    public Composer(Images images, Function<String, BufferedImage> faces) {
        this.images = images;
        this.faces = faces;
    }

    /** Whole board as RGB pixels, row by row; {@code placeholders} fills {@code {player}} etc. in texts. */
    public int[] render(Board board, UnaryOperator<String> placeholders, String viewer) {
        return render(board, placeholders, viewer, permission -> true, System.currentTimeMillis());
    }

    /** Frame of an animated image at {@code now}; animations run at most {@code maxFps} frames per second. */
    public int frame(String file, long now, int maxFps) {
        return images.frame(file, now, maxFps);
    }

    private int maxFps = 4;

    public void maxFps(int fps) { maxFps = Math.clamp(fps, 1, 20); }

    /**
     * Whole board at {@code now} (pages, animation frames) for a viewer whose permissions are tested by {@code allowed}.
     */
    public int[] render(Board board, UnaryOperator<String> placeholders, String viewer, java.util.function.Predicate<String> allowed, long now) {
        return render(board, placeholders, viewer, allowed, now, board.page(now));
    }

    public int[] render(Board board, UnaryOperator<String> placeholders, String viewer, java.util.function.Predicate<String> allowed, long now, int page) {
        int width = board.pixelWidth(), height = board.pixelHeight();
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(new Color(board.background));
            g.fillRect(0, 0, width, height);
            for (Board.Element element : board.visiblePage(page)) draw(g, element, placeholders, viewer, allowed, now, width, height);
        } finally {
            g.dispose();
        }
        // straight from the raster: getRGB converts every pixel through the colour model (a hotspot on big boards)
        int[] rgb = ((java.awt.image.DataBufferInt) canvas.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < rgb.length; i++) rgb[i] |= 0xFF000000;
        return rgb;
    }

    private void draw(Graphics2D g, Board.Element element, UnaryOperator<String> placeholders, String viewer, java.util.function.Predicate<String> allowed,
                      long now, int width, int height) {
        switch (element) {
            case Board.Gated gated -> {
                boolean negate = gated.permission().startsWith("!");
                boolean has = allowed.test(negate ? gated.permission().substring(1) : gated.permission());
                if (has != negate) draw(g, gated.element(), placeholders, viewer, allowed, now, width, height);
            }
            case Board.Rect rect -> {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, rect.alpha() / 100f));
                g.setColor(new Color(rect.rgb()));
                g.fillRect(rect.x(), rect.y(), rect.w(), rect.h());
                g.setComposite(AlphaComposite.SrcOver);
            }
            case Board.Progress bar -> {
                double max = Board.number(placeholders.apply(bar.max()));
                double value = Board.number(placeholders.apply(bar.value()));
                g.setColor(new Color(bar.backgroundRgb()));
                g.fillRect(bar.x(), bar.y(), bar.w(), bar.h());
                g.setColor(new Color(bar.rgb()));
                g.fillRect(bar.x(), bar.y(), fill(value, max, bar.w()), bar.h());
            }
            case Board.Image image -> drawImage(g, image, width, height, now);
            case Board.Head head -> {
                BufferedImage face = faces.apply(head.player().isBlank() ? viewer : head.player());
                if (face != null) {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g.drawImage(face, head.x(), head.y(), head.size(), head.size(), null);
                }
            }
            case Board.Text text -> drawText(g, text, placeholders.apply(text.text()), width);
            case Board.Top top -> drawTop(g, top, viewer, width);
        }
    }

    private static final int[] MEDALS = {0xFFD700, 0xC0C0C0, 0xCD7F32};
    private static final int HIGHLIGHT = 0xFFFF55;

    /** Rows of a ranking shown by a {@code top} element: the first {@code rows} places, plus the viewer's own when highlighted. */
    static List<Leaderboards.Ranked> rows(List<Leaderboards.Ranked> ranking, Board.Top top, String viewer) {
        List<Leaderboards.Ranked> shown = new java.util.ArrayList<>(ranking.subList(0, Math.min(top.rows(), ranking.size())));
        if (top.highlight() && viewer != null && !viewer.isBlank() && Leaderboards.find(shown, viewer) == null) {
            Leaderboards.Ranked own = Leaderboards.find(ranking, viewer);
            if (own != null) shown.add(own);
        }
        return shown;
    }

    private void drawTop(Graphics2D g, Board.Top top, String viewer, int boardWidth) {
        List<Leaderboards.Ranked> ranking = rankings.ranking(top.source(), top.lowest());
        List<Leaderboards.Ranked> shown = rows(ranking, top, viewer);
        int box = top.w() > 0 ? top.w() : boardWidth - top.x();
        int size = top.size(), rowHeight = (int) Math.round(size * 1.35);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, size >= 14 ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        Font plain = new Font(top.font(), Font.PLAIN, size), bold = new Font(top.font(), Font.BOLD, size);
        g.setFont(bold);
        int rankWidth = g.getFontMetrics().stringWidth("#" + Math.max(10, shown.isEmpty() ? 1 : shown.getLast().rank())) + size / 3;
        for (int i = 0; i < shown.size(); i++) {
            Leaderboards.Ranked ranked = shown.get(i);
            // the viewer's own place below the list is set off by half a row
            int top0 = top.y() + i * rowHeight + (i >= top.rows() ? rowHeight / 2 : 0);
            boolean self = top.highlight() && ranked.name().equalsIgnoreCase(viewer);
            int baseline = top0 + g.getFontMetrics(bold).getAscent();
            int rankColor = top.medals() && ranked.rank() <= 3 ? MEDALS[ranked.rank() - 1] : top.rgb();
            g.setFont(bold);
            g.setColor(new Color(rankColor));
            g.drawString("#" + ranked.rank(), top.x(), baseline);
            int cursor = top.x() + rankWidth;
            if (top.heads()) {
                BufferedImage face = faces.apply(ranked.name());
                int faceSize = Math.max(8, size);
                if (face != null) g.drawImage(face, cursor, top0 + (rowHeight - faceSize) / 2 - size / 10, faceSize, faceSize, null);
                cursor += faceSize + size / 3;
            }
            String value = Leaderboards.format(ranked.value(), top.format());
            int valueWidth = g.getFontMetrics(bold).stringWidth(value);
            g.setFont(plain);
            g.setColor(new Color(self ? HIGHLIGHT : top.rgb()));
            String name = clip(g, ranked.name(), top.x() + box - valueWidth - size / 2 - cursor);
            g.drawString(name, cursor, baseline);
            g.setFont(bold);
            g.setColor(new Color(self ? HIGHLIGHT : top.valueRgb()));
            g.drawString(value, top.x() + box - valueWidth, baseline);
        }
    }

    /** Shortens {@code text} with "…" until it fits {@code max} pixels in the current font. */
    private static String clip(Graphics2D g, String text, int max) {
        FontMetrics metrics = g.getFontMetrics();
        if (metrics.stringWidth(text) <= max) return text;
        String out = text;
        while (!out.isEmpty() && metrics.stringWidth(out + "…") > max) out = out.substring(0, out.length() - 1);
        return out.isEmpty() ? "" : out + "…";
    }

    /** Filled pixels of a bar {@code width} wide for value/max (clamped, 0 for max ≤ 0). */
    static int fill(double value, double max, int width) {
        if (max <= 0) return 0;
        return (int) Math.round(Math.clamp(value / max, 0, 1) * width);
    }

    private void drawImage(Graphics2D g, Board.Image image, int boardWidth, int boardHeight, long now) {
        int w = image.w() > 0 ? image.w() : boardWidth - image.x();
        int h = image.h() > 0 ? image.h() : boardHeight - image.y();
        BufferedImage fitted = images.fitted(image.file(), frame(image.file(), now, maxFps), w, h, image.dither());
        if (fitted == null) return;
        g.drawImage(fitted, image.x() + (w - fitted.getWidth()) / 2, image.y() + (h - fitted.getHeight()) / 2, null);
    }

    /** Font size that makes the widest line fit {@code box} pixels (never above the configured size, at least 6). */
    static int fitted(int size, int widest, int box) {
        if (widest <= box || widest <= 0) return size;
        return Math.max(6, (int) Math.floor(size * (box / (double) widest)));
    }

    private static void drawText(Graphics2D g, Board.Text text, String resolved, int boardWidth) {
        List<List<StyledText.Run>> lines = StyledText.parse(resolved, text.rgb());
        int box = text.width() > 0 ? text.width() : boardWidth - text.x();
        int size = text.size();
        if (text.fit()) {
            int widest = 0;
            for (List<StyledText.Run> line : lines) widest = Math.max(widest, width(g, line, text, size));
            size = fitted(size, widest, box);
        }
        boolean smooth = size >= 14;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, smooth ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        int lineStep = (int) Math.round(size * text.lineHeight());
        int shadow = Math.max(1, size / 12);
        for (int i = 0; i < lines.size(); i++) {
            List<StyledText.Run> line = lines.get(i);
            int lineWidth = width(g, line, text, size);
            int cursor = switch (text.align()) {
                case LEFT -> text.x();
                case CENTER -> text.x() + (box - lineWidth) / 2;
                case RIGHT -> text.x() + box - lineWidth;
            };
            int top = text.y() + i * lineStep;
            for (StyledText.Run run : line) {
                Font font = font(text, run, size);
                g.setFont(font);
                FontMetrics metrics = g.getFontMetrics();
                int baseline = top + metrics.getAscent();
                if (text.shadow()) {
                    g.setColor(new Color(darken(run.rgb())));
                    g.drawString(run.text(), cursor + shadow, baseline + shadow);
                }
                g.setColor(new Color(run.rgb()));
                g.drawString(run.text(), cursor, baseline);
                int advance = metrics.stringWidth(run.text());
                if (run.underlined()) g.fillRect(cursor, baseline + Math.max(1, size / 10), advance, Math.max(1, size / 14));
                cursor += advance;
            }
        }
    }

    private static int width(Graphics2D g, List<StyledText.Run> line, Board.Text text, int size) {
        int width = 0;
        for (StyledText.Run run : line) width += g.getFontMetrics(font(text, run, size)).stringWidth(run.text());
        return width;
    }

    private static Font font(Board.Text text, StyledText.Run run, int size) {
        int style = (run.bold() ? Font.BOLD : 0) | (run.italic() ? Font.ITALIC : 0);
        return new Font(text.font(), style, size);
    }

    static int darken(int rgb) {
        return ((rgb >> 18) & 0x3F) << 16 | ((rgb >> 10) & 0x3F) << 8 | ((rgb >> 2) & 0x3F);
    }
}
