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

/**
 * Resolves Minecraft's baked block-state model into Radeonium-owned CPU
 * geometry. The model's collectParts implementation is responsible for
 * selecting variants, weighted alternatives and multipart pieces.
 *
 * This adapter uses the vanilla RandomSource-only contract. Models whose
 * geometry depends on live level/block-entity context must be resolved by a
 * game-thread adapter with an immutable context snapshot before calling
 * build(List); Radeonium workers must never receive a live world object.
 */
public final class MinecraftBlockModelMesher {
    /**
     * Resolves the selected baked parts for a stable per-block seed and then
     * copies their quads into Radeonium-owned storage. Call once per block
     * state/position; use a position-derived seed to match Minecraft's
     * deterministic chunk-model selection.
     */
    public MinecraftBlockModelMesh build(BlockStateModel model, long seed) {
        if (model == null) {
            throw new IllegalArgumentException("model cannot be null");
        }

        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(seed), parts);
        return build(parts, java.util.Map.of(), -1);
    }

    /**
     * Copies already-resolved parts. This overload is also the integration
     * point for context-aware Fabric model adapters that need a captured
     * level/position/state snapshot to select their parts.
     */
    public MinecraftBlockModelMesh build(List<BlockStateModelPart> modelParts) {
        return build(modelParts, java.util.Map.of(), -1);
    }

    /** Copies resolved parts with per-tint-index colors captured on the compiler thread. */
    public MinecraftBlockModelMesh build(
            List<BlockStateModelPart> modelParts, java.util.Map<Integer, Integer> tintColors
    ) {
        return build(modelParts, tintColors, -1);
    }

    /** Copies resolved geometry with captured tint and packed light inputs. */
    public MinecraftBlockModelMesh build(
            List<BlockStateModelPart> modelParts, java.util.Map<Integer, Integer> tintColors,
            int packedLightCoords
    ) {
        if (modelParts == null || tintColors == null) {
            throw new IllegalArgumentException("modelParts cannot be null");
        }

        List<MinecraftBlockModelMesh.Quad> quads = new ArrayList<>();
        for (BlockStateModelPart part : modelParts) {
            if (part == null) continue;

            appendQuads(quads, part.getQuads(null), part.useAmbientOcclusion(), tintColors);
            for (Direction direction : Direction.values()) {
                appendQuads(quads, part.getQuads(direction), part.useAmbientOcclusion(), tintColors);
            }
        }

        return new MinecraftBlockModelMesh(quads);
    }

    private static void appendQuads(
            List<MinecraftBlockModelMesh.Quad> output,
            List<BakedQuad> source,
            boolean partAmbientOcclusion,
            java.util.Map<Integer, Integer> tintColors
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
                    quad.packedUV(0),
                    quad.packedUV(1),
                    quad.packedUV(2),
                    quad.packedUV(3)
            };

            BakedQuad.MaterialInfo material = quad.materialInfo();
            ChunkSectionLayer layer = material.layer();
            output.add(new MinecraftBlockModelMesh.Quad(
                    positions,
                    packedUv,
                    quad.direction(),
                    layer,
                    material,
                    quad,
                    partAmbientOcclusion,
                    tintColors.getOrDefault(material.tintIndex(), -1),
                    packedLightCoords
            ));
        }
    }
}
