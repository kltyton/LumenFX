layout(std140) uniform LfxBurleyMipSettings {
    ivec4 BurleyMipSettings;
};
uniform sampler2D AfisTileCount;

bool lfx_burley_use_mips() {
    return texelFetch(AfisTileCount, ivec2(0), 0).r >= float(BurleyMipSettings.x);
}
