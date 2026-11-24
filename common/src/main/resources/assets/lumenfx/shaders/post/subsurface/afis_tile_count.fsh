#version 330
#moj_import <lumenfx:lighting/subsurface_profile.glsl>
uniform sampler2D LfxSubsurfaceSelection;
out float tileCount;

void main() {
    ivec2 begin = ivec2(gl_FragCoord.xy) * 8;
    ivec2 end = min(begin + 8, textureSize(LfxSubsurfaceSelection, 0));
    for (int y = begin.y; y < end.y; ++y) for (int x = begin.x; x < end.x; ++x) {
        int profile = int(texelFetch(LfxSubsurfaceSelection, ivec2(x, y), 0).z) - 1;
        if (profile == LFX_PER_PIXEL_DIFFUSION || (profile >= 0 && lfx_subsurface_profile_load(profile, 4).a < 0.01)) {
            tileCount = 1.0;
            return;
        }
    }
    tileCount = 0.0;
}
