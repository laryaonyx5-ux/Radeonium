#version 330
#extension GL_ARB_separate_shader_objects : require

// Radeonium terrain fragment stage. Texture atlas, fog and section fade are
// supplied by Minecraft's existing terrain bind groups; the shading logic is
// compiled from Radeonium's own resource.

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:chunksection.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 surfaceColor = texture(Sampler0, texCoord0) * vertexColor;

    // Preserve Minecraft's section fade behavior rather than exposing
    // partially compiled or newly visible sections at full opacity.
    surfaceColor = mix(FogColor * vec4(1.0, 1.0, 1.0, surfaceColor.a),
                       surfaceColor, ChunkVisibility);

#ifdef ALPHA_CUTOUT
    if (surfaceColor.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    fragColor = apply_fog(
            surfaceColor,
            sphericalVertexDistance,
            cylindricalVertexDistance,
            FogEnvironmentalStart,
            FogEnvironmentalEnd,
            FogRenderDistanceStart,
            FogRenderDistanceEnd,
            FogColor
    );
}
