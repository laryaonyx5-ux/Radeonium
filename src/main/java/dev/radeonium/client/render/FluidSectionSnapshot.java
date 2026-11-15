package dev.radeonium.client.render;

/**
 * Immutable fluid capture for one 16^3 section, optionally surrounded by a
 * one-cell 18^3 halo copied from the six neighboring sections.
 *
 * Fluid IDs are renderer material keys, zero means empty, and heights are
 * quantized to 0..8 eighths of a block. Capture tint and packed light on the
 * Minecraft-approved build thread; the mesher never reads a live world.
 */
public final class FluidSectionSnapshot {
    public static final int EDGE = 16;
    public static final int CELL_COUNT = EDGE * EDGE * EDGE;
    public static final int HALO_EDGE = EDGE + 2;
    public static final int HALO_CELL_COUNT = HALO_EDGE * HALO_EDGE * HALO_EDGE;

    private final short[] fluidIds;
    private final byte[] heights;
    private final int[] tints;
    private final int[] packedLights;
    private final boolean hasHalo;

    public FluidSectionSnapshot(short[] fluidIds, byte[] heights, int[] tints, int[] packedLights) {
        this(fluidIds, heights, tints, packedLights, false);
    }

    /**
     * Creates a capture whose arrays are laid out as 18x18x18. Coordinates
     * accepted by the halo accessors range from -1 through 16; interior
     * coordinates remain identical to the ordinary 16x16x16 snapshot.
     */
    public static FluidSectionSnapshot withHalo(
            short[] fluidIds, byte[] heights, int[] tints, int[] packedLights
    ) {
        return new FluidSectionSnapshot(fluidIds, heights, tints, packedLights, true);
    }

    private FluidSectionSnapshot(
            short[] fluidIds, byte[] heights, int[] tints, int[] packedLights, boolean hasHalo
    ) {
        int expected = hasHalo ? HALO_CELL_COUNT : CELL_COUNT;
        if (fluidIds == null || heights == null || tints == null || packedLights == null
                || fluidIds.length != expected || heights.length != expected
                || tints.length != expected || packedLights.length != expected) {
            throw new IllegalArgumentException("Fluid snapshot arrays must contain exactly " + expected + " entries");
        }
        this.fluidIds = fluidIds.clone();
        this.heights = heights.clone();
        this.tints = tints.clone();
        this.packedLights = packedLights.clone();
        this.hasHalo = hasHalo;
        for (int i = 0; i < expected; i++) {
            if (this.heights[i] < 0 || this.heights[i] > 8) {
                throw new IllegalArgumentException("Fluid height must be in [0, 8]");
            }
            if (this.fluidIds[i] == 0 && this.heights[i] != 0) {
                throw new IllegalArgumentException("Empty fluid cells must have zero height");
            }
            if (this.fluidIds[i] != 0 && this.heights[i] == 0) {
                throw new IllegalArgumentException("Occupied fluid cells need positive height");
            }
        }
    }

    public short fluidId(int x, int y, int z) { return fluidIds[index(x, y, z)]; }
    public int heightEighths(int x, int y, int z) { return Byte.toUnsignedInt(heights[index(x, y, z)]); }
    public int tint(int x, int y, int z) { return tints[index(x, y, z)]; }
    public int packedLight(int x, int y, int z) { return packedLights[index(x, y, z)]; }
    public boolean hasHalo() { return hasHalo; }

    /** Returns zero outside the section when no neighbor halo was captured. */
    public short fluidIdWithHalo(int x, int y, int z) {
        return hasHalo ? fluidIds[haloIndex(x, y, z)] : inSection(x, y, z) ? fluidId(x, y, z) : 0;
    }

    /** Returns zero outside the section when no neighbor halo was captured. */
    public int heightEighthsWithHalo(int x, int y, int z) {
        return hasHalo ? Byte.toUnsignedInt(heights[haloIndex(x, y, z)])
                : inSection(x, y, z) ? heightEighths(x, y, z) : 0;
    }

    public short[] copyFluidIds() { return fluidIds.clone(); }
    public byte[] copyHeights() { return heights.clone(); }
    public int[] copyTints() { return tints.clone(); }
    public int[] copyPackedLights() { return packedLights.clone(); }

    private static boolean inSection(int x, int y, int z) {
        return x >= 0 && x < EDGE && y >= 0 && y < EDGE && z >= 0 && z < EDGE;
    }

    private static int index(int x, int y, int z) {
        if (!inSection(x, y, z)) {
            throw new IndexOutOfBoundsException("Fluid block coordinates must be in [0, 15]");
        }
        return x + EDGE * (z + EDGE * y);
    }

    private static int haloIndex(int x, int y, int z) {
        if (x < -1 || x > EDGE || y < -1 || y > EDGE || z < -1 || z > EDGE) {
            throw new IndexOutOfBoundsException("Fluid halo coordinates must be in [-1, 16]");
        }
        return (x + 1) + HALO_EDGE * ((z + 1) + HALO_EDGE * (y + 1));
    }
}
