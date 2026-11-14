package dev.radeonium.client.render;

import java.util.Arrays;

/**
 * Immutable, renderer-neutral 16x16x16 block snapshot.
 * A value of zero represents air; non-zero values are material identifiers.
 */
public final class VoxelSectionSnapshot {
    public static final int SIZE = 16;
    public static final int VOLUME = SIZE * SIZE * SIZE;
    private final short[] materials;

    public VoxelSectionSnapshot(short[] materials) {
        if (materials == null || materials.length != VOLUME) {
            throw new IllegalArgumentException("A section snapshot must contain exactly 4096 material values");
        }
        this.materials = Arrays.copyOf(materials, materials.length);
    }

    public short materialAt(int x, int y, int z) {
        if (x < 0 || x >= SIZE || y < 0 || y >= SIZE || z < 0 || z >= SIZE) return 0;
        return materials[x | (y << 4) | (z << 8)];
    }

    public short[] copyMaterials() {
        return Arrays.copyOf(materials, materials.length);
    }
}
