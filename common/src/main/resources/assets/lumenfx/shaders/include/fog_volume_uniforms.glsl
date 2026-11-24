layout(std140) uniform FogVolume {
    mat4 FogWorldToClip;
    mat4 FogClipToWorld;
    mat4 FogPreviousWorldToClip;
    vec4 FogGrid;
    vec4 FogAtlas;
    vec4 FogDepth;
    vec4 FogFrame;
    vec4 FogProjection;
    vec4 FogVolumeRange;
    vec4 FogEmissive;
    vec4 FogPreviousGrid;
    vec4 FogPreviousAtlas;
    vec4 FogUvBounds;
    vec4 FogHistoryBounds;
    vec4 FogViewForward;
    vec4 FogMaterial;
    vec4 FogAlbedoPhase;
};
