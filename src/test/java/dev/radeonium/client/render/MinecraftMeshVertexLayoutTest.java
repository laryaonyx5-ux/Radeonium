package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinecraftMeshVertexLayoutTest {
    @Test
    void vertexAttributesAreContiguousAndMatchTheUploadStride() {
        assertEquals(0, MinecraftMeshGpuUpload.POSITION_OFFSET_BYTES);
        assertEquals(12, MinecraftMeshGpuUpload.UV_LOW_OFFSET_BYTES);
        assertEquals(16, MinecraftMeshGpuUpload.UV_HIGH_OFFSET_BYTES);
        assertEquals(20, MinecraftMeshGpuUpload.TINT_OFFSET_BYTES);
        assertEquals(24, MinecraftMeshGpuUpload.BLOCK_LIGHT_OFFSET_BYTES);
        assertEquals(28, MinecraftMeshGpuUpload.AO_COLOR_OFFSET_BYTES);
        assertEquals(32, MinecraftMeshGpuUpload.VERTEX_LIGHT_OFFSET_BYTES);
        assertEquals(36, MinecraftMeshGpuUpload.MATERIAL_INDEX_OFFSET_BYTES);
        assertEquals(40, MinecraftMeshGpuUpload.VERTEX_STRIDE_BYTES);
        assertEquals(
                MinecraftMeshGpuUpload.VERTEX_STRIDE_BYTES,
                MinecraftMeshGpuUpload.MATERIAL_INDEX_OFFSET_BYTES + Integer.BYTES
        );
    }
}
