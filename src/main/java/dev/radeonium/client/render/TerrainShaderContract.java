package dev.radeonium.client.render;

import java.util.List;

/**
 * Stable description of Radeonium's terrain shader inputs and resources.
 *
 * The active SOLID and CUTOUT RenderPearl pipelines select these shader
 * resources. This class documents their shared Minecraft 26.3 interface.
 */
public final class TerrainShaderContract {
    public static final String VERTEX_SHADER =
            "assets/radeonium/shaders/core/terrain.vsh";
    public static final String FRAGMENT_SHADER =
            "assets/radeonium/shaders/core/terrain.fsh";
    public static final String VERTEX_SHADER_ID = "radeonium:core/terrain";
    public static final String FRAGMENT_SHADER_ID = "radeonium:core/terrain";

    public static final int POSITION_LOCATION = 0;
    public static final int COLOR_LOCATION = 1;
    public static final int UV_LOCATION = 2;
    public static final int LIGHT_LOCATION = 3;

    public static final int POSITION_OFFSET_BYTES = MinecraftMeshGpuUpload.POSITION_OFFSET_BYTES;
    public static final int COLOR_OFFSET_BYTES = MinecraftMeshGpuUpload.COLOR_OFFSET_BYTES;
    public static final int UV_OFFSET_BYTES = MinecraftMeshGpuUpload.UV_OFFSET_BYTES;
    public static final int LIGHT_OFFSET_BYTES = MinecraftMeshGpuUpload.LIGHT_OFFSET_BYTES;
    public static final int VERTEX_STRIDE_BYTES = MinecraftMeshGpuUpload.VERTEX_STRIDE_BYTES;

    public static final List<String> VERTEX_UNIFORMS =
            List.of("ModelViewMat", "ProjMat", "CameraBlockPos", "CameraOffset", "Sampler2");
    public static final List<String> FRAGMENT_UNIFORMS =
            List.of("Sampler0", "FogColor", "FogEnvironmentalStart", "FogEnvironmentalEnd",
                    "FogRenderDistanceStart", "FogRenderDistanceEnd", "TextureSize", "UseRgss",
                    "ChunkVisibility");

    private TerrainShaderContract() {}

    public static boolean hasValidVertexLayout() {
        return POSITION_OFFSET_BYTES == 0
                && COLOR_OFFSET_BYTES == 12
                && UV_OFFSET_BYTES == 16
                && LIGHT_OFFSET_BYTES == 24
                && VERTEX_STRIDE_BYTES == 28;
    }
}
