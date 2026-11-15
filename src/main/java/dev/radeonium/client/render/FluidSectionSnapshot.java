package dev.radeonium.client.render;

import java.util.Arrays;

/**
 * Immutable fluid capture for one 16^3 section.
 *
 * Fluid IDs are renderer material keys, zero means empty, and heights are
 * quantized to 0..8 eighths of a block. Capture tint and packed light on the
 * Minecraft-approved build thread; the mesher never reads a live world.
 */
public final class FluidSectionSnapshot {
    public static final int EDGE = 16;
    public static final int CELL_COUNT = EDGE * EDGE * EDGE;

    private final short[] fluidIds;
    private final byte[] heights;
    private final int[] tints;
    private final int[] packedLights;

    public FluidSectionSnapshot(short[] fluidIds, byte[] heights, int[] tints, int[] packedLights) {
        if (fluidIds == null || heights == null || tints == null || packedLights == null
                || fluidIds.length != CELL_COUNT || heights.length != CELL_COUNT
                || tints.length != CELL_COUNT || packedLights.length != CELL_COUNT) {
            throw new IllegalArgumentException("Fluid snapshot arrays must contain exactly 4096 entries");
        }
        this.fluidIds = fluidIds.clone();
        this.heights = heights.clone();
        this.tints = tints.clone();
        this.packedLights = packedLights.clone();
        for (int i = 0; i < CELL_COUNT; i++) {
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

    public short[] copyFluidIds() { return fluidIds.clone(); }
    public byte[] copyHeights() { return heights.clone(); }
    public int[] copyTints() { return tints.clone(); }
    public int[] copyPackedLights() { return packedLights.clone(); }

    private static int index(int x, int y, int z) {
        if (x < 0 || x >= EDGE || y < 0 || y >= EDGE || z < 0 || z >= EDGE) {
            throw new IndexOutOfBoundsException("Fluid block coordinates must be in [0, 15]");
        }
        return x + EDGE * (z + EDGE * y);
    }
}
