package dev.radeonium.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.BlockModelLighter;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Builds a section from immutable, already-captured block inputs.
 *
 * The preferred worker input is ResolvedBlockInput: model variant selection
 * and multipart collection have already happened on Minecraft's build thread.
 * The worker then only reads the baked parts and copies their quad data.
 *
 * Face removal is conservative: only a quad lying exactly on a block boundary
 * can be culled, and only when the captured adjacent face fully occludes it.
 */
public final class MinecraftSectionModelMesher {
    private static final float EDGE_EPSILON = 1.0e-5f;

    private final MinecraftBlockModelMesher blockMesher = new MinecraftBlockModelMesher();

    /**
     * Captures the actual 16x16x16 block inputs from Minecraft's immutable
     * compile region and resolves each state's deterministic model choice.
     * Call from SectionCompiler's build thread. The resulting worker input
     * contains no live world, block state, position or block entity objects.
     *
     * Face culling uses BlockState.skipRendering against each copied neighbor;
     * special block-entity renderers remain a separate vanilla fallback path.
     */
    public SectionModelMesh captureAndBuild(
            SectionPos sectionPos, RenderSectionRegion region, BlockStateModelSet modelSet
    ) {
        CapturedSectionInput captured = captureInputs(sectionPos, region, modelSet);
        return buildResolved(captured.blocks()).withUnsupportedBlockEntityCount(captured.unsupportedBlockEntityCount())
                .withFluids(captured.fluids());
    }

    private static Map<BakedQuad, CapturedQuadLighting> captureQuadLighting(
            BlockState state, BlockPos position, RenderSectionRegion region,
            List<BlockStateModelPart> parts, int packedLightCoords, boolean ambientOcclusionEnabled,
            BlockModelLighter lighter
    ) {
        Map<BakedQuad, CapturedQuadLighting> lighting = new java.util.IdentityHashMap<>();
        for (BlockStateModelPart part : parts) {
            if (part == null) continue;
            captureQuadLighting(lighting, part.getQuads(null), part.useAmbientOcclusion() && ambientOcclusionEnabled,
                    state, position, region, packedLightCoords, lighter);
            for (Direction direction : Direction.values()) {
                captureQuadLighting(lighting, part.getQuads(direction),
                        part.useAmbientOcclusion() && ambientOcclusionEnabled,
                        state, position, region, packedLightCoords, lighter);
            }
        }
        return java.util.Collections.unmodifiableMap(lighting);
    }

    private static void captureQuadLighting(
            Map<BakedQuad, CapturedQuadLighting> lighting, List<BakedQuad> quads,
            boolean useAmbientOcclusion, BlockState state, BlockPos position,
            RenderSectionRegion region, int packedLightCoords, BlockModelLighter lighter
    ) {
        if (quads == null) return;
        for (BakedQuad quad : quads) {
            QuadInstance instance = new QuadInstance();
            if (useAmbientOcclusion) {
                lighter.prepareQuadAmbientOcclusion(region, state, position, quad, instance);
            } else {
                lighter.prepareQuadFlat(region, state, position, packedLightCoords, quad, instance);
            }
            int[] colors = new int[4];
            int[] lightCoords = new int[4];
            for (int vertex = 0; vertex < 4; vertex++) {
                colors[vertex] = instance.getColor(vertex);
                lightCoords[vertex] = instance.getLightCoordsWithEmission(
                        vertex, quad.materialInfo().lightEmission());
            }
            lighting.put(quad, new CapturedQuadLighting(colors, lightCoords));
        }
    }

    private static Map<Integer, Integer> captureTintColors(
            BlockState state, BlockPos position, RenderSectionRegion region,
            List<BlockStateModelPart> parts
    ) {
        Map<Integer, Integer> colors = new HashMap<>();
        var blockColors = Minecraft.getInstance().getBlockColors();
        for (BlockStateModelPart part : parts) {
            if (part == null) continue;
            collectTintColors(colors, part.getQuads(null), blockColors, state, position, region);
            for (Direction direction : Direction.values()) {
                collectTintColors(colors, part.getQuads(direction), blockColors, state, position, region);
            }
        }
        return Map.copyOf(colors);
    }

    private static void collectTintColors(
            Map<Integer, Integer> colors, List<BakedQuad> quads,
            net.minecraft.client.color.block.BlockColors blockColors,
            BlockState state, BlockPos position, RenderSectionRegion region
    ) {
        if (quads == null) return;
        for (BakedQuad quad : quads) {
            var material = quad.materialInfo();
            int tintIndex = material.tintIndex();
            if (!material.isTinted() || colors.containsKey(tintIndex)) continue;
            BlockTintSource source = blockColors.getTintSource(state, tintIndex);
            if (source != null) colors.put(tintIndex, source.colorInWorld(state, region, position));
        }
    }

    /** Capture all world-dependent data on Minecraft's compiler thread before asynchronous meshing. */
    public CapturedSectionInput captureInputs(
            SectionPos sectionPos, RenderSectionRegion region, BlockStateModelSet modelSet
    ) {
        if (sectionPos == null || region == null || modelSet == null) {
            throw new IllegalArgumentException("sectionPos, region and modelSet are required");
        }
        List<ResolvedBlockInput> resolved = new ArrayList<>(4096);
        int blockEntities = 0;
        int baseX = sectionPos.minBlockX(), baseY = sectionPos.minBlockY(), baseZ = sectionPos.minBlockZ();
        BlockState[] states = new BlockState[18 * 18 * 18];
        BlockModelLighter lighter = new BlockModelLighter();
        for (int y = -1; y <= 16; y++) for (int z = -1; z <= 16; z++) for (int x = -1; x <= 16; x++) {
            states[haloIndex(x, y, z)] = region.getBlockState(new BlockPos(baseX + x, baseY + y, baseZ + z));
        }
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockState state = states[haloIndex(x, y, z)];
            BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
            if (region.getBlockEntity(pos) != null) blockEntities++;
            if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) continue;
            BlockStateModel blockModel = modelSet.get(state);
            List<BlockStateModelPart> selectedParts = new ArrayList<>();
            blockModel.collectParts(net.minecraft.util.RandomSource.create(state.getSeed(pos)), selectedParts);
            Map<Integer, Integer> tintColors = captureTintColors(state, pos, region, selectedParts);
            int packedLightCoords = lighter.getLightCoords(state, region, pos);
            Map<BakedQuad, CapturedQuadLighting> quadLighting = captureQuadLighting(
                    state, pos, region, selectedParts, packedLightCoords,
                    Minecraft.getInstance().options.ambientOcclusion().get(), lighter);
            resolved.add(new ResolvedBlockInput(selectedParts, x, y, z,
                    state.skipRendering(states[haloIndex(x, y - 1, z)], Direction.DOWN),
                    state.skipRendering(states[haloIndex(x, y + 1, z)], Direction.UP),
                    state.skipRendering(states[haloIndex(x, y, z - 1)], Direction.NORTH),
                    state.skipRendering(states[haloIndex(x, y, z + 1)], Direction.SOUTH),
                    state.skipRendering(states[haloIndex(x - 1, y, z)], Direction.WEST),
                    state.skipRendering(states[haloIndex(x + 1, y, z)], Direction.EAST),
                    tintColors, packedLightCoords, quadLighting));
        }
        MinecraftFluidSectionMesher.SectionFluidMesh fluids =
                new MinecraftFluidSectionMesher().capture(sectionPos, region);
        return new CapturedSectionInput(resolved, blockEntities, fluids);
    }

    /**
     * Compatibility entry point. This performs collectParts while building and
     * should only be used where the caller has established model thread safety.
     */
    public SectionModelMesh build(List<BlockInput> blocks) {
        if (blocks == null) throw new IllegalArgumentException("blocks cannot be null");
        List<ResolvedBlockInput> resolved = new ArrayList<>(blocks.size());
        for (BlockInput input : blocks) {
            if (input == null) continue;
            resolved.add(ResolvedBlockInput.capture(
                    input.model(), input.modelSeed(),
                    input.localX(), input.localY(), input.localZ(),
                    input.downOccluded(), input.upOccluded(), input.northOccluded(),
                    input.southOccluded(), input.westOccluded(), input.eastOccluded()));
        }
        return buildResolved(resolved);
    }

    /**
     * Builds from pre-resolved baked parts. Resolve each block state's actual
     * variant/multipart parts and its position-dependent random choice on the
     * Minecraft-approved build thread, then pass this immutable list to workers.
     */
    public SectionModelMesh buildResolved(List<ResolvedBlockInput> blocks) {
        if (blocks == null) throw new IllegalArgumentException("blocks cannot be null");

        Map<ChunkSectionLayer, List<MinecraftBlockModelMesh.Quad>> byLayer =
                new EnumMap<>(ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            byLayer.put(layer, new ArrayList<>());
        }

        for (ResolvedBlockInput input : blocks) {
            if (input == null) continue;
            MinecraftBlockModelMesh blockMesh = blockMesher.build(input.parts(), input.tintColors(), input.packedLightCoords(), input.quadLighting());
            for (MinecraftBlockModelMesh.Quad quad : blockMesh.quads()) {
                Direction face = quad.direction();
                if (face != null && input.neighborOccludes(face)
                        && liesOnBlockBoundary(quad.copyPositions(), face)) {
                    continue;
                }

                float[] positions = quad.copyPositions();
                for (int vertex = 0; vertex < 4; vertex++) {
                    int offset = vertex * 3;
                    positions[offset] += input.localX();
                    positions[offset + 1] += input.localY();
                    positions[offset + 2] += input.localZ();
                }

                byLayer.computeIfAbsent(quad.layer(), ignored -> new ArrayList<>())
                        .add(new MinecraftBlockModelMesh.Quad(
                                positions, quad.copyPackedUv(), quad.direction(),
                                quad.layer(), quad.material(), quad.sourceQuad(),
                                quad.ambientOcclusion(), quad.tintColor(), quad.packedLightCoords(),
                                quad.copyVertexColors(), quad.copyVertexLightCoords()));
            }
        }

        Map<ChunkSectionLayer, MinecraftBlockModelMesh> result =
                new EnumMap<>(ChunkSectionLayer.class);
        for (Map.Entry<ChunkSectionLayer, List<MinecraftBlockModelMesh.Quad>> entry : byLayer.entrySet()) {
            result.put(entry.getKey(), new MinecraftBlockModelMesh(entry.getValue()));
        }
        return new SectionModelMesh(result);
    }

    public static boolean liesOnBlockBoundary(float[] positions, Direction face) {
        if (positions == null || positions.length != 12 || face == null) return false;
        int axis = switch (face.getAxis()) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
        };
        float expected = switch (face) {
            case EAST, UP, SOUTH -> 1.0f;
            case WEST, DOWN, NORTH -> 0.0f;
        };
        for (int vertex = 0; vertex < 4; vertex++) {
            float coordinate = positions[vertex * 3 + axis];
            if (!Float.isFinite(coordinate) || Math.abs(coordinate - expected) > EDGE_EPSILON) {
                return false;
            }
        }
        return true;
    }

    /** Immutable per-vertex lighting result from Minecraft's BlockModelLighter. */
    public record CapturedQuadLighting(int[] colors, int[] lightCoords) {
        public CapturedQuadLighting {
            if (colors == null || lightCoords == null || colors.length != 4 || lightCoords.length != 4) {
                throw new IllegalArgumentException("quad lighting needs four colors and four light values");
            }
            colors = colors.clone();
            lightCoords = lightCoords.clone();
        }
        @Override public int[] colors() { return colors.clone(); }
        @Override public int[] lightCoords() { return lightCoords.clone(); }
    }

    /** Immutable, worker-safe result of section input capture. */
    public record CapturedSectionInput(
            List<ResolvedBlockInput> blocks,
            int unsupportedBlockEntityCount,
            MinecraftFluidSectionMesher.SectionFluidMesh fluids
    ) {
        public CapturedSectionInput {
            if (blocks == null || unsupportedBlockEntityCount < 0 || fluids == null) {
                throw new IllegalArgumentException("invalid capture");
            }
            blocks = List.copyOf(blocks);
        }

        public CapturedSectionInput(List<ResolvedBlockInput> blocks, int unsupportedBlockEntityCount) {
            this(blocks, unsupportedBlockEntityCount, MinecraftFluidSectionMesher.SectionFluidMesh.empty());
        }
    }

    /**
     * Input for callers that have not separated model resolution from meshing.
     * Prefer ResolvedBlockInput for asynchronous section builds.
     */
    public record BlockInput(
            BlockStateModel model,
            int localX, int localY, int localZ,
            long modelSeed,
            boolean downOccluded, boolean upOccluded,
            boolean northOccluded, boolean southOccluded,
            boolean westOccluded, boolean eastOccluded
    ) {
        public BlockInput {
            if (model == null) throw new IllegalArgumentException("model cannot be null");
            validateCoordinates(localX, localY, localZ);
        }
    }

    /**
     * Immutable snapshot of already-selected baked model parts and face
     * occlusion. No Level, BlockState, BlockEntity or live chunk is retained.
     */
    public record ResolvedBlockInput(
            List<BlockStateModelPart> parts,
            int localX, int localY, int localZ,
            boolean downOccluded, boolean upOccluded,
            boolean northOccluded, boolean southOccluded,
            boolean westOccluded, boolean eastOccluded,
            Map<Integer, Integer> tintColors,
            int packedLightCoords,
            Map<BakedQuad, CapturedQuadLighting> quadLighting
    ) {
        public ResolvedBlockInput {
            if (parts == null || tintColors == null || quadLighting == null) throw new IllegalArgumentException("captured block inputs cannot be null");
            parts = List.copyOf(parts);
            tintColors = Map.copyOf(tintColors);
            quadLighting = Map.copyOf(quadLighting);
            validateCoordinates(localX, localY, localZ);
        }

        /**
         * Resolves the model's deterministic variant/weighted/multipart choice
         * immediately on the caller's thread and freezes the resulting part list.
         * Call this during Minecraft section capture, never from a meshing worker
         * when the model implementation may consult thread-confined resources.
         */
        public static ResolvedBlockInput capture(
                BlockStateModel model, long modelSeed,
                int localX, int localY, int localZ,
                boolean downOccluded, boolean upOccluded,
                boolean northOccluded, boolean southOccluded,
                boolean westOccluded, boolean eastOccluded
        ) {
            if (model == null) throw new IllegalArgumentException("model cannot be null");
            List<BlockStateModelPart> selectedParts = new ArrayList<>();
            model.collectParts(net.minecraft.util.RandomSource.create(modelSeed), selectedParts);
            return new ResolvedBlockInput(selectedParts, localX, localY, localZ,
                    downOccluded, upOccluded, northOccluded, southOccluded,
                    westOccluded, eastOccluded, Map.of(), -1, Map.of());
        }

        boolean neighborOccludes(Direction direction) {
            return switch (direction) {
                case DOWN -> downOccluded;
                case UP -> upOccluded;
                case NORTH -> northOccluded;
                case SOUTH -> southOccluded;
                case WEST -> westOccluded;
                case EAST -> eastOccluded;
            };
        }
    }

    private static int haloIndex(int x, int y, int z) {
        if (x < -1 || x > 16 || y < -1 || y > 16 || z < -1 || z > 16) {
            throw new IndexOutOfBoundsException("Section capture halo coordinates must be in [-1, 16]");
        }
        return (x + 1) + 18 * ((z + 1) + 18 * (y + 1));
    }

    private static void validateCoordinates(int x, int y, int z) {
        if (x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15) {
            throw new IllegalArgumentException("local block coordinates must be in [0, 15]");
        }
    }

    private static boolean neighborOccludes(BlockInput input, Direction direction) {
        return switch (direction) {
            case DOWN -> input.downOccluded();
            case UP -> input.upOccluded();
            case NORTH -> input.northOccluded();
            case SOUTH -> input.southOccluded();
            case WEST -> input.westOccluded();
            case EAST -> input.eastOccluded();
        };
    }

    /** Independent meshes for every Minecraft terrain layer. */
    public static final class SectionModelMesh {
        private final Map<ChunkSectionLayer, MinecraftBlockModelMesh> layers;
        private final int unsupportedBlockEntityCount;
        private final MinecraftFluidSectionMesher.SectionFluidMesh fluids;

        private SectionModelMesh(Map<ChunkSectionLayer, MinecraftBlockModelMesh> layers) {
            this(layers, 0, MinecraftFluidSectionMesher.SectionFluidMesh.empty());
        }

        private SectionModelMesh(
                Map<ChunkSectionLayer, MinecraftBlockModelMesh> layers,
                int unsupportedBlockEntityCount
        ) {
            this(layers, unsupportedBlockEntityCount, MinecraftFluidSectionMesher.SectionFluidMesh.empty());
        }

        private SectionModelMesh(
                Map<ChunkSectionLayer, MinecraftBlockModelMesh> layers,
                int unsupportedBlockEntityCount,
                MinecraftFluidSectionMesher.SectionFluidMesh fluids
        ) {
            if (unsupportedBlockEntityCount < 0 || fluids == null) {
                throw new IllegalArgumentException("invalid section model mesh metadata");
            }
            this.layers = Map.copyOf(layers);
            this.unsupportedBlockEntityCount = unsupportedBlockEntityCount;
            this.fluids = fluids;
        }

        public MinecraftBlockModelMesh layer(ChunkSectionLayer layer) {
            if (layer == null) throw new IllegalArgumentException("layer cannot be null");
            return layers.getOrDefault(layer, new MinecraftBlockModelMesh(List.of()));
        }

        public Map<ChunkSectionLayer, MinecraftBlockModelMesh> layers() { return layers; }

        public MinecraftFluidSectionMesher.FluidLayerMesh fluids(ChunkSectionLayer layer) {
            return fluids.layer(layer);
        }

        public MinecraftFluidSectionMesher.SectionFluidMesh fluidMesh() { return fluids; }

        /** Block entities require their dedicated renderer and are not baked into these block meshes. */
        public int unsupportedBlockEntityCount() { return unsupportedBlockEntityCount; }

        public SectionModelMesh withUnsupportedBlockEntityCount(int count) {
            return new SectionModelMesh(layers, count, fluids);
        }

        public SectionModelMesh withFluids(MinecraftFluidSectionMesher.SectionFluidMesh fluids) {
            return new SectionModelMesh(layers, unsupportedBlockEntityCount, fluids);
        }

        public int quadCount() {
            int total = 0;
            for (MinecraftBlockModelMesh mesh : layers.values()) total += mesh.quadCount();
            return total + fluids.quadCount();
        }
    }
}
