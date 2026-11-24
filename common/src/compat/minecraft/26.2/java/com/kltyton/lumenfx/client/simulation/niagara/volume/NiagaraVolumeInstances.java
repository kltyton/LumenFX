package com.kltyton.lumenfx.client.simulation.niagara.volume;

import com.kltyton.lumenfx.api.client.parameter.RendererParameter;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.volume.NiagaraVolumeInstance;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraRendererParameters;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererFields.Floating;
import com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererFields.Inputs;
import com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererFields.IntegerField;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.render.volume.VolumeTracingSettings;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.jspecify.annotations.Nullable;

/** Resolves the volume renderer's source proxy, material, visibility and voxel dimensions. */
public final class NiagaraVolumeInstances {
    private final NiagaraDefinition.Emitter emitter;
    private final NiagaraDefinition.Renderer renderer;
    private final Floating position, rotation, scale;
    private final IntegerField visibility;

    public NiagaraVolumeInstances(NiagaraDefinition.Emitter emitter, NiagaraDefinition.Renderer renderer) {
        this.emitter = emitter; this.renderer = renderer;
        var attributes = emitter.attributes();
        position = new Floating(attributes, renderer, "Position", "Particles.Position", "Position");
        rotation = new Floating(attributes, renderer, "VolumeRotation", "Particles.VolumeRotation", "Quaternion");
        scale = new Floating(attributes, renderer, "VolumeScale", "Particles.VolumeScale", "Vector");
        visibility = new IntegerField(attributes, renderer, "RendererVisibilityTag", renderer.visibilityBinding(), false);
    }

    public void append(List<NiagaraVolumeInstance> output, EffectDefinition effect, EmitterDefinition definition, Inputs values,
                       @Nullable VectorDataSet particles, NiagaraBounds bounds, Vec3 tile,
                       UnaryOperator<MaterialDefinition> materialInstance) {
        if (!VolumeTracingSettings.sourcePlatformSupportsVolumes(NiagaraPlatforms.snapshot())) return;
        boolean emitterSource = renderer.sourceMode().equals("Emitter");
        if (!emitterSource && (particles == null || particles.count() == 0)) return;
        var settings = Objects.requireNonNull(definition.volumeSettings());
        int visibilityDefault = visibility.defaultValue(renderer.rendererVisibility(), values);
        if (emitterSource && visibilityDefault != renderer.rendererVisibility()) return;
        int axis = maxAxis(values);
        var size = new float[3];
        var sizeBinding = renderer.attributeBindings().get("VolumeWorldSpaceSize");
        if (sizeBinding != null)
            NiagaraRendererParameters.floating(sizeBinding.name(), values.system(), values.variables(), values.input(), values.store(), size, emitter.rendererBindings());
        float maximum = Math.max(size[0], Math.max(size[1], size[2]));
        float ratio = maximum > 0 ? 1f / maximum : 0;
        int x = (int)Math.ceil(size[0] * ratio * axis), y = (int)Math.ceil(size[1] * ratio * axis), z = (int)Math.ceil(size[2] * ratio * axis);
        if (x < 1 || y < 1 || z < 1) return;
        var bound = settings.materialBinding() == null ? null
                : RendererParameter.named(values.input().rendererParameters(), settings.materialBinding());
        var material = RendererParameter.material(bound, settings.materialVariant());
        if (material == null) material = Objects.requireNonNull(effect.materials().get(definition.material()), "Niagara volume material is missing");
        material = materialInstance.apply(material);
        var owner = NiagaraComponentMatrices.source(values.input().pose(), 0, 0, 0, true, new Matrix4d());
        var actorPosition = values.input().actorPosition();
        var customPrimitiveData = values.input().customPrimitiveData();
        if (emitterSource) {
            output.add(new NiagaraVolumeInstance(material, owner, owner, bounds, actorPosition, customPrimitiveData, x, y, z, settings));
            return;
        }
        position.defaults(0, values);
        rotation.defaults(new float[]{0, 0, 0, 1}, values);
        scale.defaults(1, values);
        for (int row = 0; row < particles.count(); row++) {
            if (visibility.read(particles, row, visibilityDefault) != renderer.rendererVisibility()) continue;
            double sx = scale.read(particles, row, 0), sy = scale.read(particles, row, 1), sz = scale.read(particles, row, 2);
            if (sx * sx + sy * sy + sz * sz <= 1.e-8) continue;
            var q = new Quaterniond(rotation.read(particles, row, 0), rotation.read(particles, row, 1),
                    rotation.read(particles, row, 2), rotation.read(particles, row, 3));
            if (q.lengthSquared() > 1.e-8) q.normalize();
            else q.identity();
            var matrix = NiagaraComponentMatrices.srt(q.x(), q.y(), q.z(), q.w(), sx, sy, sz,
                    position.read(particles, row, 0), position.read(particles, row, 1), position.read(particles, row, 2), new Matrix4d());
            if (emitter.localSpace()) matrix.mul(owner);
            else matrix.setTranslation(matrix.m30() + tile.x, matrix.m31() + tile.y, matrix.m32() + tile.z);
            output.add(new NiagaraVolumeInstance(material, matrix, owner, bounds, actorPosition, customPrimitiveData, x, y, z, settings));
        }
    }

    private int maxAxis(Inputs values) {
        var binding = renderer.attributeBindings().get("VolumeResolutionMaxAxis");
        if (binding == null) return 0;
        Integer result = NiagaraRendererParameters.integer(binding.name(), false, values.system(), values.variables(), values.input(), values.store(), emitter.rendererBindings());
        return result == null ? 0 : result;
    }
}
