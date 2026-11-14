#version 450

// Radeonium 1.0 shader contract.
// Vertex buffer stride: 20 bytes (float3 position + uint2 packed UV).
// The packed UV words are the low/high halves written by MinecraftMeshGpuUpload.

layout(location = 0) in vec3 Position;
layout(location = 1) in uvec2 PackedUV;

layout(location = 0) out vec2 TexCoord;

uniform mat4 ModelViewProjection;

void main() {
    TexCoord = vec2(
        uintBitsToFloat(PackedUV.x),
        uintBitsToFloat(PackedUV.y)
    );
    gl_Position = ModelViewProjection * vec4(Position, 1.0);
}
