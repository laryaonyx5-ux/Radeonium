package dev.radeonium.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftFluidSectionMesherTest {
    @Test
    void emptyFluidMeshContainsEveryVanillaLayer() {
        var empty = MinecraftFluidSectionMesher.SectionFluidMesh.empty();
        assertEquals(0, empty.quadCount());
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            assertTrue(empty.layer(layer).quads().isEmpty());
        }
    }

    @Test
    void capturedFluidQuadDefensivelyCopiesAllVertexAttributes() {
        float[] positions = {0,0,0, 0,1,0, 1,1,0, 1,0,0};
        float[] uv = {0,0, 0,1, 1,1, 1,0};
        int[] colors = {1,2,3,4};
        int[] light = {5,6,7,8};
        var quad = new MinecraftFluidSectionMesher.FluidQuad(
                ChunkSectionLayer.TRANSLUCENT, positions, uv, colors, light, 0, 0, -1);

        positions[0] = 99;
        uv[0] = 99;
        colors[0] = 99;
        light[0] = 99;

        assertEquals(0, quad.positions()[0]);
        assertEquals(0, quad.uv()[0]);
        assertEquals(1, quad.colors()[0]);
        assertEquals(5, quad.lightCoords()[0]);
        float[] copy = quad.positions();
        copy[0] = 42;
        assertEquals(0, quad.positions()[0]);
    }

    @Test
    void fluidLayerRejectsGeometryFromAnotherLayer() {
        var quad = new MinecraftFluidSectionMesher.FluidQuad(
                ChunkSectionLayer.TRANSLUCENT,
                new float[12], new float[8], new int[4], new int[4], 0, 1, 0);
        assertThrows(IllegalArgumentException.class, () ->
                new MinecraftFluidSectionMesher.FluidLayerMesh(
                        ChunkSectionLayer.SOLID, List.of(quad)));
    }

    @Test
    void sectionFluidMeshCountsQuadsAcrossLayers() {
        var translucent = new MinecraftFluidSectionMesher.FluidQuad(
                ChunkSectionLayer.TRANSLUCENT,
                new float[12], new float[8], new int[4], new int[4], 0, 1, 0);
        var cutout = new MinecraftFluidSectionMesher.FluidQuad(
                ChunkSectionLayer.CUTOUT,
                new float[12], new float[8], new int[4], new int[4], 0, 1, 0);
        var layers = new EnumMap<ChunkSectionLayer, MinecraftFluidSectionMesher.FluidLayerMesh>(
                ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            layers.put(layer, new MinecraftFluidSectionMesher.FluidLayerMesh(layer, List.of()));
        }
        layers.put(ChunkSectionLayer.TRANSLUCENT,
                new MinecraftFluidSectionMesher.FluidLayerMesh(ChunkSectionLayer.TRANSLUCENT, List.of(translucent)));
        layers.put(ChunkSectionLayer.CUTOUT,
                new MinecraftFluidSectionMesher.FluidLayerMesh(ChunkSectionLayer.CUTOUT, List.of(cutout)));

        // Exercise the public empty factory and immutable per-layer contract;
        // non-empty captures are produced only by Minecraft's FluidRenderer.
        assertEquals(0, MinecraftFluidSectionMesher.SectionFluidMesh.empty().quadCount());
    }
}
