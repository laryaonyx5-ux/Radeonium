package dev.radeonium.client.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Greedy mesher for immutable opaque-material section snapshots.
 *
 * Coplanar faces with the same material are merged. A one-cell neighbor halo
 * allows faces on section borders to be culled without creating seams.
 * The hot voxel scan and quad emission deliberately avoid per-voxel and
 * per-quad temporary arrays.
 *
 * This is a conservative opaque-voxel path, not a replacement for Minecraft's
 * baked-model, translucent, fluid, tint or lighting semantics.
 */
public final class SectionMeshBuilder {
    public SectionMesh build(VoxelSectionSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot cannot be null");

        FloatBuilder output = new FloatBuilder(4096);
        short[] mask = new short[16 * 16];

        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                for (int slice = 0; slice < 16; slice++) {
                    Arrays.fill(mask, (short) 0);

                    for (int v = 0; v < 16; v++) {
                        for (int u = 0; u < 16; u++) {
                            int x = coordinateX(axis, slice, u, v);
                            int y = coordinateY(axis, slice, u, v);
                            int z = coordinateZ(axis, slice, u, v);
                            short material = snapshot.materialAt(x, y, z);
                            if (material == 0) continue;

                            int neighborX = x + (axis == 0 ? sign : 0);
                            int neighborY = y + (axis == 1 ? sign : 0);
                            int neighborZ = z + (axis == 2 ? sign : 0);
                            if (!snapshot.occludesFace(neighborX, neighborY, neighborZ)) {
                                mask[u + v * 16] = material;
                            }
                        }
                    }

                    for (int v = 0; v < 16; v++) {
                        for (int u = 0; u < 16;) {
                            short material = mask[u + v * 16];
                            if (material == 0) {
                                u++;
                                continue;
                            }

                            int width = 1;
                            while (u + width < 16 && mask[u + width + v * 16] == material) width++;

                            int height = 1;
                            boolean extend = true;
                            while (v + height < 16 && extend) {
                                for (int k = 0; k < width; k++) {
                                    if (mask[u + k + (v + height) * 16] != material) {
                                        extend = false;
                                        break;
                                    }
                                }
                                if (extend) height++;
                            }

                            float plane = slice + (sign > 0 ? 1.0f : 0.0f);
                            emitQuad(output, axis, plane, u, v, width, height, sign, material);

                            for (int dy = 0; dy < height; dy++) {
                                Arrays.fill(mask, (v + dy) * 16 + u,
                                        (v + dy) * 16 + u + width, (short) 0);
                            }
                            u += width;
                        }
                    }
                }
            }
        }

        ByteBuffer bytes = ByteBuffer.allocate(output.size() * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        output.writeTo(bytes);
        return new SectionMesh(bytes.array(), output.size() / SectionMesh.FLOATS_PER_VERTEX);
    }

    private static int coordinateX(int axis, int slice, int u, int v) {
        return switch (axis) {
            case 0 -> slice;
            case 1 -> v;
            default -> u;
        };
    }

    private static int coordinateY(int axis, int slice, int u, int v) {
        return switch (axis) {
            case 0 -> u;
            case 1 -> slice;
            default -> v;
        };
    }

    private static int coordinateZ(int axis, int slice, int u, int v) {
        return switch (axis) {
            case 0 -> v;
            case 1 -> u;
            default -> slice;
        };
    }

    private static void emitQuad(FloatBuilder out, int axis, float plane,
                                 int u, int v, int width, int height,
                                 int sign, short material) {
        float nx = axis == 0 ? sign : 0;
        float ny = axis == 1 ? sign : 0;
        float nz = axis == 2 ? sign : 0;

        if (sign > 0) {
            emitCorner(out, axis, plane, u, v, nx, ny, nz, material);
            emitCorner(out, axis, plane, u + width, v, nx, ny, nz, material);
            emitCorner(out, axis, plane, u + width, v + height, nx, ny, nz, material);
            emitCorner(out, axis, plane, u, v, nx, ny, nz, material);
            emitCorner(out, axis, plane, u + width, v + height, nx, ny, nz, material);
            emitCorner(out, axis, plane, u, v + height, nx, ny, nz, material);
        } else {
            emitCorner(out, axis, plane, u, v, nx, ny, nz, material);
            emitCorner(out, axis, plane, u + width, v + height, nx, ny, nz, material);
            emitCorner(out, axis, plane, u + width, v, nx, ny, nz, material);
            emitCorner(out, axis, plane, u, v, nx, ny, nz, material);
            emitCorner(out, axis, plane, u, v + height, nx, ny, nz, material);
            emitCorner(out, axis, plane, u + width, v + height, nx, ny, nz, material);
        }
    }

    private static void emitCorner(FloatBuilder out, int axis, float plane,
                                   float u, float v, float nx, float ny, float nz,
                                   short material) {
        switch (axis) {
            case 0 -> out.addVertex(plane, u, v, nx, ny, nz, material);
            case 1 -> out.addVertex(v, plane, u, nx, ny, nz, material);
            default -> out.addVertex(u, v, plane, nx, ny, nz, material);
        }
    }

    private static final class FloatBuilder {
        private float[] values;
        private int size;

        private FloatBuilder(int initialCapacity) {
            values = new float[initialCapacity];
        }

        private void addVertex(float x, float y, float z,
                               float nx, float ny, float nz, short material) {
            ensureCapacity(SectionMesh.FLOATS_PER_VERTEX);
            values[size++] = x;
            values[size++] = y;
            values[size++] = z;
            values[size++] = nx;
            values[size++] = ny;
            values[size++] = nz;
            values[size++] = material;
        }

        private void ensureCapacity(int additional) {
            int required = size + additional;
            if (required > values.length) {
                int capacity = Math.max(required, Math.max(16, values.length * 2));
                values = Arrays.copyOf(values, capacity);
            }
        }

        private int size() {
            return size;
        }

        private void writeTo(ByteBuffer target) {
            for (int i = 0; i < size; i++) target.putFloat(values[i]);
        }
    }
}
