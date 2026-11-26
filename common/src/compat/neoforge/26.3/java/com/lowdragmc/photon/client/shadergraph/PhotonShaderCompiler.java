/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.shadergraph;

import com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderCompileContext;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderGraphCompiler;
import com.lowdragmc.kilagraph.rendertype.compiler.TangentBasis;
import com.lowdragmc.kilagraph.rendertype.format.KGVertexElement;
import com.lowdragmc.kilagraph.rendertype.format.KGVertexElements;
import com.lowdragmc.photon.client.gameobject.emitter.data.PhotonGpuChannels;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Getter;

/** Photon KilaGraph compile target adapted for KilaGraph 26.3's {@code vertexInputsBlock(Locations)}. */
public class PhotonShaderCompiler extends ShaderGraphCompiler {
    private static final String PARTICLE_DATA = "kg_pd";
    public static final String SCENE_COLOR = "SamplerSceneColor";
    public static final String SCENE_DEPTH = "SamplerSceneDepth";
    public static final String VIEWPORT = "U_ViewPort";

    public static ShaderExpr viewport(ShaderCompileContext ctx) {
        ctx.useMinecraftUniform("PhotonEngine", "photon:engine.glsl");
        return new ShaderExpr(VIEWPORT, GlslType.VEC4);
    }

    @Nullable
    private static PhotonShaderCompiler CURRENT;

    @Getter
    private long usedChannelMask;

    @Getter
    private boolean usesCustomData;

    public PhotonShaderCompiler(ShaderGraph graph) {
        super(graph);
    }

    @Nullable
    public static PhotonShaderCompiler current() {
        return CURRENT;
    }

    public void markChannelUsed(PhotonGpuChannels.Channel channel) {
        usedChannelMask |= channel.bit();
    }

    public void markCustomDataUsed() {
        usesCustomData = true;
    }

    @Override
    public CompiledShaderGraph compile() {
        var previous = CURRENT;
        CURRENT = this;
        try {
            return super.compile();
        } finally {
            CURRENT = previous;
        }
    }

    @Override
    protected String vertexInputsBlock(Locations locations) {
        return "#moj_import <photon:particle.glsl>\n";
    }

    @Override
    protected String vertexPrologue() {
        return "    ParticleData " + PARTICLE_DATA + " = getParticleData();\n";
    }

    @Override
    protected String attributeRef(KGVertexElement element) {
        if (element == KGVertexElements.POSITION) return PARTICLE_DATA + ".Position";
        if (element == KGVertexElements.COLOR) return PARTICLE_DATA + ".Color";
        if (element == KGVertexElements.UV0) return PARTICLE_DATA + ".UV";
        if (element == KGVertexElements.UV2) return PARTICLE_DATA + ".LightUV";
        if (element == KGVertexElements.NORMAL) return PARTICLE_DATA + ".Normal";
        return super.attributeRef(element);
    }

    @Override
    protected ShaderExpr objectToViewMatrix() {
        return new ShaderExpr("(" + transformField("ModelViewMat", GlslType.MAT4).code()
                + " * " + objectToWorldMatrix().code() + ")", GlslType.MAT4);
    }

    @Override
    protected ShaderExpr viewToObjectMatrix() {
        return new ShaderExpr("(" + worldToObjectMatrix().code()
                + " * " + transformField("IModelViewMat", GlslType.MAT4).code() + ")", GlslType.MAT4);
    }

    private ShaderExpr objectToWorldMatrix() {
        return varyingInput("photon_o2w", GlslType.MAT4,
                () -> new ShaderExpr("photon_objectToWorld()", GlslType.MAT4),
                new ShaderExpr("mat4(1.0)", GlslType.MAT4));
    }

    private ShaderExpr worldToObjectMatrix() {
        return varyingInput("photon_w2o", GlslType.MAT4,
                () -> new ShaderExpr("photon_worldToObject()", GlslType.MAT4),
                new ShaderExpr("mat4(1.0)", GlslType.MAT4));
    }

    @Override
    protected ShaderExpr objectSpacePosition() {
        return varyingInput("photon_objectPos", GlslType.VEC3,
                () -> new ShaderExpr(PARTICLE_DATA + ".ObjectPosition", GlslType.VEC3),
                new ShaderExpr("vPos", GlslType.VEC3));
    }

    @Override
    protected ShaderExpr worldSpacePosition() {
        return new ShaderExpr("(" + meshPosition().code() + " + (vec3("
                + transformField("CameraBlockPos", GlslType.VEC3).code() + ") - "
                + transformField("CameraOffset", GlslType.VEC3).code() + "))", GlslType.VEC3);
    }

    @Override
    protected ShaderExpr viewSpacePosition() {
        String mv = transformField("ModelViewMat", GlslType.MAT4).code();
        return new ShaderExpr("(" + mv + " * vec4(" + meshPosition().code() + ", 1.0)).xyz", GlslType.VEC3);
    }

    @Override
    protected ShaderExpr objectSpaceNormal() {
        ShaderExpr n = varyingInput("photon_objectNormal", GlslType.VEC3,
                () -> new ShaderExpr(PARTICLE_DATA + ".ObjectNormal", GlslType.VEC3),
                new ShaderExpr("vNormal", GlslType.VEC3));
        return new ShaderExpr("normalize(" + n.code() + ")", GlslType.VEC3);
    }

    private final Map<String, TangentBasis> tangentBases = new HashMap<>();

    @Override
    protected TangentBasis tangentBasis(String space) {
        var key = space + (isFragmentStage() ? "|f" : "|v");
        var cached = tangentBases.get(key);
        if (cached != null) return cached;
        var basis = buildBasis(space);
        tangentBases.put(key, basis);
        return basis;
    }

    private TangentBasis buildBasis(String space) {
        var n = hoist(GlslType.VEC3, switch (space) {
            case "object" -> objectSpaceNormal().code();
            case "view" -> viewSpaceNormal().code();
            default -> worldSpaceNormal().code();
        });
        var tangent4 = "object".equals(space) ? objectTangent() : worldTangent();
        var raw = "view".equals(space)
                ? "(mat3(" + transformField("ModelViewMat", GlslType.MAT4).code() + ") * " + tangent4.code() + ".xyz)"
                : tangent4.code() + ".xyz";
        var t3 = hoist(GlslType.VEC3, raw);
        var proj = hoist(GlslType.VEC3, t3.code() + " - " + n.code()
                + " * dot(" + n.code() + ", " + t3.code() + ")");
        var s = hoist(GlslType.FLOAT, "(" + n.code() + ".z >= 0.0 ? 1.0 : -1.0)");
        var a = hoist(GlslType.FLOAT, "(-1.0 / (" + s.code() + " + " + n.code() + ".z))");
        var fallback = hoist(GlslType.VEC3, "vec3("
                + "1.0 + " + s.code() + " * " + n.code() + ".x * " + n.code() + ".x * " + a.code() + ", "
                + s.code() + " * (" + n.code() + ".x * " + n.code() + ".y * " + a.code() + "), "
                + "-" + s.code() + " * " + n.code() + ".x)");
        var usable = "dot(" + proj.code() + ", " + proj.code() + ") > 1.0e-12";
        var t = hoist(GlslType.VEC3,
                "(" + usable + " ? normalize(" + proj.code() + ") : " + fallback.code() + ")");
        var b = hoist(GlslType.VEC3, "(cross(" + n.code() + ", " + t.code() + ") * ("
                + usable + " ? sign(" + tangent4.code() + ".w) : 1.0))");
        return new TangentBasis(t, b, n);
    }

    private ShaderExpr worldTangent() {
        return varyingInput("photon_worldTangent", GlslType.VEC4,
                () -> new ShaderExpr(PARTICLE_DATA + ".Tangent", GlslType.VEC4),
                new ShaderExpr("vec4(1.0, 0.0, 0.0, 1.0)", GlslType.VEC4));
    }

    private ShaderExpr objectTangent() {
        return varyingInput("photon_objectTangent", GlslType.VEC4,
                () -> new ShaderExpr(PARTICLE_DATA + ".ObjectTangent", GlslType.VEC4),
                new ShaderExpr("vec4(1.0, 0.0, 0.0, 1.0)", GlslType.VEC4));
    }

    @Override
    protected ShaderExpr viewSpaceNormal() {
        String mv = transformField("ModelViewMat", GlslType.MAT4).code();
        return new ShaderExpr("normalize(mat3(" + mv + ") * " + worldSpaceNormal().code() + ")", GlslType.VEC3);
    }

    @Override protected String sceneColorSamplerName() { return SCENE_COLOR; }
    @Override protected String sceneDepthSamplerName() { return SCENE_DEPTH; }
}
