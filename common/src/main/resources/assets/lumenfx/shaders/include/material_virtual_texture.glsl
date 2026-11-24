#moj_import <lumenfx:srgb.glsl>

struct LfxVirtualPage {
    vec2 pages;
    vec2 dx;
    vec2 dy;
    ivec2 address;
    int mip;
    int requestedMip;
    int localMip;
    uvec2 requestPage;
    bool fine;
};

float lfx_vt_address(float value, int mode) {
    if (mode == 1) return fract(value);
    if (mode == 2) return 1.0 - abs(fract(value * 0.5) * 2.0 - 1.0);
    return clamp(value, 0.0, 65535.0 / 65536.0);
}

// Explicit level and derivative-based mip modes follow the source page table lookup.
LfxVirtualPage lfx_vt_page(uvec4 table0, uvec4 table1, vec2 uv, vec2 dx, vec2 dy, ivec2 mode,
                          bool explicitLevel, float level, float viewBias, float noise, bool adaptive) {
    vec4 parameters = uintBitsToFloat(table0);
    vec2 scale = parameters.xy;
    vec2 size = parameters.zw;
    dx *= scale * size;
    dy *= scale * size;
    float largest = max(dot(dx, dx), dot(dy, dy));
    float smallest = min(dot(dx, dx), dot(dy, dy));
    float maxAniso = uintBitsToFloat(table1.x);
    float mipFromGradient = largest > 0.0
        ? max(0.5 * log2(max(smallest, 1e-38)), 0.5 * log2(largest) - maxAniso)
        : -126.0;
    int localMip = explicitLevel ? int(floor(level + viewBias))
        : int(floor(mipFromGradient + level + viewBias + noise)) + int((table1.y >> 24u) & 15u);
    int adaptiveBias = adaptive ? int((table1.z >> 4u) & 15u) : 0;
    if (explicitLevel) localMip -= adaptiveBias;
    uv *= scale;
    uv = vec2(lfx_vt_address(uv.x, mode.x), lfx_vt_address(uv.y, mode.y));
    vec2 pages = uv * size;
    ivec2 offset = ivec2(int(table1.y & 4095u), int((table1.y >> 12u) & 4095u));
    int mip = clamp(localMip, 0, int(table1.z & 15u));
    return LfxVirtualPage(pages, dx, dy, (ivec2(pages) + offset) >> mip, mip, max(0, localMip + adaptiveBias),
        localMip, uvec2(pages * exp2(float(adaptiveBias))), false);
}

LfxVirtualPage lfx_vt_adaptive(LfxVirtualPage page, usampler2D indirection) {
    if (page.localMip >= 0) return page;
    ivec2 cell = ivec2(page.pages);
    if (any(greaterThanEqual(cell, textureSize(indirection, 0)))) return page;
    uint packedValue = texelFetch(indirection, cell, 0).r;
    if (packedValue == 0u) return page;
    int level = int((packedValue >> 24u) & 15u);
    float side = exp2(float(level));
    ivec2 offset = ivec2(packedValue & 4095u, (packedValue >> 12u) & 4095u);
    page.pages = fract(page.pages) * side;
    page.dx *= side;
    page.dy *= side;
    page.mip = clamp(page.localMip + level, 0, level);
    page.address = (ivec2(page.pages) + offset) >> page.mip;
    page.fine = true;
    return page;
}

vec4 lfx_vt_fallback(uint packedColor) {
    return vec4(uvec4(packedColor >> 16u, packedColor >> 8u, packedColor, packedColor >> 24u) & 255u) / 255.0;
}

vec4 lfx_vt_physical(usampler2D table, sampler2D physical, uvec4 table1, uvec4 layer,
                     LfxVirtualPage page, int channel, bool explicitLevel, bool anisotropic) {
    ivec2 tableSize = textureSize(table, page.mip);
    uint packedValue = any(lessThan(page.address, ivec2(0))) || any(greaterThanEqual(page.address, tableSize))
        ? 0u : texelFetch(table, page.address, page.mip)[channel];
    if ((packedValue >> 4u) == 0u) return lfx_vt_fallback(layer.x);
    int mip = int(packedValue & 15u) - int((table1.z >> 9u) & 15u);
    float mipScale = exp2(-float(mip));
    bool wide = (table1.z & 256u) != 0u;
    uint mask = wide ? 255u : 63u;
    vec2 slot = vec2((packedValue >> 4u) & mask, (packedValue >> (wide ? 12u : 10u)) & mask);
    vec3 physicalScale = uintBitsToFloat(layer.yzw);
    vec2 uv = slot * physicalScale.z + fract(page.pages * mipScale) * physicalScale.x + physicalScale.y;
    float gradients = mipScale * physicalScale.x;
    return explicitLevel || !anisotropic ? textureLod(physical, uv, 0.0) : textureGrad(physical, uv, page.dx * gradients, page.dy * gradients);
}

vec4 lfx_vt_physical(usampler2D table, sampler2D physical, uvec4 table1, uvec4 layer,
                     LfxVirtualPage page, int channel, bool explicitLevel) {
    return lfx_vt_physical(table, physical, table1, layer, page, channel, explicitLevel, true);
}

vec4 lfx_vt_adaptive_physical(usampler2D coarse, usampler2D fine, sampler2D physical, uvec4 table1, uvec4 layer,
                              LfxVirtualPage page, bool explicitLevel, bool anisotropic) {
    if (page.fine) return lfx_vt_physical(fine, physical, table1, layer, page, 0, explicitLevel, anisotropic);
    return lfx_vt_physical(coarse, physical, table1, layer, page, 0, explicitLevel, anisotropic);
}

vec4 lfx_vt_adaptive_physical(usampler2D coarse, usampler2D fine, sampler2D physical, uvec4 table1, uvec4 layer,
                              LfxVirtualPage page, bool explicitLevel) {
    return lfx_vt_adaptive_physical(coarse, fine, physical, table1, layer, page, explicitLevel, true);
}

vec3 lfx_rvt_normal(vec2 packedValue, float signZ) {
    vec2 xy = packedValue * (255.0 / 127.0) - 1.0;
    return vec3(xy, sqrt(clamp(1.0 - dot(xy, xy), 0.0, 1.0)) * (signZ * 2.0 - 1.0));
}

vec3 lfx_rvt_ycocg_to_rgb(vec4 packedValue) {
    vec2 chroma = (packedValue.rg - 128.0 / 255.0) / ((255.0 / 8.0) * packedValue.b + 1.0);
    return packedValue.a + vec3(chroma.x - chroma.y, chroma.y, -chroma.x - chroma.y);
}
