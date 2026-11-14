package dev.radeonium.client.render;

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts the real baked quad geometry from Minecraft 26.3 block-model parts.
 *
 * This deliberately does not pretend to be a complete section compiler:
 * contextual model selection, neighbor culling, tint resolution, lighting/AO,
 * fluids, block entities and GPU upload are handled by later integration
 * stages. Material metadata is retained so those stages do not have to guess.
 */
public final class MinecraftBlockModelMesher {
    private static final Direction[] CULL_DIRECTIONS = Direction.values();

    public MinecraftBlockModelMesh build(List<BlockStateModelPart> modelParts) {
        if (modelParts == null) {
            throw new IllegalArgumentException("modelParts cannot be null");
        }

        List<MinecraftBlockModelMesh.Quad> quads = new ArrayList<>();
        for (BlockStateModelPart part : modelParts) {
            if (part == null) continue;

            appendQuads(quads, part.getQuads(null));
            for (Direction direction : CULL_DIRECTIONS) {
                appendQuads(quads, part.getQuads(direction));
            }
        }

        return new MinecraftBlockModelMesh(quads);
    }

    private static void appendQuads(
            List<MinecraftBlockModelMesh.Quad> output,
            List<BakedQuad> source
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
                    material
            ));
        }
    }
}
