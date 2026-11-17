package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class BoardsTest {
    @Test
    void rightHandDirectionPerFacing() {
        assertArrayEquals(new int[]{-1, 0}, Geometry.right(0, -1)); // frames face north, viewer looks south → right is west
        assertArrayEquals(new int[]{1, 0}, Geometry.right(0, 1));
        assertArrayEquals(new int[]{0, -1}, Geometry.right(1, 0));  // frames face east, viewer looks west → right is north
        assertArrayEquals(new int[]{0, 1}, Geometry.right(-1, 0));
    }

    @Test
    void tilesGoRightAndDown() {
        assertArrayEquals(new int[]{8, 64, 5}, Geometry.tile(10, 65, 5, 0, -1, 2, 1));
    }

    @Test
    void lookingAtTheCentreHitsTheCentrePixel() {
        // 2×1 board facing south (+Z); frame blocks (0,64,0) and (1,64,0); wall at z = 0 (block -1 behind)
        // viewer stands south at z = 5 and looks north (-Z) at the middle between both frames
        double planeZ = 0.5 - 0.5 + Geometry.FRAME_OFFSET; // frame block centre 0.5 minus half a block plus offset
        int[] pixel = Geometry.pixel(0, 64, 0, 0, 1, 2, 1, new double[]{1.0, 64.5, 5}, new double[]{0, 0, -1});
        assertArrayEquals(new int[]{128, 64}, pixel);
        assertTrue(planeZ > 0);
    }

    @Test
    void lookingAwayOrBesideMisses() {
        assertNull(Geometry.pixel(0, 64, 0, 0, 1, 2, 1, new double[]{1, 64.5, 5}, new double[]{0, 0, 1}));
        assertNull(Geometry.pixel(0, 64, 0, 0, 1, 2, 1, new double[]{5, 64.5, 5}, new double[]{0, 0, -1}));
    }

    @Test
    void styledTextSplitsLinesAndColours() {
        List<List<StyledText.Run>> lines = StyledText.parse("Hi <gold>Bob</gold>\n<b>x", 0xFFFFFF);
        assertEquals(2, lines.size());
        assertEquals("Hi Bob", StyledText.plain(lines.get(0)));
        assertEquals(0xFFAA00, lines.get(0).get(1).rgb());
        assertTrue(lines.get(1).get(0).bold());
    }

    @Test
    void colourParsing() {
        assertEquals(0x252A33, Board.color("#252a33", 0));
        assertEquals(0xFFAA00, Board.color("gold", 0));
        assertEquals(7, Board.color("nope", 7));
    }

    @Test
    void readsBoardAndDetectsPerPlayerContent() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                world: w
                x: 1
                y: 2
                z: 3
                facing: EAST
                width: 2
                height: 1
                elements:
                  - {type: rect, x: 0, y: 0, width: 10, height: 10, color: red}
                  - {type: text, text: "Hello {player}", size: 20, align: center}
                clicks:
                  - {x: 0, y: 0, width: 50, height: 50, command: "spawn", as: console}
                maps: [5, 6]
                """);
        Board board = Board.read("demo", yaml);
        assertEquals(1, board.facingX);
        assertEquals(256, board.pixelWidth());
        assertEquals(2, board.elements.size());
        assertTrue(board.perPlayer());
        assertTrue(board.clicks.getFirst().console());
        assertTrue(board.clicks.getFirst().contains(10, 10));
        assertFalse(board.clicks.getFirst().contains(60, 10));
        assertEquals(List.of(5, 6), board.maps);
    }

    @Test
    void composerPaintsBackgroundRectAndText() {
        Board board = new Board("t", "w", 0, 0, 0, 0, -1, 1, 1, true, 0x000000, 100,
                List.of(new Board.Rect(0, 0, 10, 10, 0xFF0000, 100), new Board.Text("<white>HELLO {player}", 0, 40, 0, 24, Board.Align.LEFT, 0xFFFFFF, false, "SansSerif", 1.2, true)),
                List.of(), List.of());
        Composer composer = new Composer((file, frame, w, h, dither) -> null, name -> null);
        int[] rgb = composer.render(board, text -> text.replace("{player}", "Bob"), "Bob");
        assertEquals(128 * 128, rgb.length);
        assertEquals(0xFF0000, rgb[0] & 0xFFFFFF);
        assertEquals(0x000000, rgb[127 * 128 + 127] & 0xFFFFFF);
        boolean textDrawn = false;
        for (int y = 40; y < 70; y++) for (int x = 0; x < 128; x++) if ((rgb[y * 128 + x] & 0xFFFFFF) == 0xFFFFFF) textDrawn = true;
        assertTrue(textDrawn);
    }

    @Test
    void composerCentresFittedImages() {
        BufferedImage red = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 20; x++) for (int y = 0; y < 10; y++) red.setRGB(x, y, 0xFF0000);
        Board board = new Board("t", "w", 0, 0, 0, 0, -1, 1, 1, true, 0x000000, 100,
                List.of(new Board.Image("red.png", 0, 0, 128, 128, false)), List.of(), List.of());
        int[] rgb = new Composer((file, frame, w, h, dither) -> red, name -> null).render(board, s -> s, "");
        assertEquals(0xFF0000, rgb[(64 - 5 + 2) * 128 + 64] & 0xFFFFFF);
        assertNotEquals(0xFF0000, rgb[0] & 0xFFFFFF);
    }

    @Test
    void imagePathsCannotLeaveTheFolder() {
        Path folder = Path.of("plugins", "VystormBoards", "images");
        assertNull(Assets.inside(folder, "../../secret.yml"));
        assertEquals(folder.resolve("logo.png").normalize(), Assets.inside(folder, "logo.png"));
    }

    @Test
    void progressFillAndNumbers() {
        assertEquals(64, Composer.fill(50, 100, 128));
        assertEquals(128, Composer.fill(500, 100, 128));
        assertEquals(0, Composer.fill(5, 0, 128));
        assertEquals(1234.5, Board.number("Balance: 1,234.5 coins"), 1e-9);
        assertEquals(0, Board.number("none"), 1e-9);
    }

    @Test
    void textShrinksToFit() {
        assertEquals(24, Composer.fitted(24, 100, 200));
        assertEquals(12, Composer.fitted(24, 400, 200));
        assertEquals(6, Composer.fitted(24, 10_000, 100));
    }

    @Test
    void pagesRotateAndGatedElementsMakeBoardsPersonal() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                width: 1
                height: 1
                page-seconds: 5
                elements:
                  - {type: rect, x: 0, y: 0, width: 5, height: 5, color: red, permission: vip}
                pages:
                  - [{type: rect, x: 0, y: 0, width: 1, height: 1}]
                  - [{type: rect, x: 1, y: 1, width: 1, height: 1}, {type: rect, x: 2, y: 2, width: 1, height: 1}]
                """);
        Board board = Board.read("p", yaml);
        assertEquals(2, board.pages.size());
        assertTrue(board.perPlayer());
        assertTrue(board.elements.getFirst() instanceof Board.Gated);
        assertEquals(0, board.page(0));
        assertEquals(1, board.page(5_000));
        assertEquals(3, board.visible(5_000).size());
        assertEquals(0, board.page(10_000));
    }

    @Test
    void gatedElementsRespectPermissions() {
        Board board = new Board("g", "w", 0, 0, 0, 0, -1, 1, 1, true, 0x000000, 100,
                List.of(new Board.Gated("vip", new Board.Rect(0, 0, 10, 10, 0xFF0000, 100)), new Board.Gated("!vip", new Board.Rect(20, 0, 10, 10, 0x00FF00, 100))),
                List.of(), List.of());
        Composer composer = new Composer((file, frame, w, h, dither) -> null, name -> null);
        int[] vip = composer.render(board, s -> s, "", p -> p.equals("vip"), 0);
        int[] guest = composer.render(board, s -> s, "", p -> false, 0);
        assertEquals(0xFF0000, vip[0] & 0xFFFFFF);
        assertEquals(0x000000, vip[20] & 0xFFFFFF);
        assertEquals(0x000000, guest[0] & 0xFFFFFF);
        assertEquals(0x00FF00, guest[20] & 0xFFFFFF);
    }

    @Test
    void clickAreasFollowPagesAndPermissions() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                width: 1
                height: 1
                page-seconds: 5
                pages:
                  - [{type: rect, x: 0, y: 0, width: 1, height: 1}]
                  - [{type: rect, x: 0, y: 0, width: 1, height: 1}]
                clicks:
                  - {x: 0, y: 0, width: 64, height: 64, command: "warp rules", page: 1}
                  - {x: 0, y: 0, width: 64, height: 64, command: "kit vip", page: 2, permission: vip, cooldown: 60}
                  - {x: 0, y: 0, width: 64, height: 64, command: "shop", permission: "!vip"}
                """);
        Board board = Board.read("c", yaml);
        assertEquals(60, board.clicks.get(1).cooldown());
        assertEquals("vip", board.clicks.get(1).permission());
        assertEquals(0, board.clickAt(10, 10, 0, p -> true));          // page 1: rules button
        assertEquals(1, board.clickAt(10, 10, 5_000, p -> true));      // page 2, vip: kit button
        assertEquals(2, board.clickAt(10, 10, 5_000, p -> false));     // page 2, guest: falls through to the shop
        assertEquals(-1, board.clickAt(100, 100, 0, p -> true));       // outside every area
        Board plain = new Board("p", "w", 0, 0, 0, 0, -1, 1, 1, true, 0, 100, List.of(),
                List.of(new Board.Click(0, 0, 10, 10, "spawn", false, "")), List.of());
        assertEquals(0, plain.clickAt(5, 5, 99_000, p -> false));
    }

    @Test
    void clickCooldownsRunPerPlayerAndArea() {
        ClickCooldowns cooldowns = new ClickCooldowns();
        java.util.UUID steve = java.util.UUID.randomUUID(), alex = java.util.UUID.randomUUID();
        cooldowns.start(steve, "b#0", 1_000, 10);
        assertEquals(10, cooldowns.remaining(steve, "b#0", 1_000));
        assertEquals(1, cooldowns.remaining(steve, "b#0", 10_500));
        assertEquals(0, cooldowns.remaining(steve, "b#0", 11_000));
        assertEquals(0, cooldowns.remaining(steve, "b#1", 2_000));
        assertEquals(0, cooldowns.remaining(alex, "b#0", 2_000));
        cooldowns.start(alex, "b#0", 1_000, 0);
        assertFalse(cooldowns.tracks(alex));
        cooldowns.prune(steve, 5_000);
        assertTrue(cooldowns.tracks(steve));                          // still running: survives a relog
        cooldowns.prune(steve, 11_000);
        assertFalse(cooldowns.tracks(steve));
    }

    @Test
    void shadowIsAQuarterBrightness() {
        assertEquals(0x3F2A00, Composer.darken(0xFFAA00));
    }

    @Test
    void brokenBoardsFileIsNotReadAsEmpty(@org.junit.jupiter.api.io.TempDir Path folder) throws Exception {
        java.io.File file = folder.resolve("boards.yml").toFile();
        assertTrue(BoardsPlugin.readStrict(file).getKeys(false).isEmpty(), "a missing file is empty");
        java.nio.file.Files.writeString(file.toPath(), "boards:\n  a: [unclosed\n");
        org.junit.jupiter.api.Assertions.assertThrows(org.bukkit.configuration.InvalidConfigurationException.class, () -> BoardsPlugin.readStrict(file));
    }
}
