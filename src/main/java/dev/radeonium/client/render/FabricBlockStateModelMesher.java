package dev.radeonium.client.render;

import net.fabricmc.fabric.api.client.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshBuilder;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Captures Fabric's enhanced block-model output, including dynamic model hooks.
 *
 * The supplied view must be Minecraft's immutable render-region snapshot
 * (RenderSectionRegion), never the live ClientLevel. The snapshot carries
 * neighboring block state and block-entity model data required by connected
 * and modded models. A missing Fabric renderer is reported explicitly so the
 * caller can retain vanilla compilation rather than silently lose geometry.
 */
public final class FabricBlockStateModelMesher {
    public FabricBlockModelMesh build(
            BlockStateModel model,
            RenderSectionRegion region,
            BlockPos position,
            BlockState state,
            long seed,
            Predicate<Direction> cullTest
    ) {
        if (model == null || region == null || position == null || state == null || cullTest == null) {
            throw new IllegalArgumentException("Model, region, position, state and cull test are required");
        }

        var renderer = RendererAccess.INSTANCE.getRenderer();
        if (renderer == null) {
            throw new UnsupportedOperationException(
                    "No Fabric Renderer is registered; keep vanilla model compilation authoritative");
        }

        MeshBuilder builder = renderer.meshBuilder();
        QuadEmitter emitter = builder.getEmitter();
        FabricBlockStateModel fabricModel = (FabricBlockStateModel) model;
        fabricModel.emitQuads(
                emitter,
                (BlockAndTintGetter) region,
                position.immutable(),
                state,
                RandomSource.create(seed),
                face -> face != null && cullTest.test(face)
        );

        Mesh mesh = builder.build();
        List<Quad> quads = new ArrayList<>(mesh.size());
        mesh.forEach(quad -> quads.add(copyQuad(quad)));
        return new FabricBlockModelMesh(quads);
    }

    private static Quad copyQuad(QuadView quad) {
        float[] positions = new float[12];
        float[] uv = new float[8];
        float[] normals = new float[12];
        int[] colors = new int[4];
        int[] lightmaps = new int[4];
        boolean[] hasNormals = new boolean[4];

        for (int vertex = 0; vertex < 4; vertex++) {
            int p = vertex * 3;
            int t = vertex * 2;
            positions[p] = quad.x(vertex);
            positions[p + 1] = quad.y(vertex);
            positions[p + 2] = quad.z(vertex);
            uv[t] = quad.u(vertex);
            uv[t + 1] = quad.v(vertex);
            colors[vertex] = quad.color(vertex);
            lightmaps[vertex] = quad.lightmap(vertex);
            hasNormals[vertex] = quad.hasNormal(vertex);
            if (hasNormals[vertex]) {
                normals[p] = quad.normalX(vertex);
                normals[p + 1] = quad.normalY(vertex);
                normals[p + 2] = quad.normalZ(vertex);
            }
        }

        return new Quad(
                positions, uv, normals, hasNormals, colors, lightmaps,
                quad.chunkLayer(), quad.atlas(), quad.cullFace(), quad.nominalFace(),
                quad.tintIndex(), quad.ambientOcclusion(), quad.emissive(),
                quad.animated(), quad.diffuseShade()
        );
    }

    /** Deep-copied quad data; no QuadView or mutable emitter escapes the build. */
    public record Quad(
            float[] positions,
            float[] uv,
            float[] normals,
            boolean[] hasNormals,
            int[] colors,
            int[] lightmaps,
            ChunkSectionLayer layer,
            QuadAtlas atlas,
            Direction cullFace,
            Direction nominalFace,
            int tintIndex,
            net.fabricmc.fabric.api.util.TriState ambientOcclusion,
            boolean emissive,
            boolean animated,
            boolean diffuseShade
    ) {
        public Quad {
            positions = positions.clone();
            uv = uv.clone();
            normals = normals.clone();
            hasNormals = hasNormals.clone();
            colors = colors.clone();
            lightmaps = lightmaps.clone();
            if (positions.length != 12 || uv.length != 8 || normals.length != 12
                    || hasNormals.length != 4 || colors.length != 4 || lightmaps.length != 4) {
                throw new IllegalArgumentException("Invalid Fabric quad attribute lengths");
            }
        }

        @Override public float[] positions() { return positions.clone(); }
        @Override public float[] uv() { return uv.clone(); }
        @Override public float[] normals() { return normals.clone(); }
        @Override public boolean[] hasNormals() { return hasNormals.clone(); }
        @Override public int[] colors() { return colors.clone(); }
        @Override public int[] lightmaps() { return lightmaps.clone(); }

        Quad translated(float x, float y, float z) {
            float[] moved = positions.clone();
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = vertex * 3;
                moved[offset] += x;
                moved[offset + 1] += y;
                moved[offset + 2] += z;
            }
            return new Quad(moved, uv, normals, hasNormals, colors, lightmaps,
                    layer, atlas, cullFace, nominalFace, tintIndex, ambientOcclusion,
                    emissive, animated, diffuseShade);
        }
    }

    public static final class FabricBlockModelMesh {
        private final List<Quad> quads;

        private FabricBlockModelMesh(List<Quad> quads) {
            this.quads = List.copyOf(quads);
        }

        public List<Quad> quads() { return quads; }
        public int quadCount() { return quads.size(); }

        static FabricBlockModelMesh combine(List<Quad> quads) {
            return new FabricBlockModelMesh(quads);
        }
        public int quadCount(ChunkSectionLayer layer) {
            int count = 0;
            for (Quad quad : quads) if (quad.layer() == layer) count++;
            return count;
        }
    }
}
