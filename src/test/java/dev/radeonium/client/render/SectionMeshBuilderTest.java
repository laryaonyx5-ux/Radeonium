package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class SectionMeshBuilderTest {
    private final SectionMeshBuilder builder = new SectionMeshBuilder();

    @Test
    void emptySectionProducesEmptyMesh() {
        SectionMesh mesh = builder.build(new VoxelSectionSnapshot(new short[4096]));

        assertTrue(mesh.isEmpty());
        assertEquals(0, mesh.vertexCount());
        assertEquals(0, mesh.byteSize());
    }

    @Test
    void oneSolidVoxelProducesSixFaces() {
        short[] blocks = new short[4096];
        blocks[index(4, 5, 6)] = 1;

        SectionMesh mesh = builder.build(new VoxelSectionSnapshot(blocks));

        assertEquals(36, mesh.vertexCount());
        assertEquals(36 * SectionMesh.VERTEX_STRIDE_BYTES, mesh.byteSize());
        assertEquals(SectionMesh.VERTEX_STRIDE_BYTES, mesh.vertexStrideBytes());
    }

    @Test
    void adjacentEqualMaterialVoxelsAreGreedilyMerged() {
        short[] blocks = new short[4096];
        blocks[index(4, 5, 6)] = 7;
        blocks[index(5, 5, 6)] = 7;

        SectionMesh mesh = builder.build(new VoxelSectionSnapshot(blocks));

        // The pair is one rectangular prism: six exterior quads, not ten.
        assertEquals(36, mesh.vertexCount());
    }

    @Test
    void differentMaterialsAreNotMergedAcrossTheirBoundary() {
        short[] blocks = new short[4096];
        blocks[index(4, 5, 6)] = 7;
        blocks[index(5, 5, 6)] = 8;

        SectionMesh mesh = builder.build(new VoxelSectionSnapshot(blocks));

        // The internal shared face is hidden, but material boundaries remain
        // separate on the exterior surfaces.
        assertEquals(60, mesh.vertexCount());
    }

    @Test
    void sectionMeshRejectsInconsistentVertexByteCount() {
        assertThrows(IllegalArgumentException.class, () -> new SectionMesh(new byte[28], 2));
        assertThrows(IllegalArgumentException.class, () -> new SectionMesh(new byte[0], 1));
    }

    @Test
    void sectionMeshDefensivelyCopiesVertexBytes() {
        byte[] source = new byte[SectionMesh.VERTEX_STRIDE_BYTES];
        source[0] = 12;
        SectionMesh mesh = new SectionMesh(source, 1);

        source[0] = 99;
        byte[] copy = mesh.copyVertexData();
        copy[0] = 88;

        assertEquals(12, mesh.copyVertexData()[0]);
    }

    @Test
    void emittedVerticesContainFinitePositionsAndUnitAxisNormals() {
        short[] blocks = new short[4096];
        blocks[index(1, 2, 3)] = 1;
        SectionMesh mesh = builder.build(new VoxelSectionSnapshot(blocks));
        ByteBuffer data = ByteBuffer.wrap(mesh.copyVertexData()).order(ByteOrder.nativeOrder());

        while (data.hasRemaining()) {
            float x = data.getFloat();
            float y = data.getFloat();
            float z = data.getFloat();
            float nx = data.getFloat();
            float ny = data.getFloat();
            float nz = data.getFloat();
            float material = data.getFloat();

            assertTrue(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z));
            assertTrue(Float.isFinite(material));
            assertEquals(1.0f, Math.abs(nx) + Math.abs(ny) + Math.abs(nz));
        }
    }

    private static int index(int x, int y, int z) {
        return x | (y << 4) | (z << 8);
    }
}
