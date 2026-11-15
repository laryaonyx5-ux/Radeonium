package dev.radeonium.client.render;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftSectionModelMesherTest {
    @Test
    void cullsOnlyQuadsOnTheExactBoundaryPlane() {
        float[] eastFace = {
                1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1
        };
        assertTrue(MinecraftSectionModelMesher.liesOnBlockBoundary(eastFace, Direction.EAST));
        assertFalse(MinecraftSectionModelMesher.liesOnBlockBoundary(eastFace, Direction.WEST));
    }

    @Test
    void keepsInsetAndCrossedGeometry() {
        float[] inset = {
                .9f, 0, 0, .9f, 1, 0, .9f, 1, 1, .9f, 0, 1
        };
        float[] crossed = {
                0, 0, 0, 1, 1, 0, 1, 1, 1, 0, 0, 1
        };
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
}
