package at.esoren.vystormboards;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GifTest {
    @TempDir Path folder;

    @Test
    void readsEveryFrameOfAnAnimatedGif() throws Exception {
        Path file = folder.resolve("blink.gif");
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (var out = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int color : new int[]{0xFF0000, 0x00FF00, 0x0000FF}) {
                BufferedImage frame = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
                for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++) frame.setRGB(x, y, color);
                writer.writeToSequence(new IIOImage(frame, null, null), null);
            }
            writer.endWriteSequence();
        }
        var loaded = Assets.read(file);
        assertEquals(3, loaded.frames().size());
        assertEquals(0x00FF00, loaded.frames().get(1).getRGB(4, 4) & 0xFFFFFF);
    }
}
