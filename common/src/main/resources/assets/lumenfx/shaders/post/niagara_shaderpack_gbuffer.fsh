#version 330
#moj_import <lumenfx:srgb.glsl>
#moj_import <lumenfx:decal_view.glsl>
#moj_import <lumenfx:scene_surface.glsl>

#ifndef LFX_HOST_ENCODING
#define LFX_HOST_ENCODING 0
#endif
#ifndef LFX_HOST_SPECULAR_ENCODING
#define LFX_HOST_SPECULAR_ENCODING 0
#endif
#ifndef LFX_GBUFFER_PARTICLES
#define LFX_GBUFFER_PARTICLES 0
#endif
#ifndef LFX_GBUFFER_PHOTON_MASK
#define LFX_GBUFFER_PHOTON_MASK 0
#endif
#ifndef LFX_GBUFFER_PARTICLE_BASIS
#define LFX_GBUFFER_PARTICLE_BASIS 0
#endif
#ifndef LFX_GBUFFER_FLOAT
#define LFX_GBUFFER_FLOAT 0
#endif
#ifndef LFX_GBUFFER_STATIC_LIGHTING
#define LFX_GBUFFER_STATIC_LIGHTING 0
#endif

#if LFX_GBUFFER_PARTICLE_BASIS
#moj_import <lumenfx:substrate/slab_basis.glsl>
uniform sampler2D ParticleTangent;
layout(location = 3) out vec4 GBufferF;
#endif

uniform sampler2D SurfaceBaseDepth;
uniform sampler2D SurfaceGeometry;
uniform sampler2D SurfaceMaterial;

#if LFX_GBUFFER_PARTICLES
uniform sampler2D ParticleAlbedo;
uniform sampler2D ParticleNormalAmbient;
uniform sampler2D ParticleGeometry;
uniform sampler2D ParticleSourceMaterial;
uniform sampler2D ParticleF0Roughness;
#endif
#if LFX_GBUFFER_PHOTON_MASK
uniform sampler2D PhotonOpaqueMask;
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

vec3 lfx_decode_octahedral(vec2 p) {
    vec3 n = vec3(p.xy, 1.0 - abs(p.x) - abs(p.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx)) * mix(vec2(-1.0), vec2(1.0), step(vec2(0.0), n.xy));
    }
    float lenSq = dot(n, n);
    return lenSq > 1e-12 ? n * inversesqrt(lenSq) : vec3(0.0, 0.0, 1.0);
}

vec3 lfx_decode_host_color(vec3 color) {
#if LFX_HOST_ENCODING == 1
    return srgbToLinear(max(color, vec3(0.0)));
#elif LFX_HOST_ENCODING == 2
    return pow(max(color, vec3(0.0)), vec3(2.2));
#else
    return max(color, vec3(0.0));
#endif
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    GBufferA = vec4(0.0);
    GBufferB = vec4(0.0);
    GBufferC = vec4(0.0);
#if LFX_GBUFFER_PARTICLE_BASIS
    GBufferF = vec4(0.5);
#endif

#if LFX_GBUFFER_PARTICLES
    vec4 particleGeometry = texelFetch(ParticleGeometry, pixel, 0);
    int particleFlags = int(particleGeometry.z);
    bool particleLit = lfx_surface_has_default_lit_particle(particleGeometry);
    if (particleLit) {
        vec4 surface = texelFetch(ParticleNormalAmbient, pixel, 0);
        vec3 normal = (mat3(DecalViewToWorldNormal) * surface.xyz).xzy;
#if LFX_GBUFFER_PARTICLE_BASIS
        if (lfx_surface_has_anisotropic_particle(particleGeometry)) {
            vec4 tangent = texelFetch(ParticleTangent, pixel, 0);
            if (lfx_surface_particle_shading_model(particleGeometry) == LFX_SHADING_MODEL_SUBSTRATE) {
                vec3 worldTangent;
                lfx_substrate_unpack_normal_tangent(tangent, normal, worldTangent);
                GBufferF = vec4(worldTangent * 0.5 + 0.5, tangent.z);
            } else {
                GBufferF = tangent;
            }
        }
#endif
        float squaredLength = dot(normal, normal);
        if (squaredLength > 0.0) GBufferA.rgb = normal * (0.5 * inversesqrt(squaredLength)) + 0.5;
        vec4 source = texelFetch(ParticleSourceMaterial, pixel, 0);
        int packedValue = int(source.a);
        vec4 f0Roughness = texelFetch(ParticleF0Roughness, pixel, 0);
        int model = max(1, lfx_surface_particle_shading_model(particleGeometry));
        bool firstPerson = model >= LFX_SHADING_MODEL_SUBSTRATE ? ((packedValue >> 16) & 1) != 0
                : (particleFlags & LFX_PARTICLE_FIRST_PERSON_FLAG) != 0;
        int shadingFlags = model | (firstPerson ? 64 : 0);
        if (lfx_surface_has_anisotropic_particle(particleGeometry)) shadingFlags |= 16;
        if (model == LFX_SHADING_MODEL_SUBSTRATE) {
            vec3 f0 = f0Roughness.rgb;
            float peak = max(f0.r, max(f0.g, f0.b));
            float metallic = clamp((peak - 0.08) / (0.4 - 0.08), 0.0, 1.0);
            float specular = clamp(peak / 0.08, 0.0, 1.0);
            vec3 diffuse = srgbToLinear(texelFetch(ParticleAlbedo, pixel, 0).rgb);
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
    if ((particleFlags & LFX_UNLIT_PARTICLE_FLAG) != 0) {
        return;
    }
#endif

#if LFX_GBUFFER_PHOTON_MASK
    if (texelFetch(PhotonOpaqueMask, pixel, 0).r > 0.5) {
        return;
    }
#endif

    vec4 baseDepth = texelFetch(SurfaceBaseDepth, pixel, 0);
    if (baseDepth.a < 0.0) return;

    vec4 geometry = texelFetch(SurfaceGeometry, pixel, 0);
    vec3 viewNormal = lfx_decode_octahedral(geometry.ba);
    vec3 worldNormal = (mat3(DecalViewToWorldNormal) * viewNormal).xzy;
    float normalLenSq = dot(worldNormal, worldNormal);
    if (normalLenSq > 0.0) {
        GBufferA.rgb = worldNormal * (0.5 * inversesqrt(normalLenSq)) + 0.5;
    }

    vec4 material = texelFetch(SurfaceMaterial, pixel, 0);
    float roughness;
    float metallic;
    float specular;
    if (material.g < 0.0 || LFX_HOST_SPECULAR_ENCODING == 0) {
        roughness = clamp(1.0 - clamp(material.r, 0.0, 1.0), 0.0, 1.0);
        metallic = 0.0;
        specular = 0.5;
    } else if (LFX_HOST_SPECULAR_ENCODING == 2) {
        float smoothness = clamp(material.r, 0.0, 1.0);
        roughness = (1.0 - smoothness) * (1.0 - smoothness);
        float f0Byte = material.g * 255.0;
        metallic = f0Byte >= 229.5 ? 1.0 : 0.0;
        specular = f0Byte < 229.5 ? clamp(material.g / 0.08, 0.0, 1.0) : 0.5;
    } else {
        roughness = clamp(1.0 - clamp(material.r, 0.0, 1.0), 0.0, 1.0);
        metallic = clamp(material.g, 0.0, 1.0);
        specular = 0.5;
    }

    GBufferA = lfx_gbuffer_legacy_unorm(GBufferA);
    GBufferB = lfx_gbuffer_legacy_unorm(vec4(metallic, specular, roughness, 1.0 / 255.0));
    GBufferC = lfx_gbuffer_legacy_unorm(vec4(lfx_decode_host_color(baseDepth.rgb), 1.0));
}
