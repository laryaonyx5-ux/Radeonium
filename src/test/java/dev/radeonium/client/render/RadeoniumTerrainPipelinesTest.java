package dev.radeonium.client.render;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RadeoniumTerrainPipelinesTest {
    @Test
    void createsDistinctRadeoniumPipelineIdentitiesForEveryTerrainLayer() {
        RenderPipeline solid = RadeoniumTerrainPipelines.forLayer(ChunkSectionLayer.SOLID);
        RenderPipeline cutout = RadeoniumTerrainPipelines.forLayer(ChunkSectionLayer.CUTOUT);
        RenderPipeline translucent = RadeoniumTerrainPipelines.forLayer(ChunkSectionLayer.TRANSLUCENT);

        assertNotSame(solid, RenderPipelines.SOLID_TERRAIN);
        assertNotSame(cutout, RenderPipelines.CUTOUT_TERRAIN);
        assertNotSame(translucent, RenderPipelines.TRANSLUCENT_TERRAIN);
        assertNotSame(solid, cutout);
        assertNotSame(cutout, translucent);
        assertTrue(RadeoniumTerrainPipelines.owns(solid));
        assertTrue(RadeoniumTerrainPipelines.owns(cutout));
        assertTrue(RadeoniumTerrainPipelines.owns(translucent));
    }

    @Test
    void opaqueAndCutoutNeverUseTheHostPipelineOverride() {
        RenderPipeline hostOverride = RenderPipelines.SOLID_TERRAIN;

        assertSame(RadeoniumTerrainPipelines.forLayer(ChunkSectionLayer.SOLID),
                RadeoniumTerrainPipelines.resolve(ChunkSectionLayer.SOLID, hostOverride));
        assertSame(RadeoniumTerrainPipelines.forLayer(ChunkSectionLayer.CUTOUT),
                RadeoniumTerrainPipelines.resolve(ChunkSectionLayer.CUTOUT, hostOverride));
    }

    @Test
    void translucentKeepsHostOverrideForOitCompatibility() {
        RenderPipeline hostOverride = RenderPipelines.TRANSLUCENT_TERRAIN;

        assertSame(hostOverride,
                RadeoniumTerrainPipelines.resolve(ChunkSectionLayer.TRANSLUCENT, hostOverride));
        assertSame(RadeoniumTerrainPipelines.forLayer(ChunkSectionLayer.TRANSLUCENT),
                RadeoniumTerrainPipelines.resolve(ChunkSectionLayer.TRANSLUCENT, null));
    }

    @Test
    void rejectsNullLayer() {
        assertThrows(IllegalArgumentException.class,
                () -> RadeoniumTerrainPipelines.forLayer(null));
        assertThrows(IllegalArgumentException.class,
                () -> RadeoniumTerrainPipelines.resolve(null, null));
    }
}
