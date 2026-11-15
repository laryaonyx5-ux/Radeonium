package dev.radeonium.client.render;

import com.mojang.renderpearl.api.pipeline.IndexType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftCompiledGeometryStoreTest {
    @Test
    void layerDataDefensivelyCopiesVertexAndIndexBytes() {
        byte[] vertices = {1, 2, 3};
        byte[] indices = {4, 5};
        var layer = new MinecraftCompiledGeometryStore.LayerData(
                vertices, indices, "format", "QUADS", IndexType.SHORT, 1, 1);

        vertices[0] = 99;
        indices[0] = 99;
        assertArrayEquals(new byte[]{1, 2, 3}, layer.vertexBytes());
        assertArrayEquals(new byte[]{4, 5}, layer.indexBytes());

        byte[] returnedVertices = layer.vertexBytes();
        byte[] returnedIndices = layer.indexBytes();
        returnedVertices[1] = 88;
        returnedIndices[1] = 88;
        assertArrayEquals(new byte[]{1, 2, 3}, layer.vertexBytes());
        assertArrayEquals(new byte[]{4, 5}, layer.indexBytes());
    }

    @Test
    void layerDataRejectsInvalidMetadata() {
        assertThrows(IllegalArgumentException.class, () ->
                new MinecraftCompiledGeometryStore.LayerData(
                        null, new byte[0], "format", "QUADS", null, 0, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new MinecraftCompiledGeometryStore.LayerData(
                        new byte[]{1}, new byte[0], null, "QUADS", null, 1, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new MinecraftCompiledGeometryStore.LayerData(
                        new byte[]{1}, new byte[0], "format", "QUADS", null, -1, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new MinecraftCompiledGeometryStore.LayerData(
                        new byte[]{1}, new byte[0], "format", "QUADS", null, 0, 1));
    }

    @Test
    void layerDataAllowsEmptyLayerWithoutIndexData() {
        var layer = new MinecraftCompiledGeometryStore.LayerData(
                new byte[0], new byte[0], "format", "QUADS", null, 0, 0);
        assertEquals(0L, layer.byteSize());
        assertEquals(0, layer.vertexBytes().length);
        assertEquals(0, layer.indexBytes().length);
    }
}
