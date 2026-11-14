#version 450

layout(location = 0) in vec2 TexCoord;
layout(location = 0) out vec4 FragColor;

uniform sampler2D BlockAtlas;
uniform vec4 MaterialTint;

void main() {
    FragColor = texture(BlockAtlas, TexCoord) * MaterialTint;
}
