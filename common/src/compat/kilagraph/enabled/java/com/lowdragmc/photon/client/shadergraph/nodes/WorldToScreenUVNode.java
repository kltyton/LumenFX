/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.shadergraph.nodes;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderCompileContext;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderNode;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandles;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IOptionDefinitionContext;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderFunctionGraph;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;

import java.util.List;

/**
 * Projects a {@code Position} to a screen-space UV. Output is <b>window-relative</b> (the same space as
 * {@code screenUv()}) — it takes the projected point to NDC and reads it as a 0..1 position in the target
 * being drawn into, which is exactly what the scene capture is sized after, so it is correct in-world and
 * in the editor's own preview scene alike.
 * Pure uniform math (no {@code gl_FragCoord}), so it is usable in <b>both</b> the vertex and fragment
 * stages.
 *
 * <p>The {@code space} option says what {@code Position} is measured in:
 * <ul>
 *   <li><b>absolute</b> (default) — world/block coordinates; the camera position ({@code cameraWorldPos()})
 *       is subtracted for you (1.21 renders camera-relative).</li>
 *   <li><b>camera_relative</b> — already relative to the camera (e.g. {@code ParticleData.Position}),
 *       projected directly.</li>
 * </ul></p>
 *
 * <p>Also available to fullscreen post-processing passes (project a world point — a light, an entity — to
 * the uv a pass samples at). There the view/projection come from KilaGraph's {@code KG_Transforms} block
 * rather than from Minecraft's per-draw ones, which a bare quad blit never gets.
 * {@link ScreenToWorldNode} is the inverse.</p>
 */
@NodeAttribute(name = "photon_world_to_screen_uv", group = "photon_scene",
        graphTypes = {ShaderGraph.class, PhotonShaderFunctionGraph.class})
public class WorldToScreenUVNode extends ShaderNode {
    private static final List<String> SPACES = List.of("absolute", "camera_relative");

    @Override
    public void onDefineOptions(IOptionDefinitionContext context) {
        context.addOption("space", TypeHandles.STRING).withDefaultValue("absolute").build();
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addInputPort("position", RenderTypeGraphTypes.VEC3);
        context.addOutputPort("uv", RenderTypeGraphTypes.VEC2);
    }

    @Override
    public void compile(ShaderCompileContext ctx) {
        String pos = ctx.input("position").code();
        // absolute world coords -> camera-relative: 1.21 renders camera-relative, and cameraWorldPos() is the
        // absolute world camera position (KG's precision-split kg_CameraBlockPos - kg_CameraOffset), bound each
        // frame by KGBuiltinUniforms and overridden to the pipeline's render camera by ShaderGraphMaterial.
        if (ctx.option("space", String.class, "absolute").equals("absolute")) {
            // 26.1: KG's precision-split camera world position (kg_CameraBlockPos - kg_CameraOffset)
            String camPos = "(vec3(" + ctx.transformField("CameraBlockPos", GlslType.VEC3).code() + ") - "
                    + ctx.transformField("CameraOffset", GlslType.VEC3).code() + ")";
            pos = "(" + pos + " - " + camPos + ")";
        }
        ctx.useMinecraftUniform("Projection", "minecraft:projection.glsl");
        String proj = "ProjMat";
        String modelView = ctx.transformField("ModelViewMat", GlslType.MAT4).code();
        String clip = ctx.temp(GlslType.VEC4, proj + " * " + modelView + " * vec4(" + pos + ", 1.0)").code();
        ctx.output("uv", new ShaderExpr(
                "((" + clip + ".xy / " + clip + ".w) * 0.5 + 0.5)", GlslType.VEC2));
    }


    @Override
    public List<String> optionChoices(String optionId) {
        return "space".equals(optionId) ? SPACES : List.of();
    }

    @Override
    public String glslExample() {
        return """
                // absolute space
                vec3 p = position - cameraWorldPos;
                vec4 clip = ProjMat * ModelViewMat
                          * vec4(p, 1.0);
                uv = clip.xy / clip.w * 0.5 + 0.5;""";
    }

}
