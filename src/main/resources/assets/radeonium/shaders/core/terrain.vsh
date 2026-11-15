#version 450

// Radeonium terrain vertex contract.
// Matches the 28-byte BLOCK layout written by MinecraftMeshGpuUpload:
// float3 position, normalized RGBA8 color, float2 atlas UV, signed short2 light.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;

layout(location = 0) out vec2 TexCoord;
layout(location = 1) out vec4 VertexColor;
layout(location = 2) out vec2 LightCoord;
layout(location = 3) out float FogDistance;

uniform mat4 ModelViewProjection;
uniform mat4 ModelView;

void main() {
    vec4 viewPosition = ModelView * vec4(Position, 1.0);
    gl_Position = ModelViewProjection * vec4(Position, 1.0);
    TexCoord = UV0;
    LightCoord = clamp(vec2(UV2) / 256.0, vec2(0.0), vec2(1.0));
    VertexColor = Color;
    FogDistance = length(viewPosition.xyz);
}
