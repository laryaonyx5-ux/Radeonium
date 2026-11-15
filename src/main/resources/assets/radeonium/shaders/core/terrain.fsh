#version 450

layout(location = 0) in vec2 TexCoord;
layout(location = 1) in vec4 VertexColor;
layout(location = 2) in vec2 LightCoord;
layout(location = 3) in float FogDistance;

layout(location = 0) out vec4 FragColor;

uniform sampler2D BlockAtlas;
uniform sampler2D LightMap;
uniform vec4 FogColor;
uniform float FogStart;
uniform float FogEnd;
uniform float AlphaCutoff;
uniform bool UseLightmap;

void main() {
    vec4 texel = texture(BlockAtlas, TexCoord);
    vec4 surface = texel * VertexColor;
    if (AlphaCutoff > 0.0 && surface.a < AlphaCutoff) discard;

    vec3 illumination = UseLightmap
        ? texture(LightMap, LightCoord).rgb
        : vec3(1.0);
    vec3 litColor = surface.rgb * illumination;

    float fogRange = max(FogEnd - FogStart, 0.0001);
    float fogFactor = clamp((FogEnd - FogDistance) / fogRange, 0.0, 1.0);
    FragColor = vec4(mix(FogColor.rgb, litColor, fogFactor), surface.a);
}
