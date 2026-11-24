#version 400

void main() {
    vec2 vertex = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(vertex * 2.0 - 1.0, 0.0, 1.0);
}
