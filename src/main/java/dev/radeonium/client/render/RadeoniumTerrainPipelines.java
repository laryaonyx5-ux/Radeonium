package dev.radeonium.client.render;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.resources.Identifier;

/**
 * Radeonium-owned terrain pipeline identities and layer selection.
 *
 * Each pipeline is a distinct RenderPearl pipeline with its own location and
 * render-state object. The vanilla terrain snippets are deliberately retained
 * as the compatibility baseline for shader bindings, depth state and blending;
 * Milestone F owns the translucent/OIT replacement.
 */
public final class RadeoniumTerrainPipelines {
    private static final RenderPipeline SOLID = copy(
            RenderPipelines.SOLID_TERRAIN, "terrain_solid");
    private static final RenderPipeline CUTOUT = copy(
            RenderPipelines.CUTOUT_TERRAIN, "terrain_cutout");
    private static final RenderPipeline TRANSLUCENT = copy(
            RenderPipelines.TRANSLUCENT_TERRAIN, "terrain_translucent");

    private RadeoniumTerrainPipelines() {}

    private static RenderPipeline copy(RenderPipeline template, String path) {
        return template.toBuilder()
                .withLocation(Identifier.fromNamespaceAndPath("radeonium", "pipeline/" + path))
                .build();
    }

    /** Returns Radeonium's pipeline for a terrain layer. */
    public static RenderPipeline forLayer(ChunkSectionLayer layer) {
        if (layer == null) throw new IllegalArgumentException("layer cannot be null");
        return switch (layer) {
            case SOLID -> SOLID;
            case CUTOUT -> CUTOUT;
            case TRANSLUCENT -> TRANSLUCENT;
        };
    }

    /**
     * Keeps Minecraft's supplied translucent override when present because it
     * may represent an OIT accumulation/composite pass. Opaque and cutout
     * submissions always use Radeonium's separately identified pipelines.
     */
    public static RenderPipeline resolve(
            ChunkSectionLayer layer, RenderPipeline hostOverride
    ) {
        if (layer == null) throw new IllegalArgumentException("layer cannot be null");
        if (layer == ChunkSectionLayer.TRANSLUCENT && hostOverride != null) {
            return hostOverride;
        }
        return forLayer(layer);
    }

    /** True when the pipeline is one of Radeonium's registered identities. */
    public static boolean owns(RenderPipeline pipeline) {
        if (pipeline == null) return false;
        String location = pipeline.getLocation().toString();
        return location.equals("radeonium:pipeline/terrain_solid")
                || location.equals("radeonium:pipeline/terrain_cutout")
                || location.equals("radeonium:pipeline/terrain_translucent");
    }
}
