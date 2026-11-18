package at.esoren.vystormboards;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * Live state of one board: the composed picture per viewer (or one shared picture), recomposed only when the
 * refresh interval passed and the resolved content actually changed. Every frame of the board has a {@link Tile}
 * renderer that copies its 128×128 slice.
 */
final class BoardRuntime {
    record Picture(byte[] pixels, String signature, long version, long checkedAt) {}

    /** Computes the content signature (resolved texts) for a viewer; cheap compared to composing. */
    interface Signer { String sign(Board board, Player viewer); }

    final Board board;
    private final Signer signer;
    private final BiFunction<Board, Player, byte[]> composer;
    private final Map<UUID, Picture> pictures = new HashMap<>();
    private Picture shared;
    private long version;
    private volatile long invalidatedAt;
    private final int checkTicks;
    /** Drawn per viewer: the board has viewer data and per-player.enabled is on. */
    private final boolean perPlayer;

    boolean perPlayer() { return perPlayer; }

    BoardRuntime(Board board, Signer signer, BiFunction<Board, Player, byte[]> composer) { this(board, signer, composer, board.refreshTicks); }

    /** {@code checkTicks}: how often the content is checked for changes (shorter for pages and animations). */
    BoardRuntime(Board board, Signer signer, BiFunction<Board, Player, byte[]> composer, int checkTicks) {
        this(board, signer, composer, checkTicks, true);
    }

    /** {@code personal = false} (per-player.enabled off): one picture for everyone, even if the board has viewer data. */
    BoardRuntime(Board board, Signer signer, BiFunction<Board, Player, byte[]> composer, int checkTicks, boolean personal) {
        this.board = board;
        this.perPlayer = personal && board.perPlayer();
        this.checkTicks = Math.max(1, checkTicks);
        this.signer = signer;
        this.composer = composer;
    }

    /** Forces a recompose on the next draw (images or faces changed, reload). */
    void invalidate() { invalidatedAt = System.nanoTime(); version++; pictures.clear(); shared = null; }

    void forget(UUID viewer) { pictures.remove(viewer); }

    private Picture picture(Player viewer, long now) {
        Picture current = perPlayer ? pictures.get(viewer.getUniqueId()) : shared;
        long refreshNanos = checkTicks * 50_000_000L;
        if (current != null && now - current.checkedAt() < refreshNanos && current.checkedAt() >= invalidatedAt) return current;
        String signature = signer.sign(board, perPlayer ? viewer : null);
        if (current != null && current.signature().equals(signature) && current.checkedAt() >= invalidatedAt) {
            current = new Picture(current.pixels(), signature, current.version(), now);
        } else {
            current = new Picture(composer.apply(board, perPlayer ? viewer : null), signature, ++version, now);
        }
        if (perPlayer) pictures.put(viewer.getUniqueId(), current);
        else shared = current;
        return current;
    }

    /** Current picture for a viewer (any viewer for shared boards), recomposed when due; used by packet mode. */
    Picture current(Player viewer) { return picture(viewer, System.nanoTime()); }

    Tile tile(int index) { return new Tile(index); }

    /** Renderer for one frame of the board. */
    final class Tile extends MapRenderer {
        private final int index;
        private final Map<UUID, Long> drawn = new HashMap<>();
        private long drawnShared = -1;

        Tile(int index) {
            super(perPlayer);
            this.index = index;
        }

        @Override
        @SuppressWarnings({"deprecation", "removal"})
        public void render(MapView map, MapCanvas canvas, Player player) {
            Picture picture = picture(player, System.nanoTime());
            if (isContextual()) {
                Long last = drawn.get(player.getUniqueId());
                if (last != null && last == picture.version()) return;
                drawn.put(player.getUniqueId(), picture.version());
            } else {
                if (drawnShared == picture.version()) return;
                drawnShared = picture.version();
            }
            int tx = index % board.width, ty = index / board.width;
            int stride = board.pixelWidth();
            byte[] pixels = picture.pixels();
            for (int y = 0; y < Geometry.TILE; y++) {
                int row = (ty * Geometry.TILE + y) * stride + tx * Geometry.TILE;
                for (int x = 0; x < Geometry.TILE; x++) canvas.setPixel(x, y, pixels[row + x]);
            }
        }

        void forget(UUID viewer) { drawn.remove(viewer); }
    }
}
