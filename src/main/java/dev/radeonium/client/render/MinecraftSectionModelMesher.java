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
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
        if (sectionPos == null || region == null || modelSet == null) {
            throw new IllegalArgumentException("sectionPos, region and modelSet are required");
        }

        List<ResolvedBlockInput> resolved = new ArrayList<>(4096);
        int unsupportedBlockEntityCount = 0;
        int baseX = sectionPos.minBlockX();
        int baseY = sectionPos.minBlockY();
        int baseZ = sectionPos.minBlockZ();

        // RenderSectionRegion is a 3x3x3 immutable section copy. Capture its
        // one-block halo once, rather than asking the region for seven states
        // for every interior block.
        BlockState[] states = new BlockState[18 * 18 * 18];
        for (int y = -1; y <= 16; y++) {
            for (int z = -1; z <= 16; z++) {
                for (int x = -1; x <= 16; x++) {
                    BlockPos position = new BlockPos(baseX + x, baseY + y, baseZ + z);
                    states[haloIndex(x, y, z)] = region.getBlockState(position);
                }
            }
        }

        for (int localY = 0; localY < 16; localY++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    BlockState state = states[haloIndex(localX, localY, localZ)];
                    BlockPos position = new BlockPos(
                            baseX + localX, baseY + localY, baseZ + localZ);
                    if (region.getBlockEntity(position) != null) {
                        unsupportedBlockEntityCount++;
                    }
                    if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) continue;

                    BlockStateModel model = modelSet.get(state);
                    boolean down = state.skipRendering(
                            states[haloIndex(localX, localY - 1, localZ)], Direction.DOWN);
                    boolean up = state.skipRendering(
                            states[haloIndex(localX, localY + 1, localZ)], Direction.UP);
                    boolean north = state.skipRendering(
                            states[haloIndex(localX, localY, localZ - 1)], Direction.NORTH);
                    boolean south = state.skipRendering(
                            states[haloIndex(localX, localY, localZ + 1)], Direction.SOUTH);
                    boolean west = state.skipRendering(
                            states[haloIndex(localX - 1, localY, localZ)], Direction.WEST);
                    boolean east = state.skipRendering(
                            states[haloIndex(localX + 1, localY, localZ)], Direction.EAST);

                    resolved.add(ResolvedBlockInput.capture(
                            model, state.getSeed(position), localX, localY, localZ,
                            down, up, north, south, west, east));
                }
            }
        }
        SectionModelMesh mesh = buildResolved(resolved);
        return new SectionModelMesh(mesh.layers(), unsupportedBlockEntityCount);
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
            MinecraftBlockModelMesh blockMesh = blockMesher.build(input.parts());
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
                                quad.ambientOcclusion()));
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
            boolean westOccluded, boolean eastOccluded
    ) {
        public ResolvedBlockInput {
            if (parts == null) throw new IllegalArgumentException("parts cannot be null");
            parts = List.copyOf(parts);
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
                    westOccluded, eastOccluded);
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

        private SectionModelMesh(Map<ChunkSectionLayer, MinecraftBlockModelMesh> layers) {
            this(layers, 0);
        }

        private SectionModelMesh(
                Map<ChunkSectionLayer, MinecraftBlockModelMesh> layers,
                int unsupportedBlockEntityCount
        ) {
            if (unsupportedBlockEntityCount < 0) {
                throw new IllegalArgumentException("unsupported block entity count cannot be negative");
            }
            this.layers = Map.copyOf(layers);
            this.unsupportedBlockEntityCount = unsupportedBlockEntityCount;
        }

        public MinecraftBlockModelMesh layer(ChunkSectionLayer layer) {
            if (layer == null) throw new IllegalArgumentException("layer cannot be null");
            return layers.getOrDefault(layer, new MinecraftBlockModelMesh(List.of()));
        }

        public Map<ChunkSectionLayer, MinecraftBlockModelMesh> layers() { return layers; }

        /** Block entities require their dedicated renderer and are not baked into these block meshes. */
        public int unsupportedBlockEntityCount() { return unsupportedBlockEntityCount; }

        public int quadCount() {
            int total = 0;
            for (MinecraftBlockModelMesh mesh : layers.values()) total += mesh.quadCount();
            return total;
        }
    }
}
