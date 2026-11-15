package dev.radeonium.client.render;

import java.util.Arrays;

/**
 * Immutable CPU-side section mesh payload.
 *
 * The current greedy-mesh prototype uses a fixed Radeonium layout:
 * position (float3), normal (float3), material ID (float1). The stride is
 * explicit so malformed payloads cannot reach later upload stages.
 */
public final class SectionMesh {
    public static final int FLOATS_PER_VERTEX = 7;
    public static final int VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * Float.BYTES;

    private final byte[] vertexData;
    private final int vertexCount;

    public SectionMesh(byte[] vertexData, int vertexCount) {
        if (vertexData == null) {
            throw new IllegalArgumentException("vertexData cannot be null");
        }
        if (vertexCount < 0) {
            throw new IllegalArgumentException("vertexCount cannot be negative");
        }
        long expectedBytes = (long) vertexCount * VERTEX_STRIDE_BYTES;
        if (expectedBytes != vertexData.length) {
            throw new IllegalArgumentException(
                    "Vertex byte length must equal vertexCount * " + VERTEX_STRIDE_BYTES
            );
        }
        this.vertexData = Arrays.copyOf(vertexData, vertexData.length);
        this.vertexCount = vertexCount;
    }

    public int byteSize() {
        return vertexData.length;
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int vertexStrideBytes() {
        return VERTEX_STRIDE_BYTES;
    }

    public byte[] copyVertexData() {
        return Arrays.copyOf(vertexData, vertexData.length);
    }

    public boolean isEmpty() {
        return vertexCount == 0;
    }
}
