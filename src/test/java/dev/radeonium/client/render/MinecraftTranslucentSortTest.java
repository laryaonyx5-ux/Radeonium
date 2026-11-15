package dev.radeonium.client.render;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinecraftTranslucentSortTest {
    @Test
    void sortsSectionQuadsFarToNearFromTheCamera() {
        var near = quadAt(1.0f);
        var far = quadAt(10.0f);

        List<MinecraftMeshGpuUpload.QuadData> sorted =
                MinecraftSectionGpuCache.sortTranslucentQuads(
                        new SectionPos(0, 0, 0), List.of(near, far), new Vec3(0, 0, 0));

        assertEquals(10.0f, sorted.get(0).positions()[0]);
        assertEquals(1.0f, sorted.get(1).positions()[0]);
    }

    private static MinecraftMeshGpuUpload.QuadData quadAt(float x) {
        return new MinecraftMeshGpuUpload.QuadData(
                new float[]{x,0,0, x,1,0, x,1,1, x,0,1},
                new float[8],
                new int[]{-1,-1,-1,-1},
                new int[4]
        );
    }
}
