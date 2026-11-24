#version 330
#moj_import <lumenfx:srgb.glsl>
#moj_import <lumenfx:decal_view.glsl>
#moj_import <lumenfx:scene_surface.glsl>
#if LFX_GBUFFER_PARTICLE_BASIS
#moj_import <lumenfx:substrate/slab_basis.glsl>
uniform sampler2D ParticleTangent;
layout(location = 3) out vec4 GBufferF;
#endif

uniform sampler2D SurfaceAlbedo;
uniform sampler2D SurfaceNormalAmbient;
uniform sampler2D SurfaceGeometry;
#if LFX_GBUFFER_PARTICLES
uniform sampler2D ParticleSourceMaterial;
uniform sampler2D ParticleF0Roughness;
#endif

layout(location = 0) out vec4 GBufferA;
layout(location = 1) out vec4 GBufferB;
layout(location = 2) out vec4 GBufferC;

vec4 lfx_gbuffer_legacy_unorm(vec4 value) {
#if LFX_GBUFFER_FLOAT
    return round(clamp(value, 0.0, 1.0) * 255.0) / 255.0;
#else
    return value;
#endif
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 geometry = texelFetch(SurfaceGeometry, pixel, 0);
    int flags = int(geometry.z);
    GBufferA = vec4(0.0);
    GBufferB = vec4(0.0);
    GBufferC = vec4(0.0);
#if LFX_GBUFFER_PARTICLE_BASIS
    GBufferF = vec4(0.5);
#endif
    bool particleLit = lfx_surface_has_default_lit_particle(geometry);
    if (!particleLit && ((flags & 128) == 0 || (flags & (256 | LFX_UNLIT_PARTICLE_FLAG)) != 0)) return;

    vec4 surface = texelFetch(SurfaceNormalAmbient, pixel, 0);
    vec3 normal = (mat3(DecalViewToWorldNormal) * surface.xyz).xzy;
#if LFX_GBUFFER_PARTICLE_BASIS
    if (particleLit && lfx_surface_has_anisotropic_particle(geometry)) {
        vec4 tangent = texelFetch(ParticleTangent, pixel, 0);
        if (lfx_surface_particle_shading_model(geometry) == LFX_SHADING_MODEL_SUBSTRATE) {
            vec3 worldTangent;
            lfx_substrate_unpack_normal_tangent(tangent, normal, worldTangent);
            GBufferF = vec4(worldTangent * 0.5 + 0.5, tangent.z);
        } else GBufferF = tangent;
    }
#endif
    float squaredLength = dot(normal, normal);
    if (squaredLength > 0.0) GBufferA.rgb = normal * (0.5 * inversesqrt(squaredLength)) + 0.5;
#if LFX_GBUFFER_PARTICLES
    if (particleLit) {
        vec4 source = texelFetch(ParticleSourceMaterial, pixel, 0);
        int packedValue = int(source.a);
        vec4 f0Roughness = texelFetch(ParticleF0Roughness, pixel, 0);
        int model = max(1, lfx_surface_particle_shading_model(geometry));
        bool firstPerson = model >= LFX_SHADING_MODEL_SUBSTRATE ? ((packedValue >> 16) & 1) != 0
                : (flags & LFX_PARTICLE_FIRST_PERSON_FLAG) != 0;
        int shadingFlags = model | (firstPerson ? 64 : 0);
        if (lfx_surface_has_anisotropic_particle(geometry)) shadingFlags |= 16;
        if (model == LFX_SHADING_MODEL_SUBSTRATE) {
            vec3 f0 = f0Roughness.rgb;
            float peak = max(f0.r, max(f0.g, f0.b));
            float metallic = clamp((peak - 0.08) / (0.4 - 0.08), 0.0, 1.0);
            float specular = clamp(peak / 0.08, 0.0, 1.0);
            vec3 diffuse = srgbToLinear(texelFetch(SurfaceAlbedo, pixel, 0).rgb);
            GBufferB = vec4(metallic, specular, f0Roughness.a, float(shadingFlags) / 255.0);
            GBufferC = vec4(mix(diffuse, f0, metallic), surface.w);
        } else if (model == LFX_SHADING_MODEL_SUBSTRATE_HAIR || model == LFX_SHADING_MODEL_SUBSTRATE_EYE) {
            vec2 scatterSpecular = model == LFX_SHADING_MODEL_SUBSTRATE_EYE ? vec2(0.0, f0Roughness.r / 0.08)
                    : vec2(float(packedValue & 255), float((packedValue >> 8) & 255)) / 255.0;
            GBufferB = vec4(scatterSpecular, f0Roughness.a, float(shadingFlags) / 255.0);
            GBufferC = vec4(source.rgb, surface.w);
        } else if (model == LFX_SHADING_MODEL_SUBSTRATE_TOON) {
            vec2 metalSpecular = vec2(float(packedValue & 255), float((packedValue >> 8) & 255)) / 255.0;
            GBufferB = vec4(metalSpecular, f0Roughness.a, float(shadingFlags) / 255.0);
            GBufferC = vec4(source.rgb, surface.w);
        } else {
            GBufferA = lfx_gbuffer_legacy_unorm(GBufferA);
            vec2 metalSpecular = model == LFX_SHADING_MODEL_EYE ? vec2(0.0, f0Roughness.r / 0.08)
                    : vec2(float(packedValue & 255), float((packedValue >> 8) & 255)) / 255.0;
            GBufferB = lfx_gbuffer_legacy_unorm(vec4(metalSpecular, f0Roughness.a, float(shadingFlags) / 255.0));
            float genericAo = LFX_GBUFFER_STATIC_LIGHTING != 0 ? float((packedValue >> 16) & 255) / 255.0 : surface.w;
            GBufferC = lfx_gbuffer_legacy_unorm(vec4(source.rgb, genericAo));
        }
        return;
    }
#endif
    GBufferA = lfx_gbuffer_legacy_unorm(GBufferA);
    GBufferB = vec4(0.0, 0.0, 1.0, 1.0 / 255.0);
    GBufferC = lfx_gbuffer_legacy_unorm(vec4(srgbToLinear(texelFetch(SurfaceAlbedo, pixel, 0).rgb), surface.w));
}
