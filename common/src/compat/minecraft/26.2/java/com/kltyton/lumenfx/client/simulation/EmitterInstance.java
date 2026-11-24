package com.kltyton.lumenfx.client.simulation;

import com.kltyton.lumenfx.effect.simulation.ParticleEmitterSimulation;
import com.kltyton.lumenfx.effect.simulation.UnityEmitterCapacity;
import com.kltyton.lumenfx.effect.simulation.ParticleState;
import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers;
import com.kltyton.lumenfx.client.render.ribbon.RibbonTessellation;
import com.kltyton.lumenfx.client.render.unity.UnityParticleLightProxy;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraParticles;
import com.kltyton.lumenfx.effect.simulation.unity.UnitySubEmitterEvent;
import com.kltyton.lumenfx.effect.simulation.unity.UnityTrailHistory;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.render.light.ParticleLight;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import com.kltyton.lumenfx.effect.unity.UnityExternalForces.FieldState;
import com.kltyton.lumenfx.effect.unity.UnityParticleTrail;
import com.kltyton.lumenfx.effect.unity.UnityRendererBinding;
import com.kltyton.lumenfx.effect.unity.UnityTrigger;
import com.lowdragmc.photon.client.gameobject.emitter.data.InheritVelocitySetting.Mode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.jspecify.annotations.Nullable;

/** Client presentation of the shared CPU particle simulation. */
public final class EmitterInstance extends ParticleEmitterSimulation {
    private final RibbonTessellation tessellation = new RibbonTessellation();

    public record Snapshot(EmitterDefinition definition, List<ParticleState> particles, EffectHandle.Input input,
                           ImprovedNoise noise, double time, double emitterAge, boolean emitting,
                           RibbonTessellation tessellation, NiagaraParticles nativeParticles,
                           EmitterDefinition simulationDefinition, List<UnityTrailHistory.Vertex> trailVertices,
                           long ribbonGeneration,
                           Pose unityRootPose, long unityInstanceId) {
        public Snapshot(EmitterDefinition definition, List<ParticleState> particles, EffectHandle.Input input,
                        ImprovedNoise noise, double time, double emitterAge, boolean emitting, RibbonTessellation tessellation) {
            this(definition, particles, input, noise, time, emitterAge, emitting, tessellation, null);
        }

        public Snapshot(EmitterDefinition definition, List<ParticleState> particles, EffectHandle.Input input,
                        ImprovedNoise noise, double time, double emitterAge, boolean emitting,
                        RibbonTessellation tessellation, NiagaraParticles nativeParticles) {
            this(definition, particles, input, noise, time, emitterAge, emitting, tessellation, nativeParticles, definition,
                    List.of(), 0, input.pose(), 0);
        }

        public Snapshot(EmitterDefinition definition, List<ParticleState> particles, EffectHandle.Input input,
                        ImprovedNoise noise, double time, double emitterAge, boolean emitting,
                        RibbonTessellation tessellation, NiagaraParticles nativeParticles,
                        EmitterDefinition simulationDefinition, List<UnityTrailHistory.Vertex> trailVertices,
                        long ribbonGeneration) {
            this(definition, particles, input, noise, time, emitterAge, emitting, tessellation, nativeParticles,
                    simulationDefinition, trailVertices, ribbonGeneration, input.pose(), 0);
        }

        Snapshot withUnityRenderer(EmitterDefinition renderer, @Nullable UnityRendererBinding binding,
                                   List<UnityTrailHistory.Vertex> trailVertices) {
            if (binding == null) return this;
            var tint = binding.tint();
            var color = input.withColor(input.red() * tint.red(), input.green() * tint.green(),
                    input.blue() * tint.blue(), input.alpha() * tint.alpha());
            return new Snapshot(renderer, particles, color, noise, time, emitterAge, emitting, tessellation, null,
                    simulationDefinition, trailVertices, ribbonGeneration, unityRootPose, unityInstanceId);
        }

        Snapshot withRenderInput(EffectHandle.Input renderInput) {
            return new Snapshot(definition, particles, renderInput, noise, time, emitterAge, emitting, tessellation,
                    nativeParticles, simulationDefinition, trailVertices, ribbonGeneration,
                    unityRootPose, unityInstanceId);
        }
    }
    public EmitterInstance(EmitterDefinition definition, long seed) {
        this(definition, seed, null, null);
    }

    EmitterInstance(EmitterDefinition definition, long seed, @Nullable UnityRendererBinding trailRenderer,
                    @Nullable UnityRendererBinding binding) {
        this(definition, seed, trailRenderer, binding, null, null);
    }

    EmitterInstance(EmitterDefinition definition, long seed, @Nullable UnityRendererBinding trailRenderer,
                    @Nullable UnityRendererBinding binding, @Nullable UnitySubEmitterEvent inherited,
                    @Nullable UnityEmitterCapacity sharedCapacity) {
        super(definition, seed, trailRenderer, binding, inherited, sharedCapacity);
    }

    public Snapshot update(double now, EffectHandle.Input input, boolean emitting) {
        return update(now, input, emitting, Map.of());
    }

    Snapshot update(double now, EffectHandle.Input input, boolean emitting, Map<String, FieldState> forceFields) {
        var step = simulate(now, input.pose(), emitting, true, null, null, null,
                UnityCollisionLayers.EMPTY, Map.of(), Map.of(), forceFields);
        return snapshot(step, input);
    }

    Snapshot update(double nextHostTime, EffectHandle.Input input, boolean emitting, boolean sampled,
                    @Nullable Vec3 physicsVelocity, @Nullable Level level, @Nullable Pose geometryPose,
                    UnityCollisionLayers collisionLayers, Map<Identifier, StaticMeshGeometry> emissionGeometry) {
        return update(nextHostTime, input, emitting, sampled, physicsVelocity, level, geometryPose,
                collisionLayers, emissionGeometry, Map.of());
    }

    Snapshot update(double nextHostTime, EffectHandle.Input input, boolean emitting, boolean sampled,
                    @Nullable Vec3 physicsVelocity, @Nullable Level level, @Nullable Pose geometryPose,
                    UnityCollisionLayers collisionLayers, Map<Identifier, StaticMeshGeometry> emissionGeometry,
                    Map<String, List<UnityTrigger.Collider>> triggerColliders) {
        var step = simulate(nextHostTime, input.pose(), emitting, sampled, physicsVelocity, level, geometryPose,
                collisionLayers, emissionGeometry, triggerColliders);
        return snapshot(step, input);
    }

    Snapshot snapshot(StepPlan step, EffectHandle.Input input) {
        return snapshot(step, input, input.pose(), 0);
    }

    Snapshot snapshot(StepPlan step, EffectHandle.Input input, Pose unityRootPose, long unityInstanceId) {
        return new Snapshot(definition, particlesView(), input, noise(), step.now(), elapsedEmitterTime(step.now()),
                emitting(), tessellation, null, definition, List.of(), ribbonGeneration(),
                unityRootPose, unityInstanceId);
    }

    List<UnityTrailHistory.Vertex> trailVertices(Snapshot snapshot, @Nullable UnityRendererBinding binding) {
        if (trail == null || binding == null || binding.particleTrail() == null
                || binding.particleTrail().mode() != UnityParticleTrail.Mode.PER_PARTICLE) return List.of();
        return trail.pack(snapshot.time(), snapshot.input().pose(), definition);
    }

    void appendLights(EffectDefinition effect, Snapshot snapshot, List<ParticleLight> output, int first,
                      List<ScenePointLight> sceneLights) {
        var light = lightSettings;
        if (light == null) return;
        double now = snapshot.time();
        var input = snapshot.input();
        for (var particle : snapshot.particles()) {
            if (output.size() - first + sceneLights.size() >= light.maximum()) return;
            if (particle.unityLightId() == -1) continue;
            double age = particle.normalizedAge(now), random = particle.random();
            double speed = particle.hasSpeedAppearance(definition) ? particle.sampleSpeed(now, definition) : 0;
            double radius = light.radius() * light.rangeOverLife().sample(age, random);
            if (light.sizeAffectsRange()) {
                radius *= particle.width() * particle.sizeScale(0, age, speed, definition);
                if (definition.localSpace()) radius *= Math.abs(input.pose().scaleX());
            }
            var color = light.useParticleColor() || light.alphaAffectsIntensity() ? currentColor(particle, age, speed) : null;
            double brightness = light.intensity() * light.intensityOverLife().sample(age, random);
            if (light.alphaAffectsIntensity()) brightness *= Objects.requireNonNull(color).alpha();
            if (!(radius > 0) || !(brightness > 0) || !Double.isFinite(brightness) || !Float.isFinite((float)(radius * 100))) continue;
            Vec3 tint = light.color();
            if (light.useParticleColor()) {
                var particleColor = Objects.requireNonNull(color);
                tint = tint.multiply(particleColor.red(), particleColor.green(), particleColor.blue());
            }
            tint = tint.scale(brightness);
            particle.samplePosition(now, definition, snapshot.noise(), eventScratch, 0);
            if (definition.localSpace()) input.pose().transformPosition(eventScratch, 0);
            var position = new Vec3(eventScratch[0], eventScratch[1], eventScratch[2]);
            if (light.profile() == null) {
                output.add(new ParticleLight(lightSeed, particle.unityLightId(), position, (float)(radius * 100), tint,
                        0, 0, true, false, false, 0, 1, 1));
            } else {
                var pose = input.pose();
                var basis = definition.localSpace() ? new Quaterniond(pose.x(), pose.y(), pose.z(), pose.w())
                        : Objects.requireNonNull(particle.unityLightBirthRotation());
                sceneLights.add(UnityParticleLightProxy.prepare(effect, particle.unitySceneLightIdentity(), light.profile(),
                        position, (float)(radius * 100), tint, currentRotation(particle, age, now), basis));
            }
        }
    }

}
