package dev.radeonium.client.render;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.resources.Identifier;

public final class RadeoniumTerrainPipelines {
    private static final Identifier VERTEX_SHADER =
            Identifier.fromNamespaceAndPath("radeonium", "core/terrain");
    private static final Identifier FRAGMENT_SHADER =
            Identifier.fromNamespaceAndPath("radeonium", "core/terrain");

    private static final RenderPipeline SOLID = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
            .withVertexShader(VERTEX_SHADER)
            .withFragmentShader(FRAGMENT_SHADER)
            .withLocation(Identifier.fromNamespaceAndPath("radeonium", "pipeline/terrain_solid"))
            .build();

    private static final RenderPipeline CUTOUT = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
            .withVertexShader(VERTEX_SHADER)
            .withFragmentShader(FRAGMENT_SHADER)
            .withShaderDefine("ALPHA_CUTOUT", 0.1F)
            .withLocation(Identifier.fromNamespaceAndPath("radeonium", "pipeline/terrain_cutout"))
            .build();

    private static final OitPipelineSet TRANSLUCENT_OIT = OitPipelineSet.builder(
            "terrain_translucent",
            RenderPipeline.builder(RenderPipelines.MULTIDRAW_TERRAIN_SNIPPET)
                    .withVertexShader(VERTEX_SHADER)
                    .withFragmentShader(FRAGMENT_SHADER)
    ).build();

    private RadeoniumTerrainPipelines() {}

    public static RenderPipeline forLayer(ChunkSectionLayer layer) {
        if (layer == null) throw new IllegalArgumentException("layer cannot be null");
        return switch (layer) {
            case SOLID -> SOLID;
            case CUTOUT -> CUTOUT;
            case TRANSLUCENT -> throw new IllegalArgumentException(
                    "translucent terrain uses the OIT stage resolver or the compatibility fallback");
        };
    }

    public static RenderPipeline resolve(
            ChunkSectionLayer layer,
            RenderPipeline hostOverride,
            RenderPipeline hostOverrideMultidraw
    ) {
        if (layer == null) throw new IllegalArgumentException("layer cannot be null");
        if (layer != ChunkSectionLayer.TRANSLUCENT) return forLayer(layer);

        // A non-vanilla override belongs to another mod and must never be
        // replaced merely because the other override slot contains a vanilla OIT stage.
        if (hostOverride != null && !isVanillaOitStage(hostOverride)) return hostOverride;
        if (hostOverrideMultidraw != null && !isVanillaOitStage(hostOverrideMultidraw)) {
            return hostOverrideMultidraw;
        }

        RenderPipeline ownedOit = resolveHostOitStage(hostOverride);
        if (ownedOit == null) ownedOit = resolveHostOitStage(hostOverrideMultidraw);
        if (ownedOit != null) return ownedOit;
        return layer.pipeline(false);
    }

    public static RenderPipeline resolve(ChunkSectionLayer layer, RenderPipeline hostOverride) {
        return resolve(layer, hostOverride, null);
    }

    private static boolean isVanillaOitStage(RenderPipeline candidate) {
        if (candidate == null) return false;
        OitPipelineSet separate = RenderPipelines.OIT_TERRAIN;
        OitPipelineSet multidraw = RenderPipelines.OIT_TERRAIN_MULTIDRAW;
        for (OitStage stage : OitStage.values()) {
            if (candidate == separate.getPipeline(stage)
                    || candidate == multidraw.getPipeline(stage)) return true;
        }
        return false;
    }

    private static RenderPipeline resolveHostOitStage(RenderPipeline candidate) {
        if (candidate == null) return null;
        OitPipelineSet vanillaSeparate = RenderPipelines.OIT_TERRAIN;
        OitPipelineSet vanillaMultiDraw = RenderPipelines.OIT_TERRAIN_MULTIDRAW;

        if (candidate == vanillaSeparate.depthBoundsPipeline()
                || candidate == vanillaMultiDraw.depthBoundsPipeline()) {
            return TRANSLUCENT_OIT.getPipeline(OitStage.DEPTH_BOUNDS);
        }
        if (candidate == vanillaSeparate.transmittancePipeline()
                || candidate == vanillaMultiDraw.transmittancePipeline()) {
            return TRANSLUCENT_OIT.getPipeline(OitStage.TRANSMITTANCE);
        }
        if (candidate == vanillaSeparate.accumulatePipeline()
                || candidate == vanillaMultiDraw.accumulatePipeline()) {
            return TRANSLUCENT_OIT.getPipeline(OitStage.ACCUMULATE);
        }
        return null;
    }

    public static boolean owns(RenderPipeline pipeline) {
        if (pipeline == null) return false;
        if (pipeline == SOLID || pipeline == CUTOUT) return true;
        for (OitStage stage : OitStage.values()) {
            if (pipeline == TRANSLUCENT_OIT.getPipeline(stage)) return true;
        }
        return false;
    }

    static OitPipelineSet translucentOitPipelines() {
        return TRANSLUCENT_OIT;
    }
}
