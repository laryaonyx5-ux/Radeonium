package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TerrainShaderContractTest {
    @Test
    void shaderVertexLayoutMatchesTheUploadedBlockVertexLayout() {
        assertTrue(TerrainShaderContract.hasValidVertexLayout());
        assertEquals(0, TerrainShaderContract.POSITION_LOCATION);
        assertEquals(1, TerrainShaderContract.COLOR_LOCATION);
        assertEquals(2, TerrainShaderContract.UV_LOCATION);
        assertEquals(3, TerrainShaderContract.LIGHT_LOCATION);
        assertEquals(28, TerrainShaderContract.VERTEX_STRIDE_BYTES);
    }

    @Test
    void shaderResourcesMatchTheActiveMinecraftTerrainInterface() throws IOException {
        String vertex = read(TerrainShaderContract.VERTEX_SHADER);
        String fragment = read(TerrainShaderContract.FRAGMENT_SHADER);
        assertAll(
                () -> assertTrue(vertex.contains("#include <minecraft:globals.glsl>")),
                () -> assertTrue(vertex.contains("#include <minecraft:chunksection.glsl>")),
                () -> assertTrue(vertex.contains("#include <minecraft:projection.glsl>")),
                () -> assertTrue(vertex.contains("#include <minecraft:sample_lightmap.glsl>")),
                () -> assertTrue(vertex.contains("layout(location = 0) in vec3 Position")),
                () -> assertTrue(vertex.contains("layout(location = 1) in vec4 Color")),
                () -> assertTrue(vertex.contains("layout(location = 2) in vec2 UV0")),
                () -> assertTrue(vertex.contains("layout(location = 3) in ivec2 UV2")),
                () -> assertTrue(vertex.contains("uniform sampler2D Sampler2")),
                () -> assertTrue(vertex.contains("fog_spherical_distance")),
                () -> assertTrue(vertex.contains("sample_lightmap(Sampler2, UV2)")),
                () -> assertTrue(fragment.contains("#include <minecraft:fog.glsl>")),
                () -> assertTrue(fragment.contains("#include <minecraft:chunksection.glsl>")),
                () -> assertTrue(fragment.contains("uniform sampler2D Sampler0")),
                () -> assertTrue(fragment.contains("ChunkVisibility")),
                () -> assertTrue(fragment.contains("FogEnvironmentalStart")),
                () -> assertTrue(fragment.contains("FogRenderDistanceEnd")),
                () -> assertTrue(fragment.contains("apply_fog(")),
                () -> assertTrue(fragment.contains("ALPHA_CUTOUT"))
        );
    }

    @Test
    void shaderContractExposesThePipelineShaderIdentifiersAndBoundUniforms() {
        assertEquals("radeonium:core/terrain", TerrainShaderContract.VERTEX_SHADER_ID);
        assertEquals("radeonium:core/terrain", TerrainShaderContract.FRAGMENT_SHADER_ID);
        assertTrue(TerrainShaderContract.VERTEX_UNIFORMS.contains("ModelViewMat"));
        assertTrue(TerrainShaderContract.VERTEX_UNIFORMS.contains("ProjMat"));
        assertTrue(TerrainShaderContract.FRAGMENT_UNIFORMS.contains("Sampler0"));
        assertTrue(TerrainShaderContract.FRAGMENT_UNIFORMS.contains("FogColor"));
        assertTrue(TerrainShaderContract.FRAGMENT_UNIFORMS.contains("ChunkVisibility"));
    }

    private static String read(String path) throws IOException {
        try (InputStream stream = TerrainShaderContractTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, "Missing shader resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
