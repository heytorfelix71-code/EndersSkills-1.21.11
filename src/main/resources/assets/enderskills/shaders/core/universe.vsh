#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;

out vec3 position;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    // camera-relative and world-aligned: the same ray the original shader got after undoing the view rotation
    position = Position;
    vertexColor = Color;
}
