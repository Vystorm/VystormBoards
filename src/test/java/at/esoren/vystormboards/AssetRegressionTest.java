package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.logging.Logger;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataNode;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetRegressionTest {
    @TempDir Path dir;

    private Assets assets() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        return new Assets(plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT, dir, mock(MapColors.class), () -> {});
    }

    @Test void invalidImagePathDoesNotAbortRendering() {
        assertNull(assertDoesNotThrow(() -> assets().fitted("bad\0.png", 0, 128, 128, false)));
    }

    @Test void invalidScoreboardPlayerNameDoesNotAbortRendering() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createPlayerProfile("Team Red")).thenThrow(new IllegalArgumentException("invalid player name"));
            assertNull(assertDoesNotThrow(() -> assets().face("Team Red")));
        }
    }

    @Test void staleSkinDownloadCannotUndoJoinInvalidation() throws Exception {
        Path skin = dir.resolve("skin.png");
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", skin.toFile());
        Plugin plugin = mock(Plugin.class); when(plugin.getLogger()).thenReturn(mock(Logger.class));
        Runnable changed = mock(Runnable.class);
        Assets assets = new Assets(plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT, dir, mock(MapColors.class), changed);
        var profile = mock(org.bukkit.profile.PlayerProfile.class);
        var textures = mock(org.bukkit.profile.PlayerTextures.class);
        when(profile.getTextures()).thenReturn(textures); when(textures.getSkin()).thenReturn(skin.toUri().toURL());
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        var async = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        var sync = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.createPlayerProfile("Steve")).thenReturn(profile);
            assertNull(assets.face("Steve"));
            verify(scheduler).runTaskAsynchronously(eq(plugin), async.capture());
            assets.forgetFace(java.util.UUID.randomUUID(), "Steve");
            async.getValue().run();
            verify(scheduler).runTask(eq(plugin), sync.capture());
            sync.getValue().run();
            assertTrue(((java.util.Map<?, ?>) BugFixRegressionTest.get(assets, "faces")).isEmpty());
            verifyNoInteractions(changed);
        }
    }

    @Test void gifRestoreToPreviousDoesNotLeaveTrails() throws Exception {
        writeGif();
        var loaded = Assets.read(dir.resolve("disposal.gif"));
        assertEquals(0x0000FF, loaded.frames().get(1).getRGB(0, 0) & 0xffffff);
        assertEquals(0xFF0000, loaded.frames().get(2).getRGB(0, 0) & 0xffffff);
        assertEquals(0x00FF00, loaded.frames().get(2).getRGB(2, 0) & 0xffffff);
    }

    @Test void gifUsesEachFramesOwnDelayWhileRespectingTheFpsLimit() throws Exception {
        writeGif();
        Composer composer = new Composer(assets(), name -> null);
        assertEquals(1, composer.frame("disposal.gif", 250, 10));
        assertEquals(2, composer.frame("disposal.gif", 450, 10));
        assertEquals(0, composer.frame("disposal.gif", 600, 10));
        assertEquals(1, composer.frame("disposal.gif", 500, 4));
        assertEquals(2, composer.frame("disposal.gif", 550, 4));
        assertEquals(0, composer.frame("disposal.gif", 800, 4));
    }

    @Test void gifReaderIsDisposedWhenDecodingFails() throws Exception {
        var reader = mock(javax.imageio.ImageReader.class);
        var input = mock(javax.imageio.stream.ImageInputStream.class);
        when(reader.getNumImages(true)).thenThrow(new java.io.IOException("broken GIF"));
        Path file = dir.resolve("broken.gif");
        try (var imageIO = mockStatic(ImageIO.class)) {
            imageIO.when(() -> ImageIO.createImageInputStream(file.toFile())).thenReturn(input);
            imageIO.when(() -> ImageIO.getImageReadersByFormatName("gif")).thenReturn(java.util.List.of(reader).iterator());
            assertThrows(java.io.IOException.class, () -> Assets.read(file));
            verify(reader).dispose(); verify(input).close();
        }
    }

    private void writeGif() throws Exception {
        Path file = dir.resolve("disposal.gif");
        var writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (var output = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < 3; i++) {
                BufferedImage frame = new BufferedImage(i == 0 ? 4 : 1, i == 0 ? 4 : 1, BufferedImage.TYPE_INT_RGB);
                var graphics = frame.createGraphics();
                graphics.setColor(i == 0 ? java.awt.Color.RED : i == 1 ? java.awt.Color.BLUE : java.awt.Color.GREEN);
                graphics.fillRect(0, 0, frame.getWidth(), frame.getHeight()); graphics.dispose();
                var metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame), null);
                var root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_image_1.0");
                var descriptor = (IIOMetadataNode) root.getElementsByTagName("ImageDescriptor").item(0);
                descriptor.setAttribute("imageLeftPosition", i == 2 ? "2" : "0");
                var control = (IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0);
                control.setAttribute("disposalMethod", i == 1 ? "restoreToPrevious" : "doNotDispose");
                control.setAttribute("delayTime", i == 0 ? "10" : i == 1 ? "30" : "20");
                metadata.setFromTree("javax_imageio_gif_image_1.0", root);
                writer.writeToSequence(new IIOImage(frame, null, metadata), null);
            }
            writer.endWriteSequence();
        } finally { writer.dispose(); }
    }
}
