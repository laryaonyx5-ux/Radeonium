package dev.radeonium.client.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Greedy voxel mesher for immutable section snapshots.
 *
 * Adjacent coplanar faces with the same material are merged into one quad.
 * The output is a private Radeonium vertex format (position, normal, material)
 * and is not yet a Minecraft terrain draw format.
 */
public final class SectionMeshBuilder {
    private static final int FLOATS_PER_VERTEX = 7;
    private static final int BYTES_PER_VERTEX = FLOATS_PER_VERTEX * Float.BYTES;

    public SectionMesh build(VoxelSectionSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot cannot be null");

        ByteBuffer output = ByteBuffer.allocate(estimateWorstCaseBytes())
                .order(ByteOrder.nativeOrder());
        int quadCount = 0;
        short[] mask = new short[16 * 16];

        for (int axis = 0; axis < 3; axis++) {
            int uAxis = (axis + 1) % 3;
            int vAxis = (axis + 2) % 3;

            for (int sign : new int[]{-1, 1}) {
                for (int slice = 0; slice < 16; slice++) {
                    java.util.Arrays.fill(mask, (short) 0);

                    for (int v = 0; v < 16; v++) {
                        for (int u = 0; u < 16; u++) {
                            int[] p = coordinates(axis, uAxis, vAxis, slice, u, v);
                            short material = snapshot.materialAt(p[0], p[1], p[2]);
                            if (material == 0) continue;

                            int[] neighbor = p.clone();
                            neighbor[axis] += sign;
                            if (snapshot.materialAt(neighbor[0], neighbor[1], neighbor[2]) == 0) {
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
                            emitQuad(output, axis, uAxis, vAxis, plane, u, v, width, height, sign, material);
                            quadCount++;

                            for (int dy = 0; dy < height; dy++) {
                                for (int dx = 0; dx < width; dx++) {
                                    mask[u + dx + (v + dy) * 16] = 0;
                                }
                            }
                            u += width;
                        }
                    }
                }
            }
        }

        output.flip();
        byte[] bytes = new byte[output.remaining()];
        output.get(bytes);
        return new SectionMesh(bytes, quadCount * 6);
    }

    private static int estimateWorstCaseBytes() {
        // Six faces for every voxel, six vertices per face, seven floats per vertex.
        return VoxelSectionSnapshot.VOLUME * 6 * 6 * BYTES_PER_VERTEX;
    }

    private static int[] coordinates(int axis, int uAxis, int vAxis,
                                     int slice, int u, int v) {
        int[] p = new int[3];
        p[axis] = slice;
        p[uAxis] = u;
        p[vAxis] = v;
        return p;
    }

    private static void emitQuad(ByteBuffer out, int axis, int uAxis, int vAxis,
                                 float plane, int u, int v, int width, int height,
                                 int sign, short material) {
        float[][] corners = new float[4][3];
        setPoint(corners[0], axis, uAxis, vAxis, plane, u, v);
        setPoint(corners[1], axis, uAxis, vAxis, plane, u + width, v);
        setPoint(corners[2], axis, uAxis, vAxis, plane, u + width, v + height);
        setPoint(corners[3], axis, uAxis, vAxis, plane, u, v + height);

        int[] order = sign > 0 ? new int[]{0, 1, 2, 0, 2, 3}
                : new int[]{0, 2, 1, 0, 3, 2};
        float nx = axis == 0 ? sign : 0;
        float ny = axis == 1 ? sign : 0;
        float nz = axis == 2 ? sign : 0;

        for (int index : order) {
            float[] point = corners[index];
            out.putFloat(point[0]).putFloat(point[1]).putFloat(point[2]);
            out.putFloat(nx).putFloat(ny).putFloat(nz).putFloat(material);
        }
    }

    private static void setPoint(float[] point, int axis, int uAxis, int vAxis,
                                 float plane, float u, float v) {
        point[axis] = plane;
        point[uAxis] = u;
        point[vAxis] = v;
    }
}
