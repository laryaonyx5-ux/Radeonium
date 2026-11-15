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
        assertEquals(36, builder.build(new VoxelSectionSnapshot(blocks)).vertexCount());
    }

    @Test
    void adjacentOpaqueDifferentMaterialsCullTheirSharedFace() {
        short[] blocks = new short[4096];
        blocks[index(4, 5, 6)] = 7;
        blocks[index(5, 5, 6)] = 8;
        // Ten exterior quads remain (two shared interior faces are culled).
        assertEquals(60, builder.build(new VoxelSectionSnapshot(blocks)).vertexCount());
    }

    @Test
    void neighborHaloCullsFacesAtSectionBoundary() {
        short[] halo = new short[VoxelSectionSnapshot.HALO_VOLUME];
        halo[haloIndex(16, 9, 9)] = 3; // target section x=15
        halo[haloIndex(17, 9, 9)] = 9; // adjacent section x=16
        VoxelSectionSnapshot snapshot = VoxelSectionSnapshot.withNeighborHalo(halo);

        SectionMesh mesh = builder.build(snapshot);
        // The adjacent block hides the +X face, leaving five faces.
        assertEquals(30, mesh.vertexCount());
    }

    @Test
    void missingNeighborHaloLeavesBoundaryFaceVisible() {
        short[] blocks = new short[4096];
        blocks[index(15, 8, 8)] = 3;
        assertEquals(36, builder.build(new VoxelSectionSnapshot(blocks)).vertexCount());
    }

    @Test
    void haloInputIsDefensivelyCopied() {
        short[] halo = new short[VoxelSectionSnapshot.HALO_VOLUME];
        halo[haloIndex(8, 8, 8)] = 5;
        VoxelSectionSnapshot snapshot = VoxelSectionSnapshot.withNeighborHalo(halo);
        halo[haloIndex(8, 8, 8)] = 99;
        assertEquals(5, snapshot.materialAt(7, 7, 7));
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

    private static int haloIndex(int x, int y, int z) {
        return x + z * VoxelSectionSnapshot.HALO_SIZE
                + y * VoxelSectionSnapshot.HALO_SIZE * VoxelSectionSnapshot.HALO_SIZE;
    }
}
