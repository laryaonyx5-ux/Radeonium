package dev.radeonium.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable CPU-side geometry extracted from Minecraft's baked block models.
 *
 * The MaterialInfo keeps the atlas sprite, render layer, tint index, light
 * emission and ambient-occlusion flag together with each quad. Instances must
 * be discarded when Minecraft reloads its models or texture atlases.
 */
public final class MinecraftBlockModelMesh {
    private final List<Quad> quads;

    MinecraftBlockModelMesh(List<Quad> quads) {
        this.quads = Collections.unmodifiableList(new ArrayList<>(quads));
    }

    public List<Quad> quads() {
        return quads;
    }

    public int quadCount() {
        return quads.size();
    }

    public int vertexCount() {
        return quads.size() * 4;
    }

    public int quadCount(ChunkSectionLayer layer) {
        int count = 0;
        for (Quad quad : quads) {
            if (quad.layer() == layer) count++;
        }
        return count;
    }

    public static final class Quad {
        private final float[] positions;
        private final long[] packedUv;
        private final Direction direction;
        private final ChunkSectionLayer layer;
        private final BakedQuad.MaterialInfo material;

        Quad(
                float[] positions,
                long[] packedUv,
                Direction direction,
                ChunkSectionLayer layer,
                BakedQuad.MaterialInfo material
        ) {
            if (positions.length != 12 || packedUv.length != 4) {
                throw new IllegalArgumentException("A baked quad needs four positions and four UV pairs");
            }
            this.positions = positions.clone();
            this.packedUv = packedUv.clone();
            this.direction = direction;
            this.layer = layer;
            this.material = material;
        }

        public float[] copyPositions() {
            return positions.clone();
        }

        public long[] copyPackedUv() {
            return packedUv.clone();
        }

        public Direction direction() {
            return direction;
        }

        public ChunkSectionLayer layer() {
            return layer;
        }

        public BakedQuad.MaterialInfo material() {
            return material;
        }
    }
}
