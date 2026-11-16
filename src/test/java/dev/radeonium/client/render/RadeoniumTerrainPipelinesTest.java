package dev.radeonium.client.render;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.oit.OitStage;
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
                RadeoniumTerrainPipelines.resolve(ChunkSectionLayer.SOLID, hostOverride, null));
        assertSame(RadeoniumTerrainPipelines.forLayer(ChunkSectionLayer.CUTOUT),
                RadeoniumTerrainPipelines.resolve(ChunkSectionLayer.CUTOUT, hostOverride, null));
    }

    @Test
    void createsThreeDistinctRadeoniumOwnedOitStages() {
        OitPipelineSet set = RadeoniumTerrainPipelines.translucentOitPipelines();
        RenderPipeline depth = set.getPipeline(OitStage.DEPTH_BOUNDS);
        RenderPipeline transmittance = set.getPipeline(OitStage.TRANSMITTANCE);
        RenderPipeline accumulate = set.getPipeline(OitStage.ACCUMULATE);

        assertNotSame(depth, transmittance);
        assertNotSame(depth, accumulate);
        assertNotSame(transmittance, accumulate);
        assertTrue(RadeoniumTerrainPipelines.owns(depth));
        assertTrue(RadeoniumTerrainPipelines.owns(transmittance));
        assertTrue(RadeoniumTerrainPipelines.owns(accumulate));
        OitPipelineSet vanilla = RenderPipelines.OIT_TERRAIN;
        assertNotSame(depth, vanilla.depthBoundsPipeline());
        assertNotSame(transmittance, vanilla.transmittancePipeline());
        assertNotSame(accumulate, vanilla.accumulatePipeline());
    }

    @Test
    void mapsVanillaOitStagesToMatchingRadeoniumStages() {
        OitPipelineSet vanilla = RenderPipelines.OIT_TERRAIN;
        OitPipelineSet owned = RadeoniumTerrainPipelines.translucentOitPipelines();

        assertSame(owned.depthBoundsPipeline(),
                RadeoniumTerrainPipelines.resolve(ChunkSectionLayer.TRANSLUCENT,
                        vanilla.depthBoundsPipeline(), null));
        assertSame(owned.transmittancePipeline(),
                RadeoniumTerrainPipelines.resolve(ChunkSectionLayer.TRANSLUCENT,
                        vanilla.transmittancePipeline(), null));
        assertSame(owned.accumulatePipeline(),
                RadeoniumTerrainPipelines.resolve(ChunkSectionLayer.TRANSLUCENT,
                        null, vanilla.accumulatePipeline()));
    }

    @Test
    void mapsMultidrawOitStagesAndPreservesClassicFallback() {
        OitPipelineSet vanilla = RenderPipelines.OIT_TERRAIN_MULTIDRAW;
        OitPipelineSet owned = RadeoniumTerrainPipelines.translucentOitPipelines();

        assertSame(owned.depthBoundsPipeline(),
                RadeoniumTerrainPipelines.resolve(ChunkSectionLayer.TRANSLUCENT,
                        null, vanilla.depthBoundsPipeline()));
        assertSame(owned.transmittancePipeline(),
                RadeoniumTerrainPipelines.resolve(ChunkSectionLayer.TRANSLUCENT,
                        null, vanilla.transmittancePipeline()));
        assertSame(owned.accumulatePipeline(),
                RadeoniumTerrainPipelines.resolve(ChunkSectionLayer.TRANSLUCENT,
                        vanilla.accumulatePipeline(), null));
        assertSame(ChunkSectionLayer.TRANSLUCENT.pipeline(false),
                RadeoniumTerrainPipelines.resolve(ChunkSectionLayer.TRANSLUCENT, null, null));
    }

    @Test
    void unknownHostOverrideIsPreservedForCompatibility() {
        RenderPipeline custom = RenderPipelines.TRANSLUCENT_TERRAIN;
        assertSame(custom, RadeoniumTerrainPipelines.resolve(
                ChunkSectionLayer.TRANSLUCENT, custom, null));
    }

    @Test
    void thirdPartyOverrideWinsOverVanillaOitStageInOtherSlot() {
        RenderPipeline custom = RenderPipelines.TRANSLUCENT_TERRAIN;
        RenderPipeline vanillaStage = RenderPipelines.OIT_TERRAIN.accumulatePipeline();

        assertSame(custom, RadeoniumTerrainPipelines.resolve(
                ChunkSectionLayer.TRANSLUCENT, custom, vanillaStage));
        assertSame(custom, RadeoniumTerrainPipelines.resolve(
                ChunkSectionLayer.TRANSLUCENT, vanillaStage, custom));
    }

    @Test
    void translucentPipelineIsNotExposedAsOpaqueLayerPipeline() {
        assertThrows(IllegalArgumentException.class,
                () -> RadeoniumTerrainPipelines.forLayer(ChunkSectionLayer.TRANSLUCENT));
    }

    @Test
    void rejectsNullLayer() {
        assertThrows(IllegalArgumentException.class,
                () -> RadeoniumTerrainPipelines.forLayer(null));
        assertThrows(IllegalArgumentException.class,
                () -> RadeoniumTerrainPipelines.resolve(null, null, null));
    }
}
