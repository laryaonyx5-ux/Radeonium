package dev.radeonium.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Captures fluid geometry by asking Minecraft's own FluidRenderer to tessellate
 * each fluid state. This preserves vanilla's height interpolation, flow UVs,
 * overlay decisions, face culling, tint, light and selected render layer.
 *
 * Capture runs on the SectionCompiler thread while RenderSectionRegion is
 * valid. Only immutable primitive vertex records leave this class.
 */
public final class MinecraftFluidSectionMesher {
    private static final ThreadLocal<FluidRendererCache> FLUID_RENDERERS =
            ThreadLocal.withInitial(FluidRendererCache::new);

    private static FluidRenderer rendererForCurrentResources() {
        var models = Minecraft.getInstance().getModelManager().getFluidStateModelSet();
        FluidRendererCache cache = FLUID_RENDERERS.get();
        if (cache.models != models) {
            cache.models = models;
            cache.renderer = new FluidRenderer(models);
        }
        return cache.renderer;
    }

    private static final class FluidRendererCache {
        private Object models;
        private FluidRenderer renderer;
    }
    public SectionFluidMesh capture(
            SectionPos sectionPos, RenderSectionRegion region
    ) {
        if (sectionPos == null || region == null) {
            throw new IllegalArgumentException("sectionPos and region are required");
        }

        FluidRenderer renderer = rendererForCurrentResources();
        EnumMap<ChunkSectionLayer, List<FluidQuad>> byLayer =
                new EnumMap<>(ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) byLayer.put(layer, new ArrayList<>());

        int originX = sectionPos.minBlockX();
        int originY = sectionPos.minBlockY();
        int originZ = sectionPos.minBlockZ();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockPos position = new BlockPos(originX + x, originY + y, originZ + z);
                    BlockState blockState = region.getBlockState(position);
                    FluidState fluidState = blockState.getFluidState();
                    if (fluidState.isEmpty()) continue;

                    final int blockX = x;
                    final int blockY = y;
                    final int blockZ = z;
                    EnumMap<ChunkSectionLayer, FluidVertexCollector> collectors =
                            new EnumMap<>(ChunkSectionLayer.class);
                    FluidRenderer.Output output = layer -> collectors.computeIfAbsent(
                            layer, ignored -> new FluidVertexCollector(
                                    layer, blockX, blockY, blockZ, originX, originY, originZ
                            )
                    );
                    renderer.tesselate(region, position, output, blockState, fluidState);
                    for (Map.Entry<ChunkSectionLayer, FluidVertexCollector> entry : collectors.entrySet()) {
                        byLayer.get(entry.getKey()).addAll(entry.getValue().finish());
                    }
                }
            }
        }

        EnumMap<ChunkSectionLayer, FluidLayerMesh> result =
                new EnumMap<>(ChunkSectionLayer.class);
        for (Map.Entry<ChunkSectionLayer, List<FluidQuad>> entry : byLayer.entrySet()) {
            result.put(entry.getKey(), new FluidLayerMesh(entry.getKey(), entry.getValue()));
        }
        return new SectionFluidMesh(result);
    }

    private static final class FluidVertexCollector implements VertexConsumer {
        private final ChunkSectionLayer layer;
        private final int localX;
        private final int localY;
        private final int localZ;
        private final int originX;
        private final int originY;
        private final int originZ;
        private final List<FluidVertex> vertices = new ArrayList<>();
        private MutableVertex current;

        private FluidVertexCollector(
                ChunkSectionLayer layer, int localX, int localY, int localZ,
                int originX, int originY, int originZ
        ) {
            this.layer = layer;
            this.localX = localX;
            this.localY = localY;
            this.localZ = localZ;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            flushVertex();
            current = new MutableVertex(
                    toSectionLocal(x, localX, originX + localX, originX),
                    toSectionLocal(y, localY, originY + localY, originY),
                    toSectionLocal(z, localZ, originZ + localZ, originZ)
            );
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            if (current != null) current.color = color;
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return setColor((alpha & 255) << 24 | (red & 255) << 16
                    | (green & 255) << 8 | (blue & 255));
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            if (current != null) {
                current.u = u;
                current.v = v;
            }
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            if (current != null) current.light = (u & 0xffff) | (v << 16);
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            if (current != null) {
                current.normalX = x;
                current.normalY = y;
                current.normalZ = z;
            }
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        private List<FluidQuad> finish() {
            flushVertex();
            if ((vertices.size() & 3) != 0) {
                throw new IllegalStateException("Minecraft fluid renderer emitted a non-quad vertex count");
            }
            List<FluidQuad> quads = new ArrayList<>(vertices.size() / 4);
            for (int first = 0; first < vertices.size(); first += 4) {
                float[] positions = new float[12];
                float[] uv = new float[8];
                int[] colors = new int[4];
                int[] lights = new int[4];
                float nx = 0.0f, ny = 0.0f, nz = 0.0f;
                for (int vertex = 0; vertex < 4; vertex++) {
                    FluidVertex value = vertices.get(first + vertex);
                    int p = vertex * 3;
                    int t = vertex * 2;
                    positions[p] = value.x;
                    positions[p + 1] = value.y;
                    positions[p + 2] = value.z;
                    uv[t] = value.u;
                    uv[t + 1] = value.v;
                    colors[vertex] = value.color;
                    lights[vertex] = value.light;
                    if (vertex == 0) {
                        nx = value.normalX;
                        ny = value.normalY;
                        nz = value.normalZ;
                    }
                }
                quads.add(new FluidQuad(layer, positions, uv, colors, lights, nx, ny, nz));
            }
            return List.copyOf(quads);
        }

        private void flushVertex() {
            if (current == null) return;
            vertices.add(current.freeze());
            current = null;
        }
    }

    private static float toSectionLocal(float coordinate, int local, int worldBlock, int sectionOrigin) {
        if (coordinate >= -1.0e-4f && coordinate <= 1.0001f) return coordinate + local;
        if (coordinate >= worldBlock - 1.0e-4f && coordinate <= worldBlock + 1.0001f) {
            return coordinate - sectionOrigin;
        }
        return coordinate;
    }

    private static final class MutableVertex {
        private final float x, y, z;
        private int color = 0xffffffff;
        private float u, v;
        private int light;
        private float normalX, normalY, normalZ;

        private MutableVertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private FluidVertex freeze() {
            return new FluidVertex(x, y, z, color, u, v, light, normalX, normalY, normalZ);
        }
    }

    private record FluidVertex(
            float x, float y, float z, int color, float u, float v, int light,
            float normalX, float normalY, float normalZ
    ) {}

    public record FluidQuad(
            ChunkSectionLayer layer, float[] positions, float[] uv, int[] colors, int[] lightCoords,
            float normalX, float normalY, float normalZ
    ) {
        public FluidQuad {
            if (layer == null || positions == null || positions.length != 12
                    || uv == null || uv.length != 8 || colors == null || colors.length != 4
                    || lightCoords == null || lightCoords.length != 4) {
                throw new IllegalArgumentException("Fluid quad requires four complete vertices");
            }
            positions = positions.clone();
            uv = uv.clone();
            colors = colors.clone();
            lightCoords = lightCoords.clone();
            for (float value : positions) if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Fluid positions must be finite");
            }
            for (float value : uv) if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Fluid UVs must be finite");
            }
            if (!Float.isFinite(normalX) || !Float.isFinite(normalY) || !Float.isFinite(normalZ)) {
                throw new IllegalArgumentException("Fluid normals must be finite");
            }
        }
        @Override public float[] positions() { return positions.clone(); }
        @Override public float[] uv() { return uv.clone(); }
        @Override public int[] colors() { return colors.clone(); }
        @Override public int[] lightCoords() { return lightCoords.clone(); }
    }

    public record FluidLayerMesh(ChunkSectionLayer layer, List<FluidQuad> quads) {
        public FluidLayerMesh {
            if (layer == null || quads == null) throw new IllegalArgumentException("layer and quads are required");
            quads = List.copyOf(quads);
            for (FluidQuad quad : quads) if (quad.layer() != layer) {
                throw new IllegalArgumentException("Fluid quad stored in the wrong render layer");
            }
        }
    }

    public static final class SectionFluidMesh {
        private final Map<ChunkSectionLayer, FluidLayerMesh> layers;

        public static SectionFluidMesh empty() {
            EnumMap<ChunkSectionLayer, FluidLayerMesh> empty = new EnumMap<>(ChunkSectionLayer.class);
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                empty.put(layer, new FluidLayerMesh(layer, List.of()));
            }
            return new SectionFluidMesh(empty);
        }

        private SectionFluidMesh(Map<ChunkSectionLayer, FluidLayerMesh> layers) {
            this.layers = Map.copyOf(layers);
        }

        public FluidLayerMesh layer(ChunkSectionLayer layer) {
            if (layer == null) throw new IllegalArgumentException("layer cannot be null");
            return layers.getOrDefault(layer, new FluidLayerMesh(layer, List.of()));
        }

        public int quadCount() {
            int total = 0;
            for (FluidLayerMesh mesh : layers.values()) total += mesh.quads().size();
            return total;
        }
    }
}
