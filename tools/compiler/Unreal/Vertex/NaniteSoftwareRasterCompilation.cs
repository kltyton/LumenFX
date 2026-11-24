using System.Text;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private string CompileNaniteSoftwareRaster(string fragmentSource)
    {
        int versionEnd = fragmentSource.IndexOf('\n');
        if (versionEnd < 0) throw new ConversionException("Nanite material has no shader version");
        var shader = new StringBuilder("""
            #version 450
            #define LFX_NANITE_MATERIAL 1
            #define LFX_NANITE_RASTER_MATERIAL 1
            #define LFX_NANITE_SOFTWARE_RASTER 1
            #define LFX_PARTICLE_ANISOTROPY_TARGET -1
            #define LFX_PARTICLE_SURFACE_TARGET -1
            #define LFX_PARTICLE_CUSTOM_DATA_TARGET -1
            vec4 lfxNaniteSoftwareFragCoord;
            float lfxNaniteSoftwareDepth;
            // Native Nanite visibility compute supplies analytic gradients separately.
            float lfx_nanite_finite_derivative(float value) { return 0.0; }
            vec2 lfx_nanite_finite_derivative(vec2 value) { return vec2(0.0); }
            vec3 lfx_nanite_finite_derivative(vec3 value) { return vec3(0.0); }
            vec4 lfx_nanite_finite_derivative(vec4 value) { return vec4(0.0); }
            #define dFdx(value) lfx_nanite_finite_derivative(value)
            #define dFdy(value) lfx_nanite_finite_derivative(value)
            #define fwidth(value) lfx_nanite_finite_derivative(value)
            """);
        shader.AppendLine();
        if (macroUVUsed) shader.AppendLine("#define LFX_MACRO_UV 1");
        if (depthProjectionUsed) shader.AppendLine("#define LFX_DEPTH_PROJECTION 1");
        shader.AppendLine($"#define LFX_CUSTOM_SCENE_TEXTURES {customSceneTextures}");
        shader.AppendLine($"#define LFX_DEFERRED_SCENE_TEXTURES {deferredSceneTextures}");
        shader.Append(fragmentSource[(versionEnd + 1)..]);
        shader.AppendLine("#moj_import <lumenfx:nanite/software_coverage.glsl>");
        return shader.ToString();
    }
}
