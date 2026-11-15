package dev.radeonium.client.render;

import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Full section assembly path for context-aware Fabric block models.
 *
 * Each input carries the same immutable RenderSectionRegion snapshot used by
 * Minecraft's chunk compiler, plus the block's immutable state and position.
 * The model receives this snapshot for connected textures and block-entity
 * render data; no live ClientLevel is passed to worker code.
 */
public final class FabricSectionModelMesher {
    private final FabricBlockStateModelMesher blockMesher = new FabricBlockStateModelMesher();

    public FabricBlockStateModelMesher.FabricBlockModelMesh build(List<BlockInput> blocks) {
        if (blocks == null) throw new IllegalArgumentException("blocks cannot be null");

        List<FabricBlockStateModelMesher.Quad> quads = new ArrayList<>();
        for (BlockInput input : blocks) {
            if (input == null) continue;

            FabricBlockStateModelMesher.FabricBlockModelMesh blockMesh = blockMesher.build(
                    input.model(),
                    input.region(),
                    input.worldPosition(),
                    input.state(),
                    input.modelSeed(),
                    input::neighborOccludes
            );

            for (FabricBlockStateModelMesher.Quad quad : blockMesh.quads()) {
                quads.add(quad.translated(input.localX(), input.localY(), input.localZ()));
            }
        }

        return FabricBlockStateModelMesher.FabricBlockModelMesh.combine(quads);
    }

    public record BlockInput(
            net.minecraft.client.renderer.block.dispatch.BlockStateModel model,
            RenderSectionRegion region,
            BlockPos worldPosition,
            BlockState state,
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
            if (model == null || region == null || worldPosition == null || state == null) {
                throw new IllegalArgumentException("Model, region, world position and state are required");
            }
            if (localX < 0 || localX > 15 || localY < 0 || localY > 15
                    || localZ < 0 || localZ > 15) {
                throw new IllegalArgumentException("local block coordinates must be in [0, 15]");
            }
            worldPosition = worldPosition.immutable();
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
}
