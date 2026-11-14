package dev.radeonium.client.render;

import java.util.Arrays;

/**
 * Immutable CPU-side section mesh payload.
 *
 * The byte layout is deliberately renderer-format agnostic for now. The
 * eventual backend-specific uploader owns interpretation and GPU resources.
 */
public final class SectionMesh {
    private final byte[] vertexData;
    private final int vertexCount;

    public SectionMesh(byte[] vertexData, int vertexCount) {
        if (vertexData == null) {
            throw new IllegalArgumentException("vertexData cannot be null");
        }
        if (vertexCount < 0) {
            throw new IllegalArgumentException("vertexCount cannot be negative");
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

    public byte[] copyVertexData() {
        return Arrays.copyOf(vertexData, vertexData.length);
    }

    public boolean isEmpty() {
        return vertexCount == 0 || vertexData.length == 0;
    }
}
