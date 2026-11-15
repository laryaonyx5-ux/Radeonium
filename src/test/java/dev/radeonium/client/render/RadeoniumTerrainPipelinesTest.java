package dev.radeonium.client.render;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RadeoniumTerrainPipelinesTest {
    @Test
    void createsDistinctRadeoniumPipelineIdentitiesForOpaqueAndCutoutLayers() {
        RenderPipeline solid = RadeoniumTerrainPipelines.forLayer(ChunkSectionLayer.SOLID);
        RenderPipeline cutout = RadeoniumTerrainPipelines.forLayer(ChunkSectionLayer.CUTOUT);

        assertNotSame(solid, RenderPipelines.SOLID_TERRAIN);
        assertNotSame(cutout, RenderPipelines.CUTOUT_TERRAIN);
        assertNotSame(solid, cutout);
        assertTrue(RadeoniumTerrainPipelines.owns(solid));
        assertTrue(RadeoniumTerrainPipelines.owns(cutout));
        assertFalse(RadeoniumTerrainPipelines.owns(RenderPipelines.TRANSLUCENT_TERRAIN));
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
    void translucentKeepsHostOverrideOrVanillaFallback() {
        RenderPipeline hostOverride = RenderPipelines.TRANSLUCENT_TERRAIN;

        assertSame(hostOverride,
                RadeoniumTerrainPipelines.resolve(ChunkSectionLayer.TRANSLUCENT, hostOverride));
        assertSame(ChunkSectionLayer.TRANSLUCENT.pipeline(false),
                RadeoniumTerrainPipelines.resolve(ChunkSectionLayer.TRANSLUCENT, null));
    }

    @Test
    void translucentPipelineIsNotClaimedAsRadeoniumOwned() {
        assertThrows(IllegalArgumentException.class,
                () -> RadeoniumTerrainPipelines.forLayer(ChunkSectionLayer.TRANSLUCENT));
    }

    @Test
    void rejectsNullLayer() {
        assertThrows(IllegalArgumentException.class,
                () -> RadeoniumTerrainPipelines.forLayer(null));
        assertThrows(IllegalArgumentException.class,
                () -> RadeoniumTerrainPipelines.resolve(null, null));
    }
}
