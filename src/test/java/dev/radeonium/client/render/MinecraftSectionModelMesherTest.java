package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftSectionModelMesherTest {
    @Test
    void cullsOnlyQuadsOnTheExactBoundaryPlane() {
        float[] eastFace = {1,0,0, 1,1,0, 1,1,1, 1,0,1};
        assertTrue(MinecraftSectionModelMesher.liesOnBlockBoundary(eastFace, Direction.EAST));
        assertFalse(MinecraftSectionModelMesher.liesOnBlockBoundary(eastFace, Direction.WEST));
    }

    @Test
    void keepsInsetAndCrossedGeometry() {
        float[] inset = {.9f,0,0, .9f,1,0, .9f,1,1, .9f,0,1};
        float[] crossed = {0,0,0, 1,1,0, 1,1,1, 0,0,1};
        assertFalse(MinecraftSectionModelMesher.liesOnBlockBoundary(inset, Direction.EAST));
        assertFalse(MinecraftSectionModelMesher.liesOnBlockBoundary(crossed, Direction.EAST));
    }

    @Test
    void rejectsInvalidGeometry() {
        assertFalse(MinecraftSectionModelMesher.liesOnBlockBoundary(new float[9], Direction.UP));
        float[] invalid = new float[12];
        invalid[1] = Float.NaN;
        assertFalse(MinecraftSectionModelMesher.liesOnBlockBoundary(invalid, Direction.UP));
        assertFalse(MinecraftSectionModelMesher.liesOnBlockBoundary(new float[12], null));
    }

    @Test
    void resolvedInputsCopyTheirPartList() {
        List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> source = new ArrayList<>();
        var input = new MinecraftSectionModelMesher.ResolvedBlockInput(
                source, 1, 2, 3, false, false, false, false, false, false, java.util.Map.of(), -1);
        source.clear();
        assertTrue(input.parts().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> input.parts().clear());
    }

    @Test
    void resolvedInputsRejectCoordinatesOutsideTheirSection() {
        assertThrows(IllegalArgumentException.class, () ->
                new MinecraftSectionModelMesher.ResolvedBlockInput(
                        List.of(), 16, 0, 0, false, false, false, false, false, false, java.util.Map.of()));
    }


    @Test
    void resolvedInputsCopyTintColorsForWorkerSafety() {
        var source = new java.util.HashMap<Integer, Integer>();
        source.put(0, 0xFF80A040);
        var input = new MinecraftSectionModelMesher.ResolvedBlockInput(
                List.of(), 1, 2, 3, false, false, false, false, false, false, source, 0x00F000F0);

        source.put(0, 0xFFFFFFFF);
        assertEquals(0xFF80A040, input.tintColors().get(0));
        assertEquals(0x00F000F0, input.packedLightCoords());
        assertThrows(UnsupportedOperationException.class,
                () -> input.tintColors().put(1, 0xFFFFFFFF));
    }

    @Test
    void resolvedEmptySectionProducesAnEmptyMeshForEveryLayer() {
        var mesh = new MinecraftSectionModelMesher().buildResolved(List.of());
        assertEquals(0, mesh.quadCount());
        for (var layer : net.minecraft.client.renderer.chunk.ChunkSectionLayer.values()) {
            assertEquals(0, mesh.layer(layer).quadCount());
        }
    }
}
