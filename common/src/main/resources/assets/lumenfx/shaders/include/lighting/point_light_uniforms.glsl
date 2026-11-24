layout(std140) uniform PointLightComponent {
    mat4 PointViewToSourceWorld;
    vec4 PointTranslatedPosition;
    vec4 PointDirectionRadius;
    vec4 PointTangentLength;
    vec4 PointAreaAndIndex;
    vec4 PointContactShadow;
    ivec4 PointLightingChannels;
    vec4 PointCone;
    vec4 PointRectShape;
    vec4 PointRectSourceUv;
};
