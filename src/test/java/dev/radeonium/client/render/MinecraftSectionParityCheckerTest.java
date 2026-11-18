package dev.radeonium.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftSectionParityCheckerTest {
    @Test
    void emptyRadeoniumAndVanillaSectionsVerifyEveryLayer() {
        var mesh = new MinecraftSectionModelMesher().buildResolved(java.util.List.of());
        var vanilla = new MinecraftCompiledGeometryStore.Snapshot(
                SectionPos.of(0, 0, 0), Map.of(), 1L, 1L);

        var report = MinecraftSectionParityChecker.compare(mesh, vanilla);

        assertTrue(report.matchesAllLayers());
        assertEquals(ChunkSectionLayer.values().length, report.verifiedLayers().size());
    }

    @Test
    void nonModelRenderShapeDisablesRadeoniumParityForTheWholeSection() {
        var mesh = new MinecraftSectionModelMesher().buildResolved(java.util.List.of())
                .withUnsupportedRenderShapeCount(1);
        var vanilla = new MinecraftCompiledGeometryStore.Snapshot(
                SectionPos.of(0, 0, 0), Map.of(), 1L, 1L);

        var report = MinecraftSectionParityChecker.compare(mesh, vanilla);

        assertFalse(report.matchesAllLayers());
        assertTrue(report.verifiedLayers().isEmpty());
        for (var parity : report.layers().values()) {
            assertEquals("section contains non-model render shapes; keep Minecraft fallback", parity.reason());
        }
    }

    @Test
    void blockEntityDisablesRadeoniumParityForTheWholeSection() {
        var mesh = new MinecraftSectionModelMesher().buildResolved(java.util.List.of())
                .withUnsupportedBlockEntityCount(1);
        var vanilla = new MinecraftCompiledGeometryStore.Snapshot(
                SectionPos.of(0, 0, 0), Map.of(), 1L, 1L);

        var report = MinecraftSectionParityChecker.compare(mesh, vanilla);

        assertFalse(report.matchesAllLayers());
        assertTrue(report.verifiedLayers().isEmpty());
        for (var parity : report.layers().values()) {
            assertEquals("section contains block entities; keep Minecraft fallback", parity.reason());
        }
    }

    @Test
    void mismatchedLayerFallsBackWithoutDisablingOtherVerifiedLayers() {
        byte[] vanillaVertices = new byte[MinecraftMeshGpuUpload.VERTEX_STRIDE_BYTES * 4];
        var layer = new MinecraftCompiledGeometryStore.LayerData(
                vanillaVertices, new byte[0], DefaultVertexFormat.BLOCK.toString(),
                PrimitiveTopology.QUADS.toString(), null, 4, 0);
        var vanilla = new MinecraftCompiledGeometryStore.Snapshot(
                SectionPos.of(2, 3, 4), Map.of(ChunkSectionLayer.SOLID, layer), 1L, 1L);
        var mesh = new MinecraftSectionModelMesher().buildResolved(java.util.List.of());

        var report = MinecraftSectionParityChecker.compare(mesh, vanilla);

        assertFalse(report.layers().get(ChunkSectionLayer.SOLID).matches());
        assertFalse(report.verifiedLayers().contains(ChunkSectionLayer.SOLID));
        assertTrue(report.verifiedLayers().contains(ChunkSectionLayer.TRANSLUCENT));
    }

    @Test
    void wrongVanillaVertexFormatIsNeverAccepted() {
        byte[] vanillaVertices = new byte[MinecraftMeshGpuUpload.VERTEX_STRIDE_BYTES * 4];
        var layer = new MinecraftCompiledGeometryStore.LayerData(
                vanillaVertices, new byte[0], "wrong-format",
                PrimitiveTopology.QUADS.toString(), null, 4, 0);
        var vanilla = new MinecraftCompiledGeometryStore.Snapshot(
                SectionPos.of(0, 0, 0), Map.of(ChunkSectionLayer.CUTOUT, layer), 1L, 1L);
        var mesh = new MinecraftSectionModelMesher().buildResolved(java.util.List.of());

        var report = MinecraftSectionParityChecker.compare(mesh, vanilla);

        assertFalse(report.layers().get(ChunkSectionLayer.CUTOUT).matches());
    }

    @Test
    void nonQuadVertexCountIsNeverAccepted() {
        byte[] vanillaVertices = new byte[MinecraftMeshGpuUpload.VERTEX_STRIDE_BYTES * 3];
        var layer = new MinecraftCompiledGeometryStore.LayerData(
                vanillaVertices, new byte[0], DefaultVertexFormat.BLOCK.toString(),
                PrimitiveTopology.QUADS.toString(), null, 3, 0);
        var vanilla = new MinecraftCompiledGeometryStore.Snapshot(
                SectionPos.of(1, 2, 3), Map.of(ChunkSectionLayer.SOLID, layer), 1L, 1L);
        var mesh = new MinecraftSectionModelMesher().buildResolved(java.util.List.of());

        var report = MinecraftSectionParityChecker.compare(mesh, vanilla);

        assertFalse(report.layers().get(ChunkSectionLayer.SOLID).matches());
        assertEquals("quad vertex records differ",
                report.layers().get(ChunkSectionLayer.SOLID).reason());
    }

    @Test
    void wrongPrimitiveTopologyIsNeverAccepted() {
        byte[] vanillaVertices = new byte[MinecraftMeshGpuUpload.VERTEX_STRIDE_BYTES * 4];
        var layer = new MinecraftCompiledGeometryStore.LayerData(
                vanillaVertices, new byte[0], DefaultVertexFormat.BLOCK.toString(),
                "TRIANGLES", null, 4, 0);
        var vanilla = new MinecraftCompiledGeometryStore.Snapshot(
                SectionPos.of(4, 5, 6), Map.of(ChunkSectionLayer.TRANSLUCENT, layer), 1L, 1L);
        var mesh = new MinecraftSectionModelMesher().buildResolved(java.util.List.of());

        var report = MinecraftSectionParityChecker.compare(mesh, vanilla);

        assertFalse(report.layers().get(ChunkSectionLayer.TRANSLUCENT).matches());
        assertEquals("Minecraft layer uses a different vertex format or topology",
                report.layers().get(ChunkSectionLayer.TRANSLUCENT).reason());
    }
}
