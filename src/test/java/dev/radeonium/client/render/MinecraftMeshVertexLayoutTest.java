package dev.radeonium.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinecraftMeshVertexLayoutTest {
    @Test
    void uploadedVerticesMatchMinecraftBlockFormat() {
        assertEquals(0, MinecraftMeshGpuUpload.POSITION_OFFSET_BYTES);
        assertEquals(12, MinecraftMeshGpuUpload.COLOR_OFFSET_BYTES);
        assertEquals(16, MinecraftMeshGpuUpload.UV_OFFSET_BYTES);
        assertEquals(24, MinecraftMeshGpuUpload.LIGHT_OFFSET_BYTES);
        assertEquals(28, MinecraftMeshGpuUpload.VERTEX_STRIDE_BYTES);
        assertEquals(DefaultVertexFormat.BLOCK.getVertexSize(), MinecraftMeshGpuUpload.VERTEX_STRIDE_BYTES);
    }
}
