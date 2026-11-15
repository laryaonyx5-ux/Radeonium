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
    void shaderResourcesDeclareTheRequiredTerrainInputsAndOutputs() throws IOException {
        String vertex = read(TerrainShaderContract.VERTEX_SHADER);
        String fragment = read(TerrainShaderContract.FRAGMENT_SHADER);
        assertAll(
                () -> assertTrue(vertex.contains("in vec3 Position")),
                () -> assertTrue(vertex.contains("in vec4 Color")),
                () -> assertTrue(vertex.contains("in vec2 UV0")),
                () -> assertTrue(vertex.contains("in ivec2 UV2")),
                () -> assertTrue(vertex.contains("uniform mat4 ModelViewProjection")),
                () -> assertTrue(vertex.contains("uniform mat4 ModelView")),
                () -> assertTrue(fragment.contains("uniform sampler2D BlockAtlas")),
                () -> assertTrue(fragment.contains("uniform sampler2D LightMap")),
                () -> assertTrue(fragment.contains("uniform vec4 FogColor")),
                () -> assertTrue(fragment.contains("uniform float AlphaCutoff")),
                () -> assertTrue(fragment.contains("discard")),
                () -> assertTrue(fragment.contains("VertexColor"))
        );
    }

    @Test
    void shaderContractListsAllPipelineUniforms() {
        assertTrue(TerrainShaderContract.VERTEX_UNIFORMS.contains("ModelViewProjection"));
        assertTrue(TerrainShaderContract.VERTEX_UNIFORMS.contains("ModelView"));
        assertTrue(TerrainShaderContract.FRAGMENT_UNIFORMS.contains("BlockAtlas"));
        assertTrue(TerrainShaderContract.FRAGMENT_UNIFORMS.contains("LightMap"));
        assertTrue(TerrainShaderContract.FRAGMENT_UNIFORMS.contains("FogColor"));
        assertTrue(TerrainShaderContract.FRAGMENT_UNIFORMS.contains("AlphaCutoff"));
    }

    private static String read(String path) throws IOException {
        try (InputStream stream = TerrainShaderContractTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, "Missing shader resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
