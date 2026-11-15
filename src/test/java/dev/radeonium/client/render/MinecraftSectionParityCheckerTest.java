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
                new SectionPos(0, 0, 0), Map.of(), 1L, 1L);

        var report = MinecraftSectionParityChecker.compare(mesh, vanilla);

        assertTrue(report.matchesAllLayers());
        assertEquals(ChunkSectionLayer.values().length, report.verifiedLayers().size());
    }

    @Test
    void mismatchedLayerFallsBackWithoutDisablingOtherVerifiedLayers() {
        byte[] vanillaVertices = new byte[MinecraftMeshGpuUpload.VERTEX_STRIDE_BYTES * 4];
        var layer = new MinecraftCompiledGeometryStore.LayerData(
                vanillaVertices, new byte[0], DefaultVertexFormat.BLOCK.toString(),
                PrimitiveTopology.QUADS.toString(), null, 4, 0);
        var vanilla = new MinecraftCompiledGeometryStore.Snapshot(
                new SectionPos(2, 3, 4), Map.of(ChunkSectionLayer.SOLID, layer), 1L, 1L);
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
                new SectionPos(0, 0, 0), Map.of(ChunkSectionLayer.CUTOUT, layer), 1L, 1L);
        var mesh = new MinecraftSectionModelMesher().buildResolved(java.util.List.of());

        var report = MinecraftSectionParityChecker.compare(mesh, vanilla);

        assertFalse(report.layers().get(ChunkSectionLayer.CUTOUT).matches());
    }
}
