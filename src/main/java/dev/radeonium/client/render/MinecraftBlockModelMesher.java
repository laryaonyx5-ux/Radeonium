package dev.radeonium.client.render;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Copies already-selected Minecraft baked model parts into immutable
 * Radeonium geometry. World-dependent tint and lighting are captured before
 * this class runs on the meshing worker.
 */
public final class MinecraftBlockModelMesher {
    public MinecraftBlockModelMesh build(BlockStateModel model, long seed) {
        if (model == null) throw new IllegalArgumentException("model cannot be null");
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(seed), parts);
        return build(parts);
    }

    public MinecraftBlockModelMesh build(List<BlockStateModelPart> modelParts) {
        return build(modelParts, Map.of(), -1, Map.of());
    }

    public MinecraftBlockModelMesh build(
            List<BlockStateModelPart> modelParts, Map<Integer, Integer> tintColors
    ) {
        return build(modelParts, tintColors, -1, Map.of());
    }

    public MinecraftBlockModelMesh build(
            List<BlockStateModelPart> modelParts,
            Map<Integer, Integer> tintColors,
            int packedLightCoords
    ) {
        return build(modelParts, tintColors, packedLightCoords, Map.of());
    }

    /** Copies baked geometry with tint, lightmap and per-vertex AO already resolved. */
    public MinecraftBlockModelMesh build(
            List<BlockStateModelPart> modelParts,
            Map<Integer, Integer> tintColors,
            int packedLightCoords,
            Map<BakedQuad, MinecraftSectionModelMesher.CapturedQuadLighting> quadLighting
    ) {
        if (modelParts == null || tintColors == null || quadLighting == null) {
            throw new IllegalArgumentException("modelParts, tintColors and quadLighting are required");
        }

        List<MinecraftBlockModelMesh.Quad> quads = new ArrayList<>();
        for (BlockStateModelPart part : modelParts) {
            if (part == null) continue;
            appendQuads(quads, part.getQuads(null), part.useAmbientOcclusion(),
                    tintColors, packedLightCoords, quadLighting);
            for (Direction direction : Direction.values()) {
                appendQuads(quads, part.getQuads(direction), part.useAmbientOcclusion(),
                        tintColors, packedLightCoords, quadLighting);
            }
        }
        return new MinecraftBlockModelMesh(quads);
    }

    private static void appendQuads(
            List<MinecraftBlockModelMesh.Quad> output,
            List<BakedQuad> source,
            boolean partAmbientOcclusion,
            Map<Integer, Integer> tintColors,
            int packedLightCoords,
            Map<BakedQuad, MinecraftSectionModelMesher.CapturedQuadLighting> quadLighting
    ) {
        if (source == null || source.isEmpty()) return;

        for (BakedQuad quad : source) {
            Vector3fc p0 = quad.position(0);
            Vector3fc p1 = quad.position(1);
            Vector3fc p2 = quad.position(2);
            Vector3fc p3 = quad.position(3);
            float[] positions = {
                    p0.x(), p0.y(), p0.z(),
                    p1.x(), p1.y(), p1.z(),
                    p2.x(), p2.y(), p2.z(),
                    p3.x(), p3.y(), p3.z()
            };
            long[] packedUv = {
                    quad.packedUV(0), quad.packedUV(1),
                    quad.packedUV(2), quad.packedUV(3)
            };

            BakedQuad.MaterialInfo material = quad.materialInfo();
            MinecraftSectionModelMesher.CapturedQuadLighting captured = quadLighting.get(quad);
            int[] colors = captured == null
                    ? new int[]{-1, -1, -1, -1} : captured.colors();
            int[] lightCoords = captured == null
                    ? new int[]{packedLightCoords, packedLightCoords, packedLightCoords, packedLightCoords}
                    : captured.lightCoords();

            output.add(new MinecraftBlockModelMesh.Quad(
                    positions, packedUv, quad.direction(), material.layer(), material, quad,
                    partAmbientOcclusion,
                    tintColors.getOrDefault(material.tintIndex(), -1),
                    packedLightCoords, colors, lightCoords
            ));
        }
    }
}
