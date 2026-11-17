package at.esoren.vystormboards;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;

/** One board as written in boards.yml: where it hangs, what it shows, which areas are clickable. */
public final class Board {
    /** Drawable content, painted in list order (later elements on top). Coordinates are board pixels. */
    public sealed interface Element permits Rect, Image, Text, Head, Progress, Gated, Top {}

    public record Rect(int x, int y, int w, int h, int rgb, int alpha) implements Element {}

    public record Image(String file, int x, int y, int w, int h, boolean dither) implements Element {}

    public enum Align { LEFT, CENTER, RIGHT }

    public record Text(String text, int x, int y, int width, int size, Align align, int rgb, boolean shadow, String font, double lineHeight, boolean fit) implements Element {}

    /** Bar filled to {@code value}/{@code max}; both may contain placeholders (resolved, then read as numbers). */
    public record Progress(int x, int y, int w, int h, String value, String max, int rgb, int backgroundRgb) implements Element {}

    /** An element shown only to viewers with {@code permission} (or without it when the permission starts with "!"). */
    public record Gated(String permission, Element element) implements Element {}

    /** A player's face; {@code player} empty = whoever looks at the board. */
    public record Head(int x, int y, int size, String player) implements Element {}

    /**
     * A ranking ({@code source}: stat:…, placeholder:…, objective:…) drawn as {@code rows} lines of rank, face, name and
     * value. {@code lowest} ranks the smallest value first; {@code highlight} marks the viewer and adds their own place
     * below the list when they are not in it (makes the board per player). {@code label} names it in menus and chat.
     */
    public record Top(String source, int x, int y, int w, int rows, int size, int rgb, int valueRgb, boolean heads, boolean medals,
                      String format, boolean lowest, boolean highlight, String font, String label) implements Element {}

    /**
     * A clickable area; {@code command} runs as the player or the console, {@code message} is sent to the player.
     * {@code page} (1-based, 0 = every page) and {@code permission} ("!" negates) limit when and for whom it reacts;
     * {@code cooldown} is in seconds per player. {@code vote} ("poll:option") makes it a poll button.
     * {@code navigate} (next, previous, auto or a page number) takes precedence over other actions.
     * {@code guestbook} (an id) opens a dialog to write a line into that guestbook.
     */
    public record Click(int x, int y, int w, int h, String command, boolean console, String message, int page, String permission, int cooldown, String vote, String navigate, String guestbook) {
        public Click(int x, int y, int w, int h, String command, boolean console, String message, int page, String permission, int cooldown, String vote, String navigate) {
            this(x, y, w, h, command, console, message, page, permission, cooldown, vote, navigate, "");
        }
        public Click(int x, int y, int w, int h, String command, boolean console, String message, int page, String permission, int cooldown, String vote) {
            this(x, y, w, h, command, console, message, page, permission, cooldown, vote, "");
        }

        public Click(int x, int y, int w, int h, String command, boolean console, String message) { this(x, y, w, h, command, console, message, 0, "", 0); }

        public Click(int x, int y, int w, int h, String command, boolean console, String message, int page, String permission, int cooldown) {
            this(x, y, w, h, command, console, message, page, permission, cooldown, "");
        }

        boolean contains(int px, int py) { return px >= x && py >= y && px < x + w && py < y + h; }
    }

    public final String name;
    public final String world;
    public final int x, y, z, facingX, facingZ, width, height;
    public final boolean glow;
    public final int background;
    public final int refreshTicks;
    public final List<Element> elements;
    /** Rotating pages: each page is a list of elements drawn on top of {@link #elements}. */
    public final List<List<Element>> pages;
    public final int pageSeconds;
    public final List<Click> clicks;
    public final List<Integer> maps;

    public Board(String name, String world, int x, int y, int z, int facingX, int facingZ, int width, int height, boolean glow, int background,
                 int refreshTicks, List<Element> elements, List<Click> clicks, List<Integer> maps) {
        this(name, world, x, y, z, facingX, facingZ, width, height, glow, background, refreshTicks, elements, List.of(), 10, clicks, maps);
    }

    public Board(String name, String world, int x, int y, int z, int facingX, int facingZ, int width, int height, boolean glow, int background,
                 int refreshTicks, List<Element> elements, List<List<Element>> pages, int pageSeconds, List<Click> clicks, List<Integer> maps) {
        this.name = name;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.facingX = facingX;
        this.facingZ = facingZ;
        this.width = width;
        this.height = height;
        this.glow = glow;
        this.background = background;
        this.refreshTicks = refreshTicks;
        this.elements = List.copyOf(elements);
        this.pages = pages.stream().map(List::copyOf).toList();
        this.pageSeconds = Math.max(1, pageSeconds);
        this.clicks = List.copyOf(clicks);
        this.maps = List.copyOf(maps);
    }

    public int pixelWidth() { return width * Geometry.TILE; }

    public int pixelHeight() { return height * Geometry.TILE; }

    /** Board needs a separate picture per viewer (placeholders, the viewer's head or permission-gated elements). */
    public boolean perPlayer() {
        if (clicks.stream().anyMatch(click -> !click.navigate().isBlank())) return true;
        List<Element> all = new ArrayList<>(elements);
        pages.forEach(all::addAll);
        for (Element element : all) if (personal(element)) return true;
        return false;
    }

    private static boolean personal(Element element) {
        return switch (element) {
            case Text text -> text.text().contains("{") || text.text().contains("%");
            case Head head -> head.player().isBlank();
            case Progress bar -> bar.value().contains("%") || bar.value().contains("{") || bar.max().contains("%") || bar.max().contains("{");
            case Gated ignored -> true;
            case Top top -> top.highlight();
            default -> false;
        };
    }

    /** Every element on the board (fixed and on all pages), with permission-gated ones unwrapped. */
    public List<Element> all() {
        List<Element> all = new ArrayList<>();
        List<Element> raw = new ArrayList<>(elements);
        pages.forEach(raw::addAll);
        for (Element element : raw) all.add(element instanceof Gated gated ? gated.element() : element);
        return all;
    }

    /** Rankings shown on this board. */
    public List<Top> tops() {
        List<Top> tops = new ArrayList<>();
        for (Element element : all()) if (element instanceof Top top) tops.add(top);
        return tops;
    }

    /** Whether a text or progress bar on the board contains {@code needle} (e.g. a poll placeholder). */
    public boolean mentions(String needle) {
        for (Element element : all()) {
            boolean hit = switch (element) {
                case Text text -> text.text().toLowerCase(Locale.ROOT).contains(needle);
                case Progress bar -> (bar.value() + bar.max()).toLowerCase(Locale.ROOT).contains(needle);
                default -> false;
            };
            if (hit) return true;
        }
        return false;
    }

    /** Index of the page shown at {@code nowMillis}, 0 without pages. */
    public int page(long nowMillis) { return pages.isEmpty() ? 0 : (int) ((nowMillis / 1000L / pageSeconds) % pages.size()); }

    /** Elements visible at {@code nowMillis}: the fixed ones plus the current page. */
    public List<Element> visible(long nowMillis) {
        return visiblePage(page(nowMillis));
    }

    public List<Element> visiblePage(int page) {
        if (pages.isEmpty()) return elements;
        List<Element> out = new ArrayList<>(elements);
        out.addAll(pages.get(page));
        return out;
    }

    /**
     * Index in {@link #clicks} of the area hit at pixel ({@code px}, {@code py}) at {@code nowMillis}, or -1. Areas bound to another
     * page or to a permission the viewer does not match are skipped, so the next area underneath can take the click.
     */
    public int clickAt(int px, int py, long nowMillis, java.util.function.Predicate<String> hasPermission) {
        return clickAtPage(px, py, page(nowMillis), hasPermission);
    }

    public int clickAtPage(int px, int py, int page, java.util.function.Predicate<String> hasPermission) {
        int current = page + 1;
        for (int i = 0; i < clicks.size(); i++) {
            Click area = clicks.get(i);
            if (!area.contains(px, py) || area.page() > 0 && area.page() != current) continue;
            if (!area.permission().isBlank() && !permitted(area.permission(), hasPermission)) continue;
            return i;
        }
        return -1;
    }

    /** "vip" needs the permission, "!vip" needs to lack it. */
    public static boolean permitted(String permission, java.util.function.Predicate<String> hasPermission) {
        boolean negate = permission.startsWith("!");
        return hasPermission.test(negate ? permission.substring(1) : permission) != negate;
    }

    public Board withMaps(List<Integer> newMaps) {
        return new Board(name, world, x, y, z, facingX, facingZ, width, height, glow, background, refreshTicks, elements, pages, pageSeconds, clicks, newMaps);
    }

    // ------------------------------------------------------------------ parsing

    /** "#252a33" / "252a33" / "gold" → 0xRRGGBB; unknown → fallback. */
    public static int color(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String value = raw.trim();
        if (value.startsWith("#")) value = value.substring(1);
        if (value.length() == 6) {
            try { return Integer.parseInt(value, 16); } catch (NumberFormatException ignored) { }
        }
        net.kyori.adventure.text.format.NamedTextColor named = net.kyori.adventure.text.format.NamedTextColor.NAMES.value(value.toLowerCase(Locale.ROOT));
        return named == null ? fallback : named.value();
    }

    public static Board read(String name, ConfigurationSection c) {
        String facing = c.getString("facing", "NORTH").toUpperCase(Locale.ROOT);
        int fx = switch (facing) { case "EAST" -> 1; case "WEST" -> -1; default -> 0; };
        int fz = switch (facing) { case "SOUTH" -> 1; case "NORTH" -> -1; default -> 0; };
        if (fx == 0 && fz == 0) fz = -1;
        int width = Math.clamp(c.getInt("width", 1), 1, 16), height = Math.clamp(c.getInt("height", 1), 1, 16);
        List<Element> elements = elements(c.getMapList("elements"));
        List<List<Element>> pages = new ArrayList<>();
        for (Object page : c.getList("pages", List.of())) if (page instanceof List<?> list) pages.add(elements(list.stream().filter(java.util.Map.class::isInstance).map(m -> (java.util.Map<?, ?>) m).toList()));
        List<Click> clicks = new ArrayList<>();
        for (var raw : c.getMapList("clicks")) {
            ConfigurationSection e = section(raw);
            clicks.add(new Click(e.getInt("x", 0), e.getInt("y", 0), e.getInt("width", 128), e.getInt("height", 128), e.getString("command", ""),
                    "console".equalsIgnoreCase(e.getString("as", "player")), e.getString("message", ""), Math.max(0, e.getInt("page", 0)),
                    e.getString("permission", "").trim(), Math.clamp(e.getInt("cooldown", 0), 0, 86_400), e.getString("vote", "").trim(),
                    e.getString("navigate", "").trim().toLowerCase(Locale.ROOT), e.getString("guestbook", "").trim().toLowerCase(Locale.ROOT)));
        }
        return new Board(name, c.getString("world", "world"), c.getInt("x"), c.getInt("y"), c.getInt("z"), fx, fz, width, height,
                c.getBoolean("glow", true), color(c.getString("background"), 0x252A33), Math.clamp(c.getInt("refresh-seconds", 5), 1, 3600) * 20,
                elements, pages, Math.clamp(c.getInt("page-seconds", 10), 1, 3600), clicks, c.getIntegerList("maps"));
    }

    private static List<Element> elements(List<? extends java.util.Map<?, ?>> raws) {
        List<Element> elements = new ArrayList<>();
        for (var raw : raws) {
            ConfigurationSection e = section(raw);
            String type = e.getString("type", "text").toLowerCase(Locale.ROOT);
            int ex = e.getInt("x", 0), ey = e.getInt("y", 0);
            Element element = switch (type) {
                case "rect" -> new Rect(ex, ey, e.getInt("width", 64), e.getInt("height", 64), color(e.getString("color"), 0x000000),
                        Math.clamp(e.getInt("opacity", 100), 0, 100));
                case "image" -> new Image(e.getString("file", ""), ex, ey, e.getInt("width", 0), e.getInt("height", 0), e.getBoolean("dither", true));
                case "head" -> new Head(ex, ey, Math.clamp(e.getInt("size", 64), 8, 1024), e.getString("player", ""));
                case "progress", "bar" -> new Progress(ex, ey, e.getInt("width", 128), e.getInt("height", 12), e.getString("value", "0"), e.getString("max", "100"),
                        color(e.getString("color"), 0x3A7D44), color(e.getString("background"), 0x14171C));
                case "top", "leaderboard" -> new Top(e.getString("source", "").trim(), ex, ey, e.getInt("width", 0), Math.clamp(e.getInt("rows", 5), 1, 50),
                        Math.clamp(e.getInt("size", 16), 6, 128), color(e.getString("color"), 0xFFFFFF), color(e.getString("value-color"), 0xE3B04B),
                        e.getBoolean("heads", true), e.getBoolean("medals", true), e.getString("format", "number"),
                        "lowest".equalsIgnoreCase(e.getString("order", "highest")), e.getBoolean("highlight", false),
                        e.getString("font", "SansSerif"), e.getString("label", ""));
                default -> {
                    Align align;
                    try { align = Align.valueOf(e.getString("align", "left").toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException wrong) { align = Align.LEFT; }
                    yield new Text(e.getString("text", ""), ex, ey, e.getInt("width", 0), Math.clamp(e.getInt("size", 16), 6, 256), align,
                            color(e.getString("color"), 0xFFFFFF), e.getBoolean("shadow", true), e.getString("font", "SansSerif"),
                            Math.clamp(e.getDouble("line-height", 1.2), 0.5, 3), e.getBoolean("fit", true));
                }
            };
            String permission = e.getString("permission", "");
            elements.add(permission.isBlank() ? element : new Gated(permission.trim(), element));
        }
        return elements;
    }

    /** Number from a resolved placeholder text ("1,234.5", "42%" …); 0 when there is none. */
    static double number(String text) {
        if (text == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?[0-9][0-9,]*(\\.[0-9]+)?").matcher(text);
        if (!m.find()) return 0;
        try { return Double.parseDouble(m.group().replace(",", "")); } catch (NumberFormatException wrong) { return 0; }
    }

    private static ConfigurationSection section(java.util.Map<?, ?> raw) {
        org.bukkit.configuration.MemoryConfiguration memory = new org.bukkit.configuration.MemoryConfiguration();
        for (var entry : raw.entrySet()) memory.set(String.valueOf(entry.getKey()), entry.getValue());
        return memory;
    }

    public static String facingName(int fx, int fz) {
        if (fx == 1) return "EAST";
        if (fx == -1) return "WEST";
        return fz == 1 ? "SOUTH" : "NORTH";
    }
}
