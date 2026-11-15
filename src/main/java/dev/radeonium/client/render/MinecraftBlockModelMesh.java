package dev.radeonium.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;

import java.util.List;
import java.util.ArrayList;
import java.nio.ByteBuffer;

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
        if (quads == null) throw new IllegalArgumentException("quads cannot be null");
        this.quads = List.copyOf(quads);
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

    /** Returns an immutable mesh containing only quads for the requested vanilla render layer. */
    public MinecraftBlockModelMesh forLayer(ChunkSectionLayer layer) {
        if (layer == null) throw new IllegalArgumentException("layer cannot be null");
        List<Quad> selected = new ArrayList<>();
        for (Quad quad : quads) {
            if (quad.layer() == layer) selected.add(quad);
        }
        return new MinecraftBlockModelMesh(selected);
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
        private final BakedQuad sourceQuad;
        private final boolean partAmbientOcclusion;
        private final int tintColor;

        Quad(
                float[] positions,
                long[] packedUv,
                Direction direction,
                ChunkSectionLayer layer,
                BakedQuad.MaterialInfo material,
                BakedQuad sourceQuad,
                boolean partAmbientOcclusion,
                int tintColor
        ) {
            if (positions == null || packedUv == null || positions.length != 12 || packedUv.length != 4) {
                throw new IllegalArgumentException("A baked quad needs four positions and four UV pairs");
            }
            if (layer == null || material == null || sourceQuad == null) {
                throw new IllegalArgumentException("A baked quad needs layer, material and source data");
            }
            for (float coordinate : positions) {
                if (!Float.isFinite(coordinate)) {
                    throw new IllegalArgumentException("Baked quad positions must be finite");
                }
            }
            this.positions = positions.clone();
            this.packedUv = packedUv.clone();
            this.direction = direction;
            this.layer = layer;
            this.material = material;
            this.sourceQuad = sourceQuad;
            this.partAmbientOcclusion = partAmbientOcclusion;
            this.tintColor = tintColor;
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

        /** Original immutable baked quad, retaining precomputed normals and colors. */
        public BakedQuad sourceQuad() {
            return sourceQuad;
        }

        /** Effective AO combines the quad material flag with its model-part flag. */
        public boolean ambientOcclusion() {
            return partAmbientOcclusion;
        }

        public int tintIndex() {
            return material.tintIndex();
        }

        public int lightEmission() {
            return material.lightEmission();
        }

        public boolean isTinted() {
            return material.isTinted();
        }

        /** Captured ARGB tint; -1 when this quad has no resolved tint source. */
        public int tintColor() { return tintColor; }

        /**
         * Writes two triangles in the vertex format used by
         * MinecraftMeshGpuUpload: float3 position + packed UV split into ints.
         */
        void writeTriangleVertices(ByteBuffer target) {
            int[] order = {0, 1, 2, 0, 2, 3};
            for (int vertex : order) {
                int offset = vertex * 3;
                target.putFloat(positions[offset]);
                target.putFloat(positions[offset + 1]);
                target.putFloat(positions[offset + 2]);
                target.putInt((int) packedUv[vertex]);
                target.putInt((int) (packedUv[vertex] >>> 32));
            }
        }
    }
}
