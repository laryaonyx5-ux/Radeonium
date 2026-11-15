package dev.radeonium.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a section from immutable, already-captured block inputs.
 *
 * Model variant and multipart selection is delegated to BlockStateModel.collectParts
 * through MinecraftBlockModelMesher. Inputs must be captured on Minecraft's
 * permitted build thread; this class never reads a Level, Chunk or BlockEntity.
 *
 * Face removal is deliberately conservative: only a quad lying exactly on a
 * block boundary can be culled, and only when the capture layer explicitly
 * marks the adjacent face as fully occluding. Inset, crossed and partial model
 * geometry is never removed by this test.
 */
public final class MinecraftSectionModelMesher {
    private static final float EDGE_EPSILON = 1.0e-5f;

    private final MinecraftBlockModelMesher blockMesher = new MinecraftBlockModelMesher();

    public SectionModelMesh build(List<BlockInput> blocks) {
        if (blocks == null) throw new IllegalArgumentException("blocks cannot be null");

        Map<ChunkSectionLayer, List<MinecraftBlockModelMesh.Quad>> byLayer =
                new EnumMap<>(ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            byLayer.put(layer, new ArrayList<>());
        }

        for (BlockInput input : blocks) {
            if (input == null) continue;
            MinecraftBlockModelMesh blockMesh = blockMesher.build(input.model(), input.modelSeed());
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
                                positions,
                                quad.copyPackedUv(),
                                quad.direction(),
                                quad.layer(),
                                quad.material(),
                                quad.sourceQuad(),
                                quad.ambientOcclusion()
                        ));
            }
        }

        Map<ChunkSectionLayer, MinecraftBlockModelMesh> result =
                new EnumMap<>(ChunkSectionLayer.class);
        for (Map.Entry<ChunkSectionLayer, List<MinecraftBlockModelMesh.Quad>> entry : byLayer.entrySet()) {
            result.put(entry.getKey(), new MinecraftBlockModelMesh(entry.getValue()));
        }
        return new SectionModelMesh(result);
    }

    /**
     * True only when all four vertices lie on the outward boundary plane
     * indicated by the baked quad's cull direction.
     */
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
     * Immutable block input. The six booleans describe full-face occlusion
     * from the corresponding adjacent block, captured before worker dispatch.
     */
    public record BlockInput(
            BlockStateModel model,
            int localX,
            int localY,
            int localZ,
            long modelSeed,
            boolean downOccluded,
            boolean upOccluded,
            boolean northOccluded,
            boolean southOccluded,
            boolean westOccluded,
            boolean eastOccluded
    ) {
        public BlockInput {
            if (model == null) throw new IllegalArgumentException("model cannot be null");
            if (localX < 0 || localX > 15 || localY < 0 || localY > 15
                    || localZ < 0 || localZ > 15) {
                throw new IllegalArgumentException("local block coordinates must be in [0, 15]");
            }
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

    /** Immutable, independent meshes for each Minecraft terrain layer. */
    public static final class SectionModelMesh {
        private final Map<ChunkSectionLayer, MinecraftBlockModelMesh> layers;

        private SectionModelMesh(Map<ChunkSectionLayer, MinecraftBlockModelMesh> layers) {
            this.layers = Map.copyOf(layers);
        }

        public MinecraftBlockModelMesh layer(ChunkSectionLayer layer) {
            if (layer == null) throw new IllegalArgumentException("layer cannot be null");
            return layers.getOrDefault(layer, new MinecraftBlockModelMesh(List.of()));
        }

        public Map<ChunkSectionLayer, MinecraftBlockModelMesh> layers() {
            return layers;
        }

        public int quadCount() {
            int total = 0;
            for (MinecraftBlockModelMesh mesh : layers.values()) total += mesh.quadCount();
            return total;
        }
    }
}
