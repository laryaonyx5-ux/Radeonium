#version 330
#extension GL_ARB_separate_shader_objects : require

// Radeonium terrain vertex stage. It uses the active Minecraft 26.3 terrain
// bind groups and BLOCK vertex attributes while keeping the shader source
// independently owned by Radeonium.

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:chunksection.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler2;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;

void main() {
    vec3 sectionRelativePosition = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(sectionRelativePosition, 1.0);

    sphericalVertexDistance = fog_spherical_distance(sectionRelativePosition);
    cylindricalVertexDistance = fog_cylindrical_distance(sectionRelativePosition);

#ifndef OIT_ALPHA_ONLY
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif

    texCoord0 = UV0;
}
