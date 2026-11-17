package at.esoren.vystormboards;

import Vystorm.vystorm_core.display.ClientDisplays;
import Vystorm.vystorm_core.display.ClientDisplaysInterface;
import Vystorm.vystorm_core.display.MapWall;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Packet render mode: every board is a Vystorm Core client-only map wall (no item frame entities, no server maps).
 */
final class PacketBoards {
    private record Shown(BoardRuntime runtime, MapWall wall, boolean perPlayer, Map<UUID, Long> versions) {}

    private final Plugin plugin;
    private final ClientDisplaysInterface displays;
    private final Map<String, Shown> shown = new LinkedHashMap<>();
    private final Map<String, Long> sharedVersions = new HashMap<>();

    /** @param displays {@code api.packages().clientDisplays()} */
    PacketBoards(Plugin plugin, ClientDisplaysInterface displays) { this.plugin = plugin; this.displays = displays; }

    /** Client displays work on this server; otherwise the reason (null when available). */
    static String unavailable(ClientDisplaysInterface displays) {
        return displays.supported() ? null : displays.unsupportedReason().orElse("unsupported server");
    }

    /** Shows the board as a packet wall; false when Core could not create it (the caller keeps item frames then). */
    boolean open(BoardRuntime runtime, double viewDistance, BiConsumer<Player, BoardRuntime> click) {
        Board board = runtime.board;
        World world = Bukkit.getWorld(board.world);
        if (world == null) return false;
        MapWall.Spec spec = new MapWall.Spec(world, board.x, board.y, board.z, face(board.facingX, board.facingZ), board.width, board.height,
                board.glow, true, viewDistance);
        var wall = displays.mapWall(plugin, spec);
        if (wall.isEmpty()) return false;
        wall.get().onClick(c -> click.accept(c.player(), runtime));
        shown.put(board.name.toLowerCase(java.util.Locale.ROOT), new Shown(runtime, wall.get(), board.perPlayer(), new HashMap<>()));
        return true;
    }

    boolean has(String name) { return shown.containsKey(name); }

    /** Pushes changed pictures to the viewers Core currently shows each board to (only the changed parts are sent). */
    void tick() {
        for (var entry : shown.entrySet()) {
            Shown s = entry.getValue();
            List<Player> viewers = s.wall().viewers();
            if (viewers.isEmpty()) continue;
            if (!s.perPlayer()) {
                BoardRuntime.Picture picture = s.runtime().current(viewers.getFirst());
                Long sent = sharedVersions.get(entry.getKey());
                if (sent == null || sent != picture.version()) {
                    s.wall().shared(picture.pixels());
                    sharedVersions.put(entry.getKey(), picture.version());
                }
                continue;
            }
            for (Player viewer : viewers) {
                BoardRuntime.Picture picture = s.runtime().current(viewer);
                Long sent = s.versions().get(viewer.getUniqueId());
                if (sent == null || sent != picture.version() || !s.wall().hasOwnContent(viewer)) {
                    s.wall().content(viewer, picture.pixels());
                    s.versions().put(viewer.getUniqueId(), picture.version());
                }
            }
        }
    }

    void forget(UUID viewer) { for (Shown s : shown.values()) s.versions().remove(viewer); }

    void clear() {
        for (Shown s : shown.values()) s.wall().remove();
        shown.clear();
        sharedVersions.clear();
    }

    int count() { return shown.size(); }

    /** One line of Core's client display counters for /boards render. */
    static Map<String, Object> stats(ClientDisplaysInterface displays) {
        ClientDisplays.Stats s = displays.stats();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("walls", s.walls());
        values.put("viewers", s.viewers());
        values.put("frames", s.fakeFrames());
        values.put("spawns", s.spawnPackets());
        values.put("maps", s.mapPackets());
        values.put("pixels", s.mapPixels());
        values.put("kib", s.estimatedBytes() / 1024);
        values.put("clicks", s.clicks());
        values.put("mapids", s.mapIdsInUse());
        values.put("entityids", s.entityIdsInUse());
        values.put("avg", s.ticks() == 0 ? 0 : s.tickNanos() / s.ticks() / 1000);
        values.put("max", s.maxTickNanos() / 1000);
        return values;
    }

    static BlockFace face(int fx, int fz) {
        if (fx == 1) return BlockFace.EAST;
        if (fx == -1) return BlockFace.WEST;
        return fz == 1 ? BlockFace.SOUTH : BlockFace.NORTH;
    }
}
