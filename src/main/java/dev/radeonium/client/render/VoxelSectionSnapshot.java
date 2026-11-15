package dev.radeonium.client.render;

import java.util.Arrays;

/**
 * Immutable 16x16x16 section material snapshot with an optional one-block
 * neighbor halo. Coordinates in the halo constructor are [0..17], where
 * [1..16] is the target section and the outer ring is its six-neighbor data.
 *
 * Material zero is air. Non-zero materials are treated as face-occluding by
 * this deliberately conservative voxel prototype; non-opaque Minecraft
 * materials must use the baked-model path instead.
 */
public final class VoxelSectionSnapshot {
    public static final int SIZE = 16;
    public static final int VOLUME = SIZE * SIZE * SIZE;
    public static final int HALO_SIZE = SIZE + 2;
    public static final int HALO_VOLUME = HALO_SIZE * HALO_SIZE * HALO_SIZE;

    private final short[] materials;
    private final short[] haloMaterials;

    /** Creates a section without neighbor data; out-of-section cells are air. */
    public VoxelSectionSnapshot(short[] materials) {
        if (materials == null || materials.length != VOLUME) {
            throw new IllegalArgumentException("A section snapshot must contain exactly 4096 material values");
        }
        this.materials = Arrays.copyOf(materials, materials.length);
        this.haloMaterials = new short[HALO_VOLUME];
        for (int y = 0; y < SIZE; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    haloMaterials[haloIndex(x + 1, y + 1, z + 1)] =
                            materials[index(x, y, z)];
                }
            }
        }
    }

    /**
     * Creates a section snapshot with a complete one-cell border. The supplied
     * array is laid out x-fastest, then z, then y, with dimensions 18x18x18.
     */
    public static VoxelSectionSnapshot withNeighborHalo(short[] haloMaterials) {
        if (haloMaterials == null || haloMaterials.length != HALO_VOLUME) {
            throw new IllegalArgumentException("A halo snapshot must contain exactly 5832 material values");
        }
        return new VoxelSectionSnapshot(haloMaterials, true);
    }

    private VoxelSectionSnapshot(short[] haloMaterials, boolean ignored) {
        this.haloMaterials = Arrays.copyOf(haloMaterials, haloMaterials.length);
        this.materials = new short[VOLUME];
        for (int y = 0; y < SIZE; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    materials[index(x, y, z)] =
                            this.haloMaterials[haloIndex(x + 1, y + 1, z + 1)];
                }
            }
        }
    }

    /** Returns zero outside the target section. */
    public short materialAt(int x, int y, int z) {
        if (!inside(x, y, z)) return 0;
        return materials[index(x, y, z)];
    }

    /**
     * Returns the material at section-local coordinates, including the
     * one-cell halo. Coordinates beyond [-1..16] are treated as air.
     */
    public short materialAtWithHalo(int x, int y, int z) {
        if (x < -1 || x > 16 || y < -1 || y > 16 || z < -1 || z > 16) return 0;
        return haloMaterials[haloIndex(x + 1, y + 1, z + 1)];
    }

    public boolean occludesFace(int x, int y, int z) {
        return materialAtWithHalo(x, y, z) != 0;
    }

    public short[] copyMaterials() {
        return Arrays.copyOf(materials, materials.length);
    }

    public short[] copyHaloMaterials() {
        return Arrays.copyOf(haloMaterials, haloMaterials.length);
    }

    private static boolean inside(int x, int y, int z) {
        return x >= 0 && x < SIZE && y >= 0 && y < SIZE && z >= 0 && z < SIZE;
    }

    private static int index(int x, int y, int z) {
        return x | (y << 4) | (z << 8);
    }

    private static int haloIndex(int x, int y, int z) {
        return x + z * HALO_SIZE + y * HALO_SIZE * HALO_SIZE;
    }
}
