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
        private final int packedLightCoords;
        private final int[] vertexColors;
        private final int[] vertexLightCoords;

        Quad(
                float[] positions,
                long[] packedUv,
                Direction direction,
                ChunkSectionLayer layer,
                BakedQuad.MaterialInfo material,
                BakedQuad sourceQuad,
                boolean partAmbientOcclusion,
                int tintColor,
                int packedLightCoords,
                int[] vertexColors,
                int[] vertexLightCoords
        ) {
            if (positions == null || packedUv == null || positions.length != 12 || packedUv.length != 4) {
                throw new IllegalArgumentException("A baked quad needs four positions and four UV pairs");
            }
            if (layer == null || material == null || sourceQuad == null
                    || vertexColors == null || vertexLightCoords == null
                    || vertexColors.length != 4 || vertexLightCoords.length != 4) {
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
            this.packedLightCoords = packedLightCoords;
            this.vertexColors = vertexColors.clone();
            this.vertexLightCoords = vertexLightCoords.clone();
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

        /** Packed block/sky light sampled from Minecraft's section region. */
        public int packedLightCoords() { return packedLightCoords; }
        public int[] copyVertexColors() { return vertexColors.clone(); }
        public int[] copyVertexLightCoords() { return vertexLightCoords.clone(); }

        /**
         * Writes one quad in Minecraft's DefaultVertexFormat.BLOCK layout:
         * position (RGB32F), multiplied AO/tint color (RGBA8), atlas UV (RG32F),
         * and per-vertex packed block/sky light (RG16_SINT).
         */
        void writeBlockVertices(ByteBuffer target) {
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = vertex * 3;
                target.putFloat(positions[offset]);
                target.putFloat(positions[offset + 1]);
                target.putFloat(positions[offset + 2]);
                target.putInt(multiplyArgb(vertexColors[vertex], tintColor));
                target.putFloat(Float.intBitsToFloat((int) packedUv[vertex]));
                target.putFloat(Float.intBitsToFloat((int) (packedUv[vertex] >>> 32)));
                target.putShort((short) (vertexLightCoords[vertex] & 0xffff));
                target.putShort((short) ((vertexLightCoords[vertex] >>> 16) & 0xffff));
            }
        }

        private static int multiplyArgb(int color, int tint) {
            if (tint == -1) return color;
            int a = ((color >>> 24) & 255) * ((tint >>> 24) & 255) / 255;
            int r = ((color >>> 16) & 255) * ((tint >>> 16) & 255) / 255;
            int g = ((color >>> 8) & 255) * ((tint >>> 8) & 255) / 255;
            int b = (color & 255) * (tint & 255) / 255;
            return (a << 24) | (r << 16) | (g << 8) | b;
        }
    }
}
