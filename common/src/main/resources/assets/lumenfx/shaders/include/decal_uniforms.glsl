layout(std140) uniform DecalPrimitive {
    mat4 DecalPixelToView;
    mat4 DecalPixelToWorld;
    mat4 DecalPixelToLocal;
    mat4 DecalLocalToWorld;
    mat4 DecalLocalToClip;
    vec4 DecalViewport;
    vec4 DecalColorValue;
    vec4 DecalFadeParameters;
    vec4 DecalInverseScale;
    vec4 DecalOrientationValue;
    vec4 DecalObjectTile;
    vec4 DecalObjectOffset;
};
