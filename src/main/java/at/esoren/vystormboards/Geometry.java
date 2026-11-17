package at.esoren.vystormboards;

/**
 * Pure wall-board geometry (no server, unit-tested). A board hangs on a wall; {@code facing} is the direction the
 * frames face (out of the wall, towards the viewer) given as a unit vector on the X or Z axis. Tile (0, 0) is the
 * top-left frame as seen by someone standing in front of the board.
 */
public final class Geometry {
    public static final int TILE = 128;
    /** Item frames sit this far in front of the wall surface. */
    static final double FRAME_OFFSET = 1.0 / 16;

    private Geometry() {}

    /** Viewer's right-hand direction when looking at a wall whose frames face {@code (fx, fz)}. */
    public static int[] right(int fx, int fz) {
        // viewer looks along -facing; right of looking direction (dx, dz) is (-dz, dx)
        return new int[]{fz, -fx};
    }

    /** Block position of the frame for tile ({@code tx}, {@code ty}) given the top-left frame block. */
    public static int[] tile(int ox, int oy, int oz, int fx, int fz, int tx, int ty) {
        int[] r = right(fx, fz);
        return new int[]{ox + r[0] * tx, oy - ty, oz + r[1] * tx};
    }

    /**
     * Board pixel hit by a look ray, or {@code null} when the ray misses the board or points away. {@code origin} is the
     * top-left frame block; the board is {@code width}×{@code height} tiles.
     */
    public static int[] pixel(int ox, int oy, int oz, int fx, int fz, int width, int height, double[] eye, double[] direction) {
        int[] r = right(fx, fz);
        // plane: wall surface plus frame offset, through the frame block's back face
        double px = ox + 0.5 - fx * (0.5 - FRAME_OFFSET), pz = oz + 0.5 - fz * (0.5 - FRAME_OFFSET);
        double denominator = direction[0] * fx + direction[2] * fz;
        if (denominator >= -1e-9) return null;
        double t = ((px - eye[0]) * fx + (pz - eye[2]) * fz) / denominator;
        if (t <= 0) return null;
        double hx = eye[0] + direction[0] * t, hy = eye[1] + direction[1] * t, hz = eye[2] + direction[2] * t;
        // top-left corner of tile (0, 0) on the plane
        double cx = px - r[0] * 0.5, cy = oy + 1.0, cz = pz - r[1] * 0.5;
        double u = (hx - cx) * r[0] + (hz - cz) * r[1];
        double v = cy - hy;
        int x = (int) Math.floor(u * TILE), y = (int) Math.floor(v * TILE);
        if (x < 0 || y < 0 || x >= width * TILE || y >= height * TILE) return null;
        return new int[]{x, y};
    }
}
