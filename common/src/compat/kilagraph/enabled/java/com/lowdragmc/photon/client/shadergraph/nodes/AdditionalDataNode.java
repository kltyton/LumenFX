/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.shadergraph.nodes;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderCompileContext;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderNode;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.INodeOption;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandle;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandles;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IOptionDefinitionContext;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;
import com.lowdragmc.photon.client.gameobject.emitter.data.PhotonGpuChannels;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderFunctionGraph;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;

/**
 * Reads one per-instance "additional GPU data" channel ({@link PhotonGpuChannels}) chosen from a
 * dropdown — normalized lifetime T, velocity, per-point trail data, beam direction, etc. The read
 * happens in the vertex stage through the {@code photon_data_*()} accessors declared by
 * {@code photon:particle.glsl} and is routed through an auto-varying, so the value is usable in
 * both stages. Instanced render passes auto-enable whatever channels the graph reads (the mask is
 * harvested at compile time via {@link PhotonShaderCompiler#markChannelUsed}).
 *
 * <p>Reads 0 on the CPU (non-instanced) path, in node previews, and on particle kinds that don't
 * support the chosen channel.</p>
 */
@NodeAttribute(name = "photon_additional_data", group = "photon_input",
        graphTypes = {ShaderGraph.class, PhotonShaderFunctionGraph.class})
public class AdditionalDataNode extends ShaderNode {

    private static final String OPTION = "channel";
    private static final String DEFAULT_ID = "addition_gpu_data.t";

    /** The currently-selected channel (read from the option; falls back to T). */
    private PhotonGpuChannels.Channel currentChannel() {
        String id = DEFAULT_ID;
        INodeOption opt = getNodeOptionById(OPTION);
        if (opt != null) {
            Object raw = opt.tryGetValue(Object.class).result().orElse(null);
            if (raw instanceof String s && !s.isEmpty()) {
                id = s;
            }
        }
        var channel = PhotonGpuChannels.byId(id);
        if (channel == null) {
            channel = PhotonGpuChannels.byId(DEFAULT_ID);
        }
        return channel;
    }

    @Override
    public void onDefineOptions(IOptionDefinitionContext context) {
        context.addOption(OPTION, TypeHandles.STRING)
                .withDefaultValue(DEFAULT_ID)
                .build();
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        context.addOutputPort("out", portType(currentChannel()));
    }

    @Override
    public void compile(ShaderCompileContext ctx) {
        var channel = currentChannel();
        var glslType = glslType(channel);
        var zero = zeroExpr(glslType);
        var compiler = PhotonShaderCompiler.current();
        if (compiler != null) {
            compiler.markChannelUsed(channel);
        }
        var suffix = channel.id().substring(channel.id().lastIndexOf('.') + 1);
        ctx.output("out", ctx.varyingInput("photon_ch_" + suffix, glslType,
                () -> new ShaderExpr(channel.glslAccessor() + "()", glslType), zero));
    }


    @Override
    public String glslExample() {
        return """
                // vertex stage, routed through a varying
                float t = photon_data_t();""";
    }

    private static TypeHandle portType(PhotonGpuChannels.Channel channel) {
        return "vec3".equals(channel.typeName()) ? RenderTypeGraphTypes.VEC3 : TypeHandles.FLOAT;
    }

    private static GlslType glslType(PhotonGpuChannels.Channel channel) {
        return "vec3".equals(channel.typeName()) ? GlslType.VEC3 : GlslType.FLOAT;
    }

    private static ShaderExpr zeroExpr(GlslType type) {
        return type == GlslType.VEC3
                ? new ShaderExpr("vec3(0.0)", GlslType.VEC3)
                : new ShaderExpr("0.0", GlslType.FLOAT);
    }

}
