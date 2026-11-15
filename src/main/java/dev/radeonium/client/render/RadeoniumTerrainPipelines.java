package dev.radeonium.client.render;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.resources.Identifier;

/**
 * Radeonium-owned RenderPearl pipeline identities for opaque terrain.
 *
 * The pipelines reuse Minecraft's public terrain shader/bind-group snippet as
 * a compatibility baseline, but have independent Radeonium locations. The
 * translucent layer deliberately remains host-owned until Milestone F can
 * replace its OIT/depth-bounds integration without changing transparency.
 */
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

    private RadeoniumTerrainPipelines() {}

    /** Returns Radeonium's pipeline for an opaque/cutout layer. */
    public static RenderPipeline forLayer(ChunkSectionLayer layer) {
        if (layer == null) throw new IllegalArgumentException("layer cannot be null");
        return switch (layer) {
            case SOLID -> SOLID;
            case CUTOUT -> CUTOUT;
            case TRANSLUCENT -> throw new IllegalArgumentException(
                    "translucent terrain remains host-owned until Milestone F");
        };
    }

    /**
     * Opaque and cutout draws always use Radeonium pipeline identities.
     * Translucent draws preserve the frame graph's supplied OIT pipeline or
     * fall back to Minecraft's matching translucent pipeline.
     */
    public static RenderPipeline resolve(
            ChunkSectionLayer layer, RenderPipeline hostOverride
    ) {
        if (layer == null) throw new IllegalArgumentException("layer cannot be null");
        if (layer == ChunkSectionLayer.TRANSLUCENT) {
            return hostOverride != null ? hostOverride : layer.pipeline(false);
        }
        return forLayer(layer);
    }

    /** True when the pipeline is one of Radeonium's opaque/cutout identities. */
    public static boolean owns(RenderPipeline pipeline) {
        if (pipeline == null) return false;
        String location = pipeline.getLocation().toString();
        return location.equals("radeonium:pipeline/terrain_solid")
                || location.equals("radeonium:pipeline/terrain_cutout");
    }
}
