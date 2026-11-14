package dev.radeonium.client.render;

/** The six section-to-section directions used by visibility traversal. */
public enum SectionFace {
    DOWN(0, -1, 0, 1),
    UP(0, 1, 0, 2),
    NORTH(0, 0, -1, 4),
    SOUTH(0, 0, 1, 8),
    WEST(-1, 0, 0, 16),
    EAST(1, 0, 0, 32);

    private final int dx;
    private final int dy;
    private final int dz;
    private final int bit;

    SectionFace(int dx, int dy, int dz, int bit) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.bit = bit;
    }

    public int dx() { return dx; }
    public int dy() { return dy; }
    public int dz() { return dz; }
    public int bit() { return bit; }

    public SectionFace opposite() {
        return switch (this) {
            case DOWN -> UP;
            case UP -> DOWN;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case WEST -> EAST;
            case EAST -> WEST;
        };
    }
}
