package com.kltyton.lumenfx.client.simulation.niagara.light;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererFields;
import com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererFields.Floating;
import com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererFields.IntegerField;
import com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererTransform;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraParticleReadTypes;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.render.light.LightRendererSettings;
import com.kltyton.lumenfx.effect.render.light.ParticleLight;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Cached light accessors read a completed dataset; GPU emitter mode uses only host bindings. */
public final class NiagaraLightParticles {
    private final Floating position, color, radius, exponent, scattering, specular, diffuse;
    private final IntegerField enabled, visibility;
    private final boolean emitter;
    private final int visibilityTag, uniqueId;
    private final NiagaraRendererTransform transform;

    public NiagaraLightParticles(NiagaraDefinition.Emitter source, NiagaraDefinition.Renderer renderer) {
        var attributes = source.attributes();
        emitter = renderer.sourceMode().equals("Emitter");
        transform = new NiagaraRendererTransform(source.localSpace());
        position = new Floating(attributes, renderer, "Position", "Particles.Position", "Position");
        color = new Floating(attributes, renderer, "Color", "Particles.Color", "Color");
        radius = new Floating(attributes, renderer, "Radius", "Particles.LightRadius", "Float");
        exponent = new Floating(attributes, renderer, "LightExponent", "Particles.LightExponent", "Float");
        scattering = new Floating(attributes, renderer, "VolumetricScattering", "Particles.LightVolumetricScattering", "Float");
        specular = new Floating(attributes, renderer, "SpecularScale", "Particles.LightSpecularScale", "Float");
        diffuse = new Floating(attributes, renderer, "DiffuseScale", "Particles.LightDiffuseScale", "Float");
        enabled = new IntegerField(attributes, renderer, "LightRenderingEnabled", "Particles.LightEnabled", true);
        boolean hostTag = emitter || !renderer.visibilityParticleBinding();
        visibility = new IntegerField(attributes, renderer.visibilityBinding(), hostTag, false);
        visibilityTag = renderer.rendererVisibility();
        var id = NiagaraParticleReadTypes.renderer(attributes, "UniqueID", "Int");
        uniqueId = id == null ? -1 : id.component();
    }

    public void append(LightRendererSettings settings, @Nullable VectorDataSet particles, VectorDataSet system,
                       Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                       long sourcePrimitive, double originX, double originY, double originZ,
                       float projectInverseExposureBlend, List<ParticleLight> output) {
        append(settings, particles, system, variables, input, null, sourcePrimitive, originX, originY, originZ,
                projectInverseExposureBlend, output);
    }

    public void append(LightRendererSettings settings, @Nullable VectorDataSet particles, VectorDataSet system,
                       Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                       @Nullable NiagaraParameterStore store, long sourcePrimitive, double originX, double originY, double originZ,
                       float projectInverseExposureBlend, List<ParticleLight> output) {
        if (!emitter && particles == null) throw new IllegalArgumentException("Particle light source requires CPU simulation");
        var inputs = new NiagaraRendererFields.Inputs(system, variables, input, store);
        position.defaults(0, inputs); color.defaults(1, inputs);
        radius.defaults(100, inputs); exponent.defaults(settings.defaultExponent(), inputs);
        scattering.defaults(0, inputs); specular.defaults(settings.specularScale(), inputs);
        diffuse.defaults(settings.diffuseScale(), inputs);
        int hostEnabled = enabled.defaultValue(-1, inputs);
        int hostVisibility = visibility.defaultValue(visibilityTag, inputs);
        transform.prepare(input.pose(), originX, originY, originZ);
        int count = emitter ? 1 : particles.count();
        for (int row = 0; row < count; row++) {
            float lightRadius = radius.read(particles, row, 0) * settings.radiusScale();
            if (enabled.read(particles, row, hostEnabled) == 0 || visibility.read(particles, row, hostVisibility) != visibilityTag
                    || !(lightRadius > 0)) continue;
            Vec3 world = transform.position(position.read(particles, row, 0), position.read(particles, row, 1), position.read(particles, row, 2));
            float brightness = settings.alphaScalesBrightness() ? color.read(particles, row, 3) : 1;
            Vec3 add = settings.colorAdd();
            float red = color.read(particles, row, 0) * brightness + (float)add.x;
            float green = color.read(particles, row, 1) * brightness + (float)add.y;
            float blue = color.read(particles, row, 2) * brightness + (float)add.z;
            Vec3 lightColor = new Vec3(red, green, blue);
            int id = emitter ? 0 : uniqueId < 0 ? -1 : particles.integer(uniqueId, row);
            output.add(new ParticleLight(sourcePrimitive, id, world, lightRadius, lightColor,
                    settings.inverseSquaredFalloff() ? 0 : exponent.read(particles, row, 0), settings.exposureBlend(projectInverseExposureBlend),
                    settings.affectsTranslucency(), settings.allowMegaLights(), settings.megaLightsCastShadows(),
                    scattering.read(particles, row, 0), specular.read(particles, row, 0), diffuse.read(particles, row, 0)));
        }
    }

    public @Nullable NiagaraBounds bounds(LightRendererSettings settings, VectorDataSet particles) {
        if (emitter || !position.hasParticleData() || particles.count() == 0) return null;
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        float maximumRadius = !radius.hasParticleData() ? 100 : Float.NEGATIVE_INFINITY;
        for (int row = 0; row < particles.count(); row++) {
            float x = position.read(particles, row, 0), y = position.read(particles, row, 1), z = position.read(particles, row, 2);
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            if (radius.hasParticleData()) maximumRadius = Math.max(maximumRadius, radius.read(particles, row, 0));
        }
        float extent = maximumRadius * settings.radiusScale();
        // Source bounds can shrink past the particle positions when every radius is negative.
        // An inverted box has no volume to contribute to the other renderers' bounds.
        if (minX - extent > maxX + extent || minY - extent > maxY + extent || minZ - extent > maxZ + extent)
            return null;
        return new NiagaraBounds(minX - extent, minY - extent, minZ - extent, maxX + extent, maxY + extent, maxZ + extent);
    }

}
