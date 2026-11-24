package com.kltyton.lumenfx.client.simulation;

import com.kltyton.lumenfx.effect.simulation.ParticleState;
import com.kltyton.lumenfx.effect.simulation.UnityEmitterCapacity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.render.EffectMeshBuilder;
import com.kltyton.lumenfx.client.resource.EffectJson;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import com.kltyton.lumenfx.effect.simulation.unity.UnityBirthRandom;
import com.kltyton.lumenfx.effect.unity.UnityAtlasAnimation;
import com.kltyton.lumenfx.effect.unity.UnityConeEmission;
import com.kltyton.lumenfx.effect.unity.UnityShapeSettings;
import com.lowdragmc.photon.client.gameobject.emitter.data.ContinuousEmission;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.ShapeArcMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class UnitySimulationParityTest {
    private static final double EPS = 1e-4;
    private static final float INV_SQRT2 = (float) (Math.sqrt(0.5));

    @Test
    void seededUnityBirthPropertiesMatchMixedNativeBurstLanes() {
        var definition = parse("""
                {
                  "format_version": 1,
                  "materials": {"mat": {}},
                  "emitters": [{
                    "name": "particles", "renderer": "sprite", "material": "mat",
                    "duration": 2, "max_particles": 16, "rate": 0,
                    "bursts": [
                      {"time": 0, "min": 5, "max": 5},
                      {"time": 0.25, "min": 3, "max": 3},
                      {"time": 0.6, "min": 1, "max": 1}
                    ],
                    "lifetime": {"min": 1, "max": 2},
                    "start_speed": {"min": 0.5, "max": 2.5},
                    "width": {"min": 0.3, "max": 0.7},
                    "height": {"min": 0.3, "max": 0.7},
                    "rotation": {"min": 0.05, "max": 0.65},
                    "start_color": {"random_time": [
                      {"keys": [{"time": 0, "value": 1}, {"time": 0.35, "value": 0}]},
                      {"keys": [{"time": 0, "value": 0}, {"time": 0.35, "value": 1}]},
                      0,
                      {"keys": [{"time": 0, "value": 0.2}, {"time": 0.25, "value": 0.75}]}
                    ]},
                    "unity_renderer": {"tint": [1, 1, 1, 1]}
                  }]
                }
                """);
        var authored = definition.emitters().getFirst();
        var binding = definition.unityRenderers().get("particles");
        var input = new EffectHandle(Vec3.ZERO).input();
        var zero = new EmitterInstance(authored, 0, null, binding);
        zero.update(0, input, true);
        var sample = zero.update(0.65, input, true).particles();
        assertEquals(9, sample.size());
        // Unity 6000.6.10f1 mixed bursts of five, three and one. Index six uses the second lane.
        var secondLane = sample.get(6);
        assertEquals(1.3798666, secondLane.lifetime(), 1e-6);
        assertEquals(0.4533086, secondLane.width(), 1e-6);
        assertEquals(0.1053967, secondLane.rotation(), 1e-6);
        assertEquals(0.660794735, secondLane.velocity().length(), 1e-6);
        var maximum = new EmitterInstance(authored, 0xffff_ffffL, null, binding);
        maximum.update(0, input, true);
        var first = maximum.update(0.65, input, true).particles().getFirst();
        assertEquals(1.073801, first.lifetime(), 1e-6);
        assertEquals(0.467762947, first.width(), 1e-6);
        assertEquals(0.607711256, first.rotation(), 1e-6);

        var randomColor = new EmitterInstance(authored, 3821, null, binding);
        randomColor.update(0, input, true);
        var color = randomColor.update(0.05, input, true).particles().getFirst().startColor();
        // Color32 recorded from Unity for the first particle, seed 3821.
        assertEquals(232, Math.round(color.red() * 255));
        assertEquals(23, Math.round(color.green() * 255));
        assertEquals(69, Math.round(color.alpha() * 255));
    }

    @Test
    void nativeMeshChoicePreservesUniformWeightedAndZeroWeightModes() {
        var first = new UnityBirthRandom(0).next(0);
        // Unity 6000.6 recorded mesh 2 out of four uniformly weighted slots.
        assertEquals(2, first.meshIndex(4, List.of()));
        assertEquals(2, first.meshIndex(4, List.of(0f, 0f, 0f, 0f)));
        assertEquals(1, first.meshIndex(2, List.of(2f, 5f)));
        assertEquals(0, first.meshIndex(2, List.of(2f, 0f)));
        assertEquals(1, first.meshIndex(2, List.of(0f, 1f)));
        assertEquals(0, first.meshAtlasRow(2, 4));
        assertEquals(40, first.meshAtlasRow(3, 61));
        assertEquals(1, first.meshAtlasRow(3, 2));

        var defaultWeight = new UnityBirthRandom(3821).next(0);
        assertEquals(1, defaultWeight.meshIndex(2, List.of(0f, 0f)));
        assertEquals(2, defaultWeight.meshAtlasRow(2, 4));
    }

    @Test
    void randomAtlasRowsPreserveNativeSeed() {
        var first = new UnityBirthRandom(1).next(0);
        assertEquals(42, first.randomAtlasRow(503));
        assertEquals(43, first.randomAtlasRow(511));

        var lanes = new UnityBirthRandom(0);
        assertEquals(1, lanes.next(0).randomAtlasRow(2));
        assertEquals(0, lanes.next(0).randomAtlasRow(2));
        lanes.reset();
        assertEquals(369, lanes.next(0).randomAtlasRow(511));
        assertEquals(51, lanes.next(0).randomAtlasRow(511));
        assertEquals(50, UnityAtlasAnimation.fixedRow(51, 4, 97));
    }

    @Test
    void singleRowAtlasUsesNativeFloatBoundaries() {
        // Adjacent phases from Unity 6000.6.10f1 BakeMesh for Custom, Random and Mesh Index.
        int[][] samples = {
                {61, 44, 1048575878, 176},
                {97, 70, 1048575709, 280},
                {503, 335, 1048576000, 1340}
        };
        for (int[] sample : samples) {
            float before = Float.intBitsToFloat(sample[2]);
            float after = Math.nextUp(before);
            assertEquals(sample[3], (int) UnityAtlasAnimation.sampleRow(sample[1], 4, sample[0], before));
            assertEquals(sample[3] + 1, (int) UnityAtlasAnimation.sampleRow(sample[1], 4, sample[0], after));
        }
    }

    @Test
    void prewarmedRateAndBurstShareNativeSeedLanes() {
        var definition = parse("""
                {
                  "format_version": 1,
                  "materials": {"mat": {}},
                  "emitters": [{
                    "name": "particles", "renderer": "sprite", "material": "mat",
                    "duration": 2, "loop": true, "max_particles": 128, "rate": 4,
                    "bursts": [
                      {"time": 0.25, "min": 1, "max": 1},
                      {"time": 1.25, "min": 2, "max": 2}
                    ],
                    "lifetime": {"min": 2, "max": 4},
                    "unity_renderer": {"tint": [1, 1, 1, 1], "prewarm": true}
                  }]
                }
                """);
        var input = new EffectHandle(Vec3.ZERO).input();
        var emitter = new EmitterInstance(definition.emitters().getFirst(), 3821, null,
                definition.unityRenderers().get("particles"));
        emitter.update(0, input, true);
        var sample = emitter.update(.075, input, true).particles();
        // Isolated Unity prefab, seed 3821. At age .075, two of three co-timed births are still alive.
        assertEquals(16, sample.size());
        var oldest = sample.stream().filter(particle -> Math.abs(particle.born() + 2.75) < EPS)
                .map(ParticleState::lifetime).sorted().toList();
        assertEquals(2, oldest.size());
        assertEquals(2.891738, oldest.getFirst(), 1e-5);
        assertEquals(3.480939, oldest.getLast(), 1e-5);
    }

    @Test
    void shurikenBurstSpreadIncludesOnlyDistinctPathEndpoints() {
        var spread = new UnityShapeSettings.Traversal(ShapeArcMode.BurstSpread, 0, ScalarFunction.constant(1));
        assertEquals(0, spread.sample(0, 0, 1, 0, true), EPS);
        assertEquals(1, spread.sample(0, 1, 2, 0, true), EPS);
        assertEquals(1.0 / 3, spread.sample(0, 1, 4, 0, true), EPS);
        assertEquals(1, spread.sample(0, 3, 4, 0, true), EPS);
        assertEquals(0.75, spread.sample(0, 3, 4, 0, false), EPS);
    }

    @Test
    void shurikenPingPongSpreadMeasuresTravelFromEachTurnaround() {
        var traversal = new UnityShapeSettings.Traversal(ShapeArcMode.PingPong, 0.25f, ScalarFunction.constant(1));
        assertEquals(0.75, traversal.sample(0.95f, 0, 1, 0, true), EPS);
        assertEquals(1, traversal.sample(1, 0, 1, 0, true), EPS);
        assertEquals(1, traversal.sample(1.125f, 0, 1, 0, true), EPS);
        assertEquals(0.75, traversal.sample(1.27f, 0, 1, 0, true), EPS);
        assertEquals(0, traversal.sample(2, 0, 1, 0, true), EPS);
    }

    @Test
    void shurikenConeScalesItsRayAndKeepsVolumeLengthAtNinetyDegrees() {
        Vec3 scale = new Vec3(2, 1, 1);
        double angle = Math.toRadians(35);
        var base = UnityConeEmission.sample(new UnityShapeSettings.Cone(1, 0), scale, angle, 0, 0, new SplittableRandom(3821));
        assertEquals(2, base.position().x, EPS);
        assertEquals(2 * Math.tan(angle), base.direction().x / base.direction().y, EPS);
        assertEquals(1, base.direction().length(), EPS);

        var volume = UnityConeEmission.sample(new UnityShapeSettings.Cone(1, 2), scale, Math.PI / 2, 0, 0, new SplittableRandom(3821));
        assertTrue(volume.position().x > 2 && volume.position().x <= 6);
        assertEquals(0, volume.position().y, EPS);
        assertEquals(Vec3.ZERO.add(1, 0, 0), volume.direction());
    }

    private static EffectDefinition parse(String json) {
        return EffectJson.read(JsonParser.parseString(json).getAsJsonObject());
    }

    private static EffectHandle.Input inputAt(Vec3 pos, float rx, float ry, float rz, float rw, float scale) {
        EffectHandle handle = new EffectHandle(pos);
        handle.setPose(new EffectHandle.Pose(pos, rx, ry, rz, rw, scale));
        return handle.input();
    }

    @Test
    void customSimulationSpaceAppliesEmissionTransformAndFollowsOwnerPose() {
        String json = """
                {
                  "format_version": 1,
                  "materials": { "mat": {} },
                  "emitters": [
                    {
                      "name": "shape_only",
                      "renderer": "sprite",
                      "material": "mat",
                      "local_space": true,
                      "duration": 5,
                      "loop": false,
                      "max_particles": 16,
                      "rate": 0,
                      "bursts": [ { "time": 0, "min": 1, "max": 1 } ],
                      "lifetime": 5,
                      "width": 2,
                      "height": 4,
                      "start_speed": 10,
                      "start_direction": [0, 1, 0],
                      "unity_renderer": {
                        "emission_transform": {
                          "position": [3, 1, -2],
                          "rotation": [0, 0, %f, %f],
                          "scale": [2, 3, 4],
                          "shape_only_scale": true
                        }
                      }
                    },
                    {
                      "name": "scaled_all",
                      "renderer": "sprite",
                      "material": "mat",
                      "local_space": true,
                      "duration": 5,
                      "loop": false,
                      "max_particles": 16,
                      "rate": 0,
                      "bursts": [ { "time": 0, "min": 1, "max": 1 } ],
                      "lifetime": 5,
                      "width": 2,
                      "height": 4,
                      "start_speed": 10,
                      "start_direction": [0, 1, 0],
                      "unity_renderer": {
                        "emission_transform": {
                          "position": [3, 1, -2],
                          "rotation": [0, 0, %f, %f],
                          "scale": [2, 2, 2],
                          "shape_only_scale": false
                        }
                      }
                    }
                  ]
                }
                """.formatted(INV_SQRT2, INV_SQRT2, INV_SQRT2, INV_SQRT2);

        EffectDefinition def = parse(json);
        var shapeOnlyDef = def.emitters().get(0);
        var scaledAllDef = def.emitters().get(1);
        var shapeOnlyBinding = def.unityRenderers().get("shape_only");
        var scaledAllBinding = def.unityRenderers().get("scaled_all");

        EmitterInstance shapeOnly = new EmitterInstance(shapeOnlyDef, 42L, null, shapeOnlyBinding);
        var snap0 = shapeOnly.update(0.0, inputAt(Vec3.ZERO, 0, 0, 0, 1, 1f), true);
        assertEquals(1, snap0.particles().size());
        ParticleState p0 = snap0.particles().get(0);
        assertEquals(3.0, p0.position().x, EPS);
        assertEquals(1.0, p0.position().y, EPS);
        assertEquals(-2.0, p0.position().z, EPS);
        // 90deg Z rotates +Y direction to -X with start_speed 10
        assertEquals(-10.0, p0.velocity().x, EPS);
        assertEquals(0.0, p0.velocity().y, EPS);
        assertEquals(0.0, p0.velocity().z, EPS);
        // shape_only_scale leaves particle size untouched
        assertEquals(2.0, p0.width(), EPS);
        assertEquals(4.0, p0.height(), EPS);

        EmitterInstance scaledAll = new EmitterInstance(scaledAllDef, 42L, null, scaledAllBinding);
        var snapScaled = scaledAll.update(0.0, inputAt(Vec3.ZERO, 0, 0, 0, 1, 1f), true);
        ParticleState p1 = snapScaled.particles().get(0);
        assertEquals(3.0, p1.position().x, EPS);
        assertEquals(4.0, p1.width(), EPS);
        assertEquals(8.0, p1.height(), EPS);

        // Moving Custom simulation space owner pose moves rendered local particles cleanly
        EffectMeshBuilder builder = new EffectMeshBuilder(0);
        builder.begin(new Vec3(0, 0, 10), new org.joml.Quaternionf());
        var snapMoved = shapeOnly.update(0.5, inputAt(new Vec3(10, 5, 0), 0, 0, 0, 1, 1f), true);
        builder.add(def, snapMoved);
        var meshes = builder.finish();
        assertEquals(1, meshes.size());
        // Verify particle rendered near custom simulation space translated by (10, 5, 0) + birth(3,1,-2) + vel(-10,0,0)*0.5 = (8, 6, -2)
        float[] vals = snapMoved.particles().stream()
                .map(p -> p.positionAt(0.5, shapeOnlyDef, snapMoved.noise()))
                .findFirst().map(v -> new float[] {(float) v.x, (float) v.y, (float) v.z}).orElseThrow();
        assertEquals(-2.0f, vals[0], 1e-2f);
        assertEquals(1.0f, vals[1], 1e-2f);
        assertEquals(-2.0f, vals[2], 1e-2f);
    }

    @Test
    void allSpaceTransformModesRotateVelocityForceAndVelocityLimit() {
        // 1) Fixed space rotation (90 deg Z: +Y -> -X) ignores rotated owner pose (90 deg X)
        // 2) Non-fixed velocity & limit space_transform (90 deg Y: +X -> -Z) rotates relative to local/world pose
        String json = """
                {
                  "format_version": 1,
                  "materials": { "mat": {} },
                  "emitters": [
                    {
                      "name": "fixed_motion",
                      "renderer": "sprite",
                      "material": "mat",
                      "local_space": true,
                      "duration": 5,
                      "loop": false,
                      "max_particles": 8,
                      "rate": 0,
                      "bursts": [ { "time": 0, "min": 1, "max": 1 } ],
                      "lifetime": 5,
                      "unity_renderer": {
                        "space_motion": {
                          "velocity": [0, 6, 0],
                          "force": [0, 4, 0],
                          "velocity_space": { "fixed": true, "rotation": [0, 0, %f, %f] },
                          "force_space": { "fixed": true, "rotation": [0, 0, %f, %f] }
                        }
                      }
                    },
                    {
                      "name": "cross_space_limited",
                      "renderer": "sprite",
                      "material": "mat",
                      "local_space": false,
                      "duration": 5,
                      "loop": false,
                      "max_particles": 8,
                      "rate": 0,
                      "bursts": [ { "time": 0, "min": 1, "max": 1 } ],
                      "lifetime": 5,
                      "unity_renderer": {
                        "space_motion": {
                          "velocity": [10, 0, 0],
                          "velocity_space": { "fixed": false, "rotation": [0, %f, 0, %f] }
                        },
                        "velocity_limit": {
                          "axes": [100, 100, 2],
                          "dampen": 1.0,
                          "local_space": false,
                          "space_transform": { "fixed": false, "rotation": [0, 0, 0, 1] }
                        }
                      }
                    }
                  ]
                }
                """.formatted(INV_SQRT2, INV_SQRT2, INV_SQRT2, INV_SQRT2, INV_SQRT2, INV_SQRT2);

        EffectDefinition def = parse(json);
        // Owner rotated 90 deg around X (leaves fixed Z rotation unaffected because fixed=true)
        var rotatedPoseInput = inputAt(Vec3.ZERO, INV_SQRT2, 0, 0, INV_SQRT2, 1f);

        var fixedDef = def.emitters().get(0);
        EmitterInstance fixedEmitter = new EmitterInstance(fixedDef, 7L, null, def.unityRenderers().get("fixed_motion"));
        fixedEmitter.update(0.0, rotatedPoseInput, true);
        var snapFixed = fixedEmitter.update(1.0, rotatedPoseInput, true);
        ParticleState pf = snapFixed.particles().get(0);
        double[] vel = new double[3];
        pf.sampleVelocity(1.0, fixedDef, vel, 0);
        // Velocity(0,6,0) + Force(0,4,0)*1s both rotated 90 deg Z -> (-10, 0, 0)
        assertEquals(-10.0, vel[0], 0.15);
        assertEquals(0.0, vel[1], 0.15);
        assertEquals(0.0, vel[2], 0.15);

        // Identity pose on cross-space limited emitter: +X rotated +90deg Y -> -Z (0, 0, -10), clamped along Z to 2
        var limitDef = def.emitters().get(1);
        EmitterInstance limitEmitter = new EmitterInstance(limitDef, 7L, null, def.unityRenderers().get("cross_space_limited"));
        var identityInput = inputAt(Vec3.ZERO, 0, 0, 0, 1, 1f);
        limitEmitter.update(0.0, identityInput, true);
        var snapLimited = limitEmitter.update(0.5, identityInput, true);
        ParticleState pl = snapLimited.particles().get(0);
        double[] limitedVel = new double[3];
        pl.sampleVelocity(0.5, limitDef, limitedVel, 0);
        assertEquals(0.0, limitedVel[0], 0.05);
        assertEquals(0.0, limitedVel[1], 0.05);
        assertTrue(limitedVel[2] < 0 && Math.abs(limitedVel[2]) <= 2.05,
                "Expected Z speed clamped to <= 2.0, got " + limitedVel[2]);
    }

    @Test
    void shurikenTimeAndDistanceShareOneEmissionRemainder() {
        // Unity 6000.6.10f1, 6 per second and 4 per metre at .9 m/s. The first birth is at x=.096.
        for (String distance : List.of(
                "\"distance_spacing\": 0.25,",
                "\"rate_over_distance\": {\"keys\":[{\"time\":0,\"value\":4},{\"time\":1,\"value\":4}]},"
        )) {
            var definition = parse("""
                    {
                      "format_version": 1,
                      "materials": { "mat": {} },
                      "emitters": [{
                        "name": "moving", "renderer": "sprite", "material": "mat",
                        "duration": 2.0, "loop": false, "rate": 6,
                        %s
                        "lifetime": 4, "unity_renderer": {"tint": [1, 1, 1, 1]}
                      }]
                    }
                    """.formatted(distance));
            var emitter = new EmitterInstance(definition.emitters().getFirst(), 3821, null,
                    definition.unityRenderers().get("moving"));
            emitter.update(0.0, inputAt(new Vec3(.045, 0, 0), 0, 0, 0, 1, 1f), true);
            for (int frame = 1; frame <= 5; frame++) {
                double age = frame * .05;
                var step = emitter.update(age, inputAt(new Vec3(age * .9, 0, 0), 0, 0, 0, 1, 1f), true);
                assertEquals(frame < 3 ? 0 : frame < 5 ? 1 : 2, step.particles().size(), distance);
                if (frame == 3) assertEquals(.096, step.particles().getFirst().position().x, EPS, distance);
            }
        }
    }

    @Test
    void rateOverDistanceCurvesAndContinuousEmissionEmitDistanceWorkAcrossNonConstantCurves() {
        // 1) Direct ContinuousEmission non-linear RateIntegral test: integral(a, b) = (b^2 - a^2) * 10
        ContinuousEmission emission = new ContinuousEmission(2.0, false, (a, b) -> (b * b - a * a) * 10.0);
        List<Double> birthFractions = new ArrayList<>();
        emission.emitDistance(1.0, 0.0, 2.0, (a, b) -> (b * b - a * a) * 10.0, () -> true, birthFractions::add);
        // Total integral across full [0, duration=2] (normalized [0, 1]) is 10 -> 10 particles with quadratically accelerating density (later gaps smaller)
        assertEquals(10, birthFractions.size());
        assertTrue(birthFractions.get(0) < birthFractions.get(birthFractions.size() - 1));
        double firstGap = birthFractions.get(1) - birthFractions.get(0);
        double lastGap = birthFractions.get(9) - birthFractions.get(8);
        assertTrue(lastGap < firstGap, "Quadratic distance rate must crowd births later: firstGap=" + firstGap + ", lastGap=" + lastGap);

        // 2) EmitterInstance with curved JSON rate_over_distance: linear ramp 0 -> 40/unit over [0, 1] emitter age
        String json = """
                {
                  "format_version": 1,
                  "materials": { "mat": {} },
                  "emitters": [
                    {
                      "name": "distance_curved",
                      "renderer": "sprite",
                      "material": "mat",
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 256,
                      "rate": 0,
                      "rate_over_distance": {
                        "keys": [
                          { "time": 0, "value": 0, "interpolation": "linear" },
                          { "time": 1, "value": 40, "interpolation": "linear" }
                        ]
                      },
                      "lifetime": 5
                    }
                  ]
                }
                """;
        EffectDefinition def = parse(json);
        EmitterInstance instance = new EmitterInstance(def.emitters().get(0), 99L);
        instance.update(0.0, inputAt(Vec3.ZERO, 0, 0, 0, 1, 1f), true);
        var half = instance.update(0.5, inputAt(new Vec3(1, 0, 0), 0, 0, 0, 1, 1f), true);
        int firstHalfCount = half.particles().size();
        var full = instance.update(1.0, inputAt(new Vec3(2, 0, 0), 0, 0, 0, 1, 1f), true);
        int secondHalfSpawned = full.particles().size() - firstHalfCount;
        assertTrue(firstHalfCount >= 8 && firstHalfCount <= 12, "First half [0..0.5] expected ~10 particles, got " + firstHalfCount);
        assertTrue(secondHalfSpawned > firstHalfCount * 2,
                "Second half must spawn much more than first half for ramp 0->40: first=" + firstHalfCount + ", second=" + secondHalfSpawned);
    }

    @Test
    void randomizedForceIntegratesIndependentlyPerAxisAndReproducesDeterministically() {
        String json = """
                {
                  "format_version": 1,
                  "materials": { "mat": {} },
                  "emitters": [
                    {
                      "name": "rand_force",
                      "renderer": "sprite",
                      "material": "mat",
                      "duration": 5,
                      "loop": false,
                      "max_particles": 64,
                      "rate": 0,
                      "bursts": [ { "time": 0, "min": 16, "max": 16 } ],
                      "lifetime": 5,
                      "force_over_life": [0, 0, 0],
                      "velocity_over_life": [0, 0, 0],
                      "unity_renderer": {
                        "space_motion": {
                          "force": [
                            { "min": 4, "max": 8 },
                            { "min": -6, "max": 6 },
                            { "min": 12, "max": 20 }
                          ],
                          "randomized_force": true
                        }
                      }
                    }
                  ]
                }
                """;
        EffectDefinition def = parse(json);
        var emitterDef = def.emitters().get(0);
        var binding = def.unityRenderers().get("rand_force");
        var input = inputAt(Vec3.ZERO, 0, 0, 0, 1, 1f);

        EmitterInstance runA = new EmitterInstance(emitterDef, 777L, null, binding);
        runA.update(0.0, input, true);
        var snapA = runA.update(0.5, input, true);

        EmitterInstance runB = new EmitterInstance(emitterDef, 777L, null, binding);
        runB.update(0.0, input, true);
        var snapB = runB.update(0.5, input, true);

        assertEquals(16, snapA.particles().size());
        assertEquals(16, snapB.particles().size());

        boolean sawPositiveY = false, sawNegativeY = false, sawIndependentXVsZ = false;
        double[] firstVel = null;
        for (int i = 0; i < 16; i++) {
            ParticleState pa = snapA.particles().get(i);
            ParticleState pb = snapB.particles().get(i);
            Vec3 posA = pa.motion().position();
            Vec3 posB = pb.motion().position();
            assertEquals(posA.x, posB.x, 1e-6);
            assertEquals(posA.y, posB.y, 1e-6);
            assertEquals(posA.z, posB.z, 1e-6);

            double[] vel = new double[3];
            pa.sampleVelocity(0.5, emitterDef, vel, 0);
            // v(0.5) = 0.5 * f -> X in [2, 4], Y in [-3, 3], Z in [6, 10]
            assertTrue(vel[0] >= 1.9 && vel[0] <= 4.1, "vx out of bounds: " + vel[0]);
            assertTrue(vel[1] >= -3.1 && vel[1] <= 3.1, "vy out of bounds: " + vel[1]);
            assertTrue(vel[2] >= 5.9 && vel[2] <= 10.1, "vz out of bounds: " + vel[2]);
            assertTrue(posA.z > 0.5, "Expected displacement under randomized force even when primary force_over_life=0");
            if (vel[1] > 0.2) sawPositiveY = true;
            if (vel[1] < -0.2) sawNegativeY = true;
            if (firstVel == null) firstVel = vel.clone();
            else {
                double rx = (vel[0] - 2.0) / 2.0;
                double rz = (vel[2] - 6.0) / 4.0;
                if (Math.abs(rx - rz) > 0.08) sawIndependentXVsZ = true;
            }
        }
        assertFalse(!sawPositiveY || !sawNegativeY, "Expected independent symmetric Y force spread");
        assertTrue(sawIndependentXVsZ, "Expected X and Z random forces to vary independently per axis");
    }

    @Test
    void burstAndContinuousBirthsFollowChronologicalOrderUnderCapacityAcrossStepSizes() {
        String json = """
                {
                  "format_version": 1,
                  "materials": { "mat": {} },
                  "emitters": [
                    {
                      "name": "chrono_cap",
                      "renderer": "sprite",
                      "material": "mat",
                      "local_space": true,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 3,
                      "rate": 50,
                      "bursts": [ { "time": 0.05, "min": 2, "max": 2 } ],
                      "lifetime": 5.0,
                      "start_speed": 10.0,
                      "start_direction": [0, 0, 1]
                    }
                  ]
                }
                """;
        EffectDefinition def = parse(json);
        var emitterDef = def.emitters().get(0);
        var input = inputAt(Vec3.ZERO, 0, 0, 0, 1, 1f);

        EmitterInstance singleJump = new EmitterInstance(emitterDef, 1234L, null, null);
        singleJump.update(0.0, input, true);
        var jumpSnap = singleJump.update(0.1, input, true);

        EmitterInstance subStepped = new EmitterInstance(emitterDef, 1234L, null, null);
        subStepped.update(0.0, input, true);
        subStepped.update(0.03, input, true);
        var stepSnap = subStepped.update(0.1, input, true);

        assertEquals(3, jumpSnap.particles().size());
        assertEquals(3, stepSnap.particles().size());
        for (int i = 0; i < 3; i++) {
            assertEquals(stepSnap.particles().get(i).born(), jumpSnap.particles().get(i).born(), 1e-6,
                    "Particle " + i + " birth time diverged across step sizes");
            assertEquals(stepSnap.particles().get(i).motion().position().z,
                    jumpSnap.particles().get(i).motion().position().z, 1e-6,
                    "Particle " + i + " position diverged across step sizes");
        }
    }

    @Test
    void acceptsRingBufferedSubEmittersAndLowQuality2DCollision() {
        String json = """
                {
                  "format_version": 1,
                  "materials": { "mat": {} },
                  "emitters": [
                    {
                      "name": "parent",
                      "renderer": "sprite",
                      "material": "mat",
                      "local_space": true,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 4,
                      "rate": 0,
                      "bursts": [ { "time": 0.0, "min": 1, "max": 1 } ],
                      "lifetime": 0.5,
                      "start_speed": 1.0,
                      "start_direction": [0, 0, 1],
                      "unity_renderer": {
                        "sub_emitters": [
                          {
                            "target": "child",
                            "event": "birth",
                            "probability": 1.0,
                            "inherit": 1
                          }
                        ]
                      }
                    },
                    {
                      "name": "child",
                      "renderer": "sprite",
                      "material": "mat",
                      "local_space": false,
                      "duration": 2.0,
                      "loop": false,
                      "max_particles": 8,
                      "rate": 0,
                      "bursts": [ { "time": 0.0, "min": 2, "max": 2 } ],
                      "lifetime": 1.0,
                      "start_speed": 0.0,
                      "start_direction": [0, 0, 1],
                      "unity_renderer": {
                        "pause_until_replaced": true,
                        "world_collision": {
                          "dynamic_colliders": true,
                          "max_dynamic_shapes": 16,
                          "layer_mask": 7,
                          "quality": "low",
                          "mode_2d": true,
                          "dampen": 0.1,
                          "bounce": 0.8
                        }
                      }
                    }
                  ]
                }
                """;
        EffectDefinition def = parse(json);
        assertEquals(2, def.emitters().size());
        var childBinding = def.unityRenderers().get("child");
        assertTrue(childBinding.pauseUntilReplaced());
        assertEquals(com.kltyton.lumenfx.effect.unity.UnityCollision.Quality.LOW, childBinding.collision().world().quality());
        assertTrue(childBinding.collision().world().mode2d());

        var handle = new EffectHandle(Vec3.ZERO);
        var instance = new EffectInstance(def, handle, 77L);
        var builder = new EffectMeshBuilder(0);
        builder.begin(Vec3.ZERO, new org.joml.Quaternionf());
        instance.beginSimulationFrame(0.0);
        for (var f : instance.packPreparedFrame()) f.updateAndPack(builder);
        builder.finish();
        builder.begin(Vec3.ZERO, new org.joml.Quaternionf());
        instance.beginSimulationFrame(0.05);
        for (var f : instance.packPreparedFrame()) f.updateAndPack(builder);
        var meshes = builder.finish();
        assertEquals(2, meshes.size());
        assertEquals(2, meshes.get(1).instanceCount());

        // #4a: Real runtime 2D mode behavior - collides in XY while passing Z motion cleanly without Z obstruction.
        var mode2dSettings = new com.kltyton.lumenfx.effect.unity.UnityCollision(
                List.of(),
                new com.kltyton.lumenfx.effect.unity.UnityCollision.World(false, 4, -1,
                        com.kltyton.lumenfx.effect.unity.UnityCollision.Quality.HIGH, true),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.0), com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(1.0), com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.0), 0.0, 100.0, 1.0);
        var mode2dContacts = new com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts(mode2dSettings, false);
        // Solid wall at voxel (1, 0, 0) plus Z wall along [0,2]x[0,1]x[1,2]: in 2D mode, X bounces off (1,0,0) and Z passes Z=[1,2] unobstructed!
        com.lowdragmc.photon.client.collision.BlockBoxSource wallAndFloor = (minX, minY, minZ, maxX, maxY, maxZ, mask, filter, out) -> {
            boolean added = false;
            if (maxX > 1.0 && minX < 2.0 && maxY > 0.0 && minY < 1.0 && maxZ > 0.0 && minZ < 1.0) {
                out.add(1.0, 0.0, 0.0, 2.0, 1.0, 1.0, com.lowdragmc.photon.client.collision.BoxBuffer.BLOCK, 1L, null);
                added = true;
            }
            if (maxX > 0.0 && minX < 2.0 && maxY > 0.0 && minY < 1.0 && maxZ > 1.0 && minZ < 2.0) {
                out.add(0.0, 0.0, 1.0, 2.0, 1.0, 2.0, com.lowdragmc.photon.client.collision.BoxBuffer.BLOCK, 2L, null);
                added = true;
            }
            return added;
        };
        var identityPose = EffectHandle.Pose.at(Vec3.ZERO);
        mode2dContacts.prepareBlockSource(wallAndFloor, 1, identityPose, 0.0, 0.1);
        var body2d = mode2dContacts.move(0.5, 0.5, 0.5, 1.0, 0.0, 0.8, 10.0, 0.0, 8.0, 0.0, 0.1, 0.1, 0.0, 1.0, 1.0, 0.0, null);
        assertTrue(body2d.touched, "2D mode must collide against XY wall at x=1");
        assertTrue(body2d.svx < 0.0, "2D mode must reflect X velocity off XY wall");
        assertEquals(0.8, body2d.moveZ, 1e-6, "2D mode must preserve Z movement unobstructed past Z=[1,2] blocker");
        assertEquals(8.0, body2d.svz, 1e-6, "2D mode must preserve Z velocity");

        // #4b: Real runtime quality query budget (Low=1 cell/step vs Medium=4 vs High=unthrottled).
        var blockCalls = new int[1];
        com.lowdragmc.photon.client.collision.BlockBoxSource meteredWall = (minX, minY, minZ, maxX, maxY, maxZ, mask, filter, out) -> {
            blockCalls[0]++;
            return false;
        };
        var lowSettings = new com.kltyton.lumenfx.effect.unity.UnityCollision(
                List.of(),
                new com.kltyton.lumenfx.effect.unity.UnityCollision.World(false, 4, -1,
                        com.kltyton.lumenfx.effect.unity.UnityCollision.Quality.LOW, false),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.0), com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(1.0), com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.0), 0.0, 100.0, 1.0);
        var lowContacts = new com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts(lowSettings, false);
        lowContacts.prepareBlockSource(meteredWall, 1, identityPose, 0.0, 0.1);
        // Moving across 5 distinct voxels (0..4) in a single Low step should throttle uncached voxel queries to 1.
        lowContacts.move(0.5, 0.5, 0.5, 4.0, 0.0, 0.0, 40.0, 0.0, 0.0, 0.0, 0.1, 0.1, 0.0, 1.0, 1.0, 0.0, null);
        assertEquals(1, blockCalls[0], "Low quality must cap uncached static voxel lookups per frame to 1");

        // #5: Cross-instance shared ring buffer capacity across 2 parent births (2 births * 2 children = 4, shared cap = 3).
        String sharedRingJson = """
                {
                  "format_version": 1,
                  "materials": { "mat": {} },
                  "emitters": [
                    {
                      "name": "parent_two",
                      "renderer": "sprite",
                      "material": "mat",
                      "local_space": true,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 4,
                      "rate": 0,
                      "bursts": [ { "time": 0.0, "min": 1, "max": 1 }, { "time": 0.02, "min": 1, "max": 1 } ],
                      "lifetime": 1.0,
                      "start_speed": 0.0,
                      "start_direction": [0, 0, 1],
                      "unity_renderer": {
                        "sub_emitters": [
                          { "target": "shared_ring_child", "event": "birth", "probability": 1.0, "inherit": 0 }
                        ]
                      }
                    },
                    {
                      "name": "shared_ring_child",
                      "renderer": "sprite",
                      "material": "mat",
                      "local_space": false,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 3,
                      "rate": 0,
                      "bursts": [ { "time": 0.0, "min": 2, "max": 2 } ],
                      "lifetime": 5.0,
                      "start_speed": 0.0,
                      "start_direction": [0, 0, 1],
                      "unity_renderer": {
                        "pause_until_replaced": true
                      }
                    }
                  ]
                }
                """;
        EffectDefinition sharedRingDef = parse(sharedRingJson);
        var ringHandle = new EffectHandle(Vec3.ZERO);
        var ringInstance = new EffectInstance(sharedRingDef, ringHandle, 91L);
        builder.begin(Vec3.ZERO, new org.joml.Quaternionf());
        ringInstance.beginSimulationFrame(0.0);
        for (var f : ringInstance.packPreparedFrame()) f.updateAndPack(builder);
        builder.finish();
        builder.begin(Vec3.ZERO, new org.joml.Quaternionf());
        ringInstance.beginSimulationFrame(0.05);
        for (var f : ringInstance.packPreparedFrame()) f.updateAndPack(builder);
        var ringMeshes = builder.finish();
        int totalRingChildInstances = 0;
        for (int i = 1; i < ringMeshes.size(); i++) totalRingChildInstances += ringMeshes.get(i).instanceCount();
        assertEquals(3, totalRingChildInstances,
                "Expected shared Pause Until Replaced capacity=3 across sibling child instances");

        // #1: MEDIUM spatial entity cache must NOT let particle B overwrite particle A's entities on even steps.
        var entityCollectCalls = new int[1];
        var ent1 = new com.lowdragmc.photon.client.collision.EntityTarget("e1", 1, 1.0, 0.0, 0.0, 2.0, 1.0, 1.0, 0, 0, 0, false);
        var ent2 = new com.lowdragmc.photon.client.collision.EntityTarget("e2", 2, 101.0, 0.0, 0.0, 102.0, 1.0, 1.0, 0, 0, 0, false);
        com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts.EntityBoxSource spatialEntities = (bounds, max, now, s) -> {
            entityCollectCalls[0]++;
            var out = new java.util.ArrayList<com.lowdragmc.photon.client.collision.EntityTarget>();
            if (bounds.intersects(ent1.minX(), ent1.minY(), ent1.minZ(), ent1.maxX(), ent1.maxY(), ent1.maxZ())) out.add(ent1);
            if (bounds.intersects(ent2.minX(), ent2.minY(), ent2.minZ(), ent2.maxX(), ent2.maxY(), ent2.maxZ())) out.add(ent2);
            return out.toArray(com.lowdragmc.photon.client.collision.EntityTarget[]::new);
        };
        var mediumEntitySettings = new com.kltyton.lumenfx.effect.unity.UnityCollision(
                List.of(),
                new com.kltyton.lumenfx.effect.unity.UnityCollision.World(true, 4, -1,
                        com.kltyton.lumenfx.effect.unity.UnityCollision.Quality.MEDIUM, com.kltyton.lumenfx.effect.unity.UnityCollision.Plane2D.NONE),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.0),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(1.0),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.0), 0.0, 100.0, 1.0);
        var mediumContacts = new com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts(mediumEntitySettings, false);
        mediumContacts.prepareSources(null, spatialEntities, 1, identityPose, 0.0, 0.1,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY);
        // Odd step 1: Particle A at x=0.5, Particle B at x=100.5 in separate cells.
        assertTrue(mediumContacts.move(0.5, 0.5, 0.5, 1.0, 0.0, 0.0, 10.0, 0.0, 0.0, 0.0, 0.1, 0.1, 0.0, 1.0, 1.0, 0.0, null).touched);
        assertTrue(mediumContacts.move(100.5, 0.5, 0.5, 1.0, 0.0, 0.0, 10.0, 0.0, 0.0, 0.0, 0.1, 0.1, 0.0, 1.0, 1.0, 0.0, null).touched);
        int oddCalls = entityCollectCalls[0];
        assertTrue(oddCalls >= 2);
        // Even step 2: MUST reuse both spatial cells without querying again and MUST still hit e1 for particle A!
        mediumContacts.prepareSources(null, spatialEntities, 1, identityPose, 0.1, 0.2,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY);
        var evenA = mediumContacts.move(0.5, 0.5, 0.5, 1.0, 0.0, 0.0, 10.0, 0.0, 0.0, 0.1, 0.1, 0.1, 0.1, 0.9, 1.0, 0.0, null);
        var evenB = mediumContacts.move(100.5, 0.5, 0.5, 1.0, 0.0, 0.0, 10.0, 0.0, 0.0, 0.1, 0.1, 0.1, 0.1, 0.9, 1.0, 0.0, null);
        assertEquals(oddCalls, entityCollectCalls[0], "Even step should reuse cached spatial entity cells");
        assertTrue(evenA.touched, "Particle A must still hit entity 1 on even step despite Particle B in another cell");
        assertTrue(evenB.touched, "Particle B must still hit entity 2 on even step");

        // #2: Dynamic entities in mode_2d MUST NOT revert sphericalBody=true (which expands thin Z slice to 3D radius!).
        var xyEnt = new com.lowdragmc.photon.client.collision.EntityTarget("xy", 10, 1.0, 0.0, 0.0, 2.0, 1.0, 1.0, 0, 0, 0, false);
        var zOffsetEnt = new com.lowdragmc.photon.client.collision.EntityTarget("zOff", 11, 0.0, 0.0, 0.55, 2.0, 1.0, 1.5, 0, 0, 0, false);
        com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts.EntityBoxSource entities2d = (b, max, now, s) ->
                new com.lowdragmc.photon.client.collision.EntityTarget[] { xyEnt, zOffsetEnt };
        var dynamic2dSettings = new com.kltyton.lumenfx.effect.unity.UnityCollision(
                List.of(),
                new com.kltyton.lumenfx.effect.unity.UnityCollision.World(true, 4, -1,
                        com.kltyton.lumenfx.effect.unity.UnityCollision.Quality.HIGH, com.kltyton.lumenfx.effect.unity.UnityCollision.Plane2D.XY),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.0),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(1.0),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.0), 0.0, 100.0, 1.0);
        var dynamic2dContacts = new com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts(dynamic2dSettings, false);
        dynamic2dContacts.prepareSources(null, entities2d, 1, identityPose, 0.0, 0.1,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY);
        // Particle at z=0.5, radius=0.1, dz=0.8: if sphericalBody were true, radius 0.1 would hit zOffsetEnt at z=0.55!
        var dyn2d = dynamic2dContacts.move(0.5, 0.5, 0.5, 1.0, 0.0, 0.8, 10.0, 0.0, 8.0, 0.0, 0.1, 0.1, 0.0, 1.0, 1.0, 0.0, null);
        assertTrue(dyn2d.touched, "Dynamic 2D mode must collide with XY dynamic entity at x=1");
        assertTrue(dyn2d.svx < 0.0, "Dynamic 2D mode must reflect X velocity off XY entity");
        assertEquals(0.8, dyn2d.moveZ, 1e-6, "Dynamic 2D mode must keep Z free past offset Z entity");

        // #3: MEDIUM voxelCache must invalidate when layer inputs or tag generation change even if worldVersion stays identical.
        var layerWallCalls = new int[1];
        com.lowdragmc.photon.client.collision.BlockBoxSource layerWall = (minX, minY, minZ, maxX, maxY, maxZ, mask, filter, out) -> {
            layerWallCalls[0]++;
            return true;
        };
        var layerSetA = new com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers(Map.of(0,
                new com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.Layer(List.of("minecraft:dirt"), List.of())));
        var layerSetB = new com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers(Map.of(0,
                new com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.Layer(List.of("minecraft:stone"), List.of())));
        var layerSettings = new com.kltyton.lumenfx.effect.unity.UnityCollision(
                List.of(),
                new com.kltyton.lumenfx.effect.unity.UnityCollision.World(false, 4, 1,
                        com.kltyton.lumenfx.effect.unity.UnityCollision.Quality.MEDIUM, false),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.0),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.0),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.0), 0.0, 100.0, 1.0);
        var layerContacts = new com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts(layerSettings, false);
        java.util.function.Function<List<String>, com.lowdragmc.photon.client.collision.BlockFilter> noopFilter = b -> null;
        layerContacts.prepareSources(layerWall, null, 7, identityPose, 0.0, 0.1, layerSetA, noopFilter);
        layerContacts.move(0.25, 0.25, 0.25, 0.1, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.1, 0.02, 0.0, 1.0, 1.0, 0.0, null);
        int cachedCalls = layerWallCalls[0];
        assertTrue(cachedCalls > 0 && cachedCalls <= 4);
        // Unchanged layerSetA & worldVersion=7 -> must hit voxelCache without calling layerWall again.
        layerContacts.prepareSources(layerWall, null, 7, identityPose, 0.05, 0.15, layerSetA, noopFilter);
        layerContacts.move(0.25, 0.25, 0.25, 0.1, 0.0, 0.0, 1.0, 0.0, 0.0, 0.05, 0.1, 0.02, 0.05, 0.95, 1.0, 0.0, null);
        assertEquals(cachedCalls, layerWallCalls[0], "Unchanged layer + worldVersion should reuse voxelCache");
        // Same worldVersion=7, but layer inputs changed -> must clear voxelCache!
        int beforeRequery = layerWallCalls[0];
        layerContacts.prepareSources(layerWall, null, 7, identityPose, 0.1, 0.2, layerSetB, noopFilter);
        layerContacts.move(0.25, 0.25, 0.25, 0.1, 0.0, 0.0, 1.0, 0.0, 0.0, 0.1, 0.1, 0.02, 0.1, 0.9, 1.0, 0.0, null);
        assertTrue(layerWallCalls[0] > beforeRequery, "Layer input change must invalidate voxelCache with unchanged worldVersion");
        // Tag generation bump with same layer object & same worldVersion must also invalidate voxelCache!
        int beforeTagChange = layerWallCalls[0];
        com.lowdragmc.photon.client.collision.BlockFilter.tagsChanged();
        layerContacts.prepareSources(layerWall, null, 7, identityPose, 0.2, 0.3, layerSetB, noopFilter);
        layerContacts.move(0.25, 0.25, 0.25, 0.1, 0.0, 0.0, 1.0, 0.0, 0.0, 0.2, 0.1, 0.02, 0.2, 0.8, 1.0, 0.0, null);
        assertTrue(layerWallCalls[0] > beforeTagChange, "Tag generation change must invalidate voxelCache");

        // #4: ConeVolume + Collision2D maps Unity XY to host XZ (free axis is Y, NOT Z!).
        String cone2dJson = """
                {
                  "format_version": 1,
                  "materials": { "mat": {} },
                  "emitters": [
                    {
                      "name": "cone2d",
                      "renderer": "sprite",
                      "material": "mat",
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 4,
                      "rate": 0,
                      "lifetime": 1.0,
                      "start_speed": 0,
                      "start_direction": [0, 0, 1],
                      "unity_renderer": {
                        "world_collision": {
                          "dynamic_colliders": true,
                          "max_dynamic_shapes": 8,
                          "layer_mask": -1,
                          "quality": "high",
                          "mode_2d": true,
                          "forward_to_up": true,
                          "plane_2d": "xz"
                        }
                      }
                    }
                  ]
                }
                """;
        EffectDefinition cone2dDef = parse(cone2dJson);
        var coneCollision = cone2dDef.unityRenderers().get("cone2d").collision();
        assertEquals(com.kltyton.lumenfx.effect.unity.UnityCollision.Plane2D.XZ, coneCollision.world().plane2d());
        var xzContacts = new com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts(coneCollision, false);
        com.lowdragmc.photon.client.collision.BlockBoxSource xzWall = (minX, minY, minZ, maxX, maxY, maxZ, mask, filter, out) -> {
            if (maxZ > 1.0 && minZ < 2.0 && maxX > 0.0 && minX < 1.0 && maxY > 0.0 && minY < 1.0) {
                out.add(0.0, 0.0, 1.0, 1.0, 1.0, 2.0, com.lowdragmc.photon.client.collision.BoxBuffer.BLOCK, 3L, null);
                return true;
            }
            return false;
        };
        xzContacts.prepareBlockSource(xzWall, 1, identityPose, 0.0, 0.1);
        var xzHit = xzContacts.move(0.5, 0.5, 0.5, 0.0, 0.8, 1.0, 0.0, 8.0, 10.0, 0.0, 0.1, 0.1, 0.0, 1.0, 1.0, 0.0, null);
        assertTrue(xzHit.touched, "ConeVolume Plane2D.XZ must collide against Z wall (mapped from Unity Y)");
        assertTrue(xzHit.svz < 0.0, "ConeVolume Plane2D.XZ must reflect Z velocity");
        assertEquals(0.8, xzHit.moveY, 1e-6, "ConeVolume Plane2D.XZ free axis is Y, keeping Y displacement");
        assertEquals(8.0, xzHit.svy, 1e-6, "ConeVolume Plane2D.XZ free axis is Y, keeping Y velocity");

        // #5 exact counterexample: cap=1, host 0 (delay 0.04 -> child born 0.04) updated BEFORE host 0.02 (delay 0.0 -> child born 0.02)!
        String counterexampleRingJson = """
                {
                  "format_version": 1,
                  "materials": { "mat": {} },
                  "emitters": [
                    {
                      "name": "parent_ce",
                      "renderer": "sprite",
                      "material": "mat",
                      "local_space": true,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 4,
                      "rate": 0,
                      "bursts": [ { "time": 0.0, "min": 1, "max": 1 }, { "time": 0.02, "min": 1, "max": 1 } ],
                      "lifetime": 1.0,
                      "start_speed": 0.0,
                      "start_direction": [0, 0, 1],
                      "unity_renderer": {
                        "sub_emitters": [
                          { "target": "child_first", "event": "birth", "probability": 1.0, "inherit": 0 }
                        ]
                      }
                    },
                    {
                      "name": "child_first",
                      "renderer": "sprite",
                      "material": "mat",
                      "local_space": false,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 1,
                      "rate": 0,
                      "bursts": [ { "time": 0.0, "min": 1, "max": 1 } ],
                      "lifetime": 5.0,
                      "start_speed": 0.0,
                      "start_direction": [0, 0, 1],
                      "unity_renderer": {
                        "pause_until_replaced": true,
                        "sub_emitters": [
                          { "target": "obs", "event": "birth", "probability": 1.0, "inherit": 0 },
                          { "target": "obs", "event": "death", "probability": 1.0, "inherit": 0 }
                        ]
                      }
                    },
                    {
                      "name": "obs",
                      "renderer": "sprite",
                      "material": "mat",
                      "local_space": false,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 1,
                      "rate": 0,
                      "lifetime": 1.0,
                      "start_speed": 0.0,
                      "start_direction": [0, 0, 1],
                      "unity_renderer": {}
                    }
                  ]
                }
                """;
        EffectDefinition ceDef = parse(counterexampleRingJson);
        var ceCap = new UnityEmitterCapacity(1);
        // Simulate exact order where child born at host 0.04 inserts FIRST, then child born at host 0.02 inserts SECOND!
        EmitterDefinition childDef = ceDef.emitters().get(1);
        var ceChildBinding = ceDef.unityRenderers().get("child_first");
        var evAt0 = new com.kltyton.lumenfx.effect.simulation.unity.UnitySubEmitterEvent(
                new com.kltyton.lumenfx.effect.unity.UnitySubEmitter("child_first", com.kltyton.lumenfx.effect.unity.UnitySubEmitter.Event.BIRTH, 1.0, 0),
                0.04, identityPose, Vec3.ZERO, null, null, null, null, 1.0, 0.0);
        var evAt02 = new com.kltyton.lumenfx.effect.simulation.unity.UnitySubEmitterEvent(
                new com.kltyton.lumenfx.effect.unity.UnitySubEmitter("child_first", com.kltyton.lumenfx.effect.unity.UnitySubEmitter.Event.BIRTH, 1.0, 0),
                0.02, identityPose, Vec3.ZERO, null, null, null, null, 1.0, 0.0);
        var cLate = new EmitterInstance(childDef, 10L, null, ceChildBinding, evAt0, ceCap);
        var cEarly = new EmitterInstance(childDef, 11L, null, ceChildBinding, evAt02, ceCap);
        var snapLate = cLate.update(0.05, ringHandle.input(), true, true, Vec3.ZERO, null, null,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY, Map.of());
        var snapEarly = cEarly.update(0.05, ringHandle.input(), true, true, Vec3.ZERO, null, null,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY, Map.of());
        cLate.syncRingSnapshot(); cLate.flushPendingRingEvents();
        cEarly.syncRingSnapshot(); cEarly.flushPendingRingEvents();
        assertEquals(1, snapLate.particles().size(), "Newer host 0.04 particle must remain in ring");
        assertEquals(0, snapEarly.particles().size(), "Evicted self-oldest host 0.02 particle must NOT stay in ring");
        var earlyEvents = cEarly.subEmitterEvents();
        assertEquals(2, earlyEvents.size(), "Evicted child must fire BIRTH then DEATH");
        assertEquals(com.kltyton.lumenfx.effect.unity.UnitySubEmitter.Event.BIRTH, earlyEvents.get(0).link().event());
        assertEquals(com.kltyton.lumenfx.effect.unity.UnitySubEmitter.Event.DEATH, earlyEvents.get(1).link().event());
        assertEquals(0.02, earlyEvents.get(0).time(), 1e-6);
        assertEquals(0.04, earlyEvents.get(1).time(), 1e-6);
    }

    @Test
    void lowQualityBudgetDoesNotFreezeLoadedAirMediumCellCacheDoesNotTruncateEdgeEntityAndEffectInstanceOrdersSharedRingGlobally() {
        // #1: LOW quality exhausted voxel budget MUST still move through loaded air (never fabricate readable=false)!
        var lowCollision = new com.kltyton.lumenfx.effect.unity.UnityCollision(
                List.of(),
                new com.kltyton.lumenfx.effect.unity.UnityCollision.World(false, 4, -1,
                        com.kltyton.lumenfx.effect.unity.UnityCollision.Quality.LOW,
                        com.kltyton.lumenfx.effect.unity.UnityCollision.Plane2D.NONE),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.0),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(1.0),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.0), 0.0, 100.0, 1.0);
        int[] blockProbeCount = { 0 };
        com.lowdragmc.photon.client.collision.BlockBoxSource meteredAirSource = (minX, minY, minZ, maxX, maxY, maxZ, mask, filter, out) -> {
            blockProbeCount[0]++;
            return true; // Loaded empty air
        };
        var lowContacts = new com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts(lowCollision, true);
        var airPose = EffectHandle.Pose.at(new Vec3(0.5, 0.5, 0.5));
        lowContacts.prepareSources(meteredAirSource, null, 1, airPose, 0.0, 1.0,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY);
        var movedAir = lowContacts.move(0.5, 0.5, 0.5, 4.0, 0.0, 0.0, 4.0, 0.0, 0.0,
                1.0, 1.0, 0.1, 1.0, 4.0, 5.0, 0.5, null);
        assertEquals(1, blockProbeCount[0], "LOW quality should cap uncached voxel probes to 1 per frame");
        assertEquals(4.0, movedAir.moveX, 1e-4, "Exhausting LOW voxel budget in loaded air must NOT freeze particle displacement");

        // #2: MEDIUM entityCellCache must not truncate cell entities around cell center before particle bounds filtering!
        var medCollision = new com.kltyton.lumenfx.effect.unity.UnityCollision(
                List.of(),
                new com.kltyton.lumenfx.effect.unity.UnityCollision.World(true, 4, -1,
                        com.kltyton.lumenfx.effect.unity.UnityCollision.Quality.MEDIUM,
                        com.kltyton.lumenfx.effect.unity.UnityCollision.Plane2D.NONE),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.0),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(1.0),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.0), 0.0, 100.0, 1.0);
        // 2 irrelevant entities near cell center (8,8,8), plus Enear on cell edge (0.55, 0.0, 0.0).
        // MEDIUM sets maxContacts=2 -> querying cell with maximum=2 sorted by cellBounds.getCenter() would drop Enear!
        var eCenter1 = new com.lowdragmc.photon.client.collision.EntityTarget("c1", 1, 7.5, 7.5, 7.5, 8.5, 8.5, 8.5, 0, 0, 0, false);
        var eCenter2 = new com.lowdragmc.photon.client.collision.EntityTarget("c2", 2, 7.6, 7.6, 7.6, 8.6, 8.6, 8.6, 0, 0, 0, false);
        var eNear = new com.lowdragmc.photon.client.collision.EntityTarget("near", 3, 0.55, 0.0, 0.0, 0.95, 1.0, 1.0, 0, 0, 0, false);
        com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts.EntityBoxSource cellCenterSortedSource = (bounds, maximum, time, speed) -> {
            var all = new ArrayList<com.lowdragmc.photon.client.collision.EntityTarget>(List.of(eCenter1, eCenter2, eNear));
            var c = bounds.getCenter();
            all.sort(java.util.Comparator.comparingDouble(t -> {
                double dx = (t.minX() + t.maxX()) * 0.5 - c.x;
                double dy = (t.minY() + t.maxY()) * 0.5 - c.y;
                double dz = (t.minZ() + t.maxZ()) * 0.5 - c.z;
                return dx * dx + dy * dy + dz * dz;
            }));
            return all.subList(0, Math.min(maximum, all.size()))
                    .toArray(com.lowdragmc.photon.client.collision.EntityTarget[]::new);
        };
        var medContacts = new com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts(medCollision, false);
        medContacts.prepareSources(null, cellCenterSortedSource, 1, airPose, 0.0, 1.0,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY);
        var medHit = medContacts.move(0.1, 0.5, 0.5, 0.9, 0.0, 0.0, 0.9, 0.0, 0.0, 1.0, 1.0, 0.05, 1.0, 4.0, 5.0, 0.5, null);
        assertTrue(medHit.touched, "Edge entity inside particle sweep must NOT be lost due to cell-center truncation");
        assertTrue(medHit.svx < 0.0, "Edge entity collision must deflect particle velocity");

        // #3: Two sibling sub-emitter instances sharing PauseUntilReplaced ring capacity=1 in EffectInstance
        // Sibling A emits at host 0.00 AND host 0.04; Sibling B emits at host 0.02 -> stepping EffectInstance to 0.05
        // must chronologically order: A0(0.00) -> evicted by B0(0.02) -> evicted by A1(0.04), so obs receives 2 DEATHs!
        String sharedEffectJson = """
                {
                  "format_version": 1,
                  "materials": { "mat_parent": {}, "mat_ring": {}, "mat_death": {} },
                  "emitters": [
                    {
                      "name": "parent",
                      "renderer": "sprite",
                      "material": "mat_parent",
                      "local_space": false,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 4,
                      "rate": 0,
                      "bursts": [
                        { "time": 0.00, "min": 1, "max": 1 },
                        { "time": 0.02, "min": 1, "max": 1 }
                      ],
                      "lifetime": 5.0,
                      "start_speed": 0.0,
                      "start_direction": [0, 0, 1],
                      "unity_renderer": {
                        "sub_emitters": [
                          { "target": "child_ring", "event": "birth", "probability": 1.0, "inherit": 0 }
                        ]
                      }
                    },
                    {
                      "name": "child_ring",
                      "renderer": "sprite",
                      "material": "mat_ring",
                      "local_space": false,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 1,
                      "rate": 0,
                      "bursts": [
                        { "time": 0.00, "min": 1, "max": 1 },
                        { "time": 0.04, "min": 1, "max": 1 }
                      ],
                      "lifetime": 5.0,
                      "start_speed": 0.0,
                      "start_direction": [0, 0, 1],
                      "unity_renderer": {
                        "pause_until_replaced": true,
                        "sub_emitters": [
                          { "target": "death_counter", "event": "death", "probability": 1.0, "inherit": 0 }
                        ]
                      }
                    },
                    {
                      "name": "death_counter",
                      "renderer": "sprite",
                      "material": "mat_death",
                      "local_space": false,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 8,
                      "rate": 0,
                      "bursts": [ { "time": 0.0, "min": 1, "max": 1 } ],
                      "lifetime": 5.0,
                      "start_speed": 0.0,
                      "start_direction": [0, 0, 1],
                      "unity_renderer": {}
                    }
                  ]
                }
                """;
        EffectDefinition fxDef = parse(sharedEffectJson);
        EffectHandle fxHandle = new EffectHandle(Vec3.ZERO);
        EffectInstance fxInst = new EffectInstance(fxDef, fxHandle, 123L);
        var builder = new EffectMeshBuilder(0);
        builder.begin(Vec3.ZERO, new org.joml.Quaternionf());
        fxInst.beginSimulationFrame(0.0);
        for (var f : fxInst.packPreparedFrame()) f.updateAndPack(builder);
        builder.finish();
        builder.begin(Vec3.ZERO, new org.joml.Quaternionf());
        fxInst.beginSimulationFrame(0.05);
        for (var f : fxInst.packPreparedFrame()) f.updateAndPack(builder);
        var fxMeshes = builder.finish();
        int ringParticles = 0;
        int deathCounterParticles = 0;
        var matRing = fxDef.materials().get("mat_ring");
        var matDeath = fxDef.materials().get("mat_death");
        for (var mesh : fxMeshes) {
            if (matRing.equals(mesh.key().material())) ringParticles += mesh.instanceCount();
            if (matDeath.equals(mesh.key().material())) deathCounterParticles += mesh.instanceCount();
        }
        assertEquals(1, ringParticles, "Only 1 particle (A1@0.04) should survive in PauseUntilReplaced ring(1)");
        assertEquals(2, deathCounterParticles, "Both chronological ring evictions (A0@0.02 and B0@0.04) must trigger death_counter");
    }

    @Test
    void enforcesChronologicalDeathBeforeReplacementHonorsVoxelSizeAndPublishesColliderForces() {
        // #1: Official Unity PauseUntilReplaced retains natural-end particles paused at age=1 until real replacement (or drain/collision kill)
        String pauseUntilReplacedJson = """
                {
                  "format_version": 1,
                  "materials": { "mat_child": {}, "mat_obs": {} },
                  "emitters": [
                    {
                      "name": "child",
                      "renderer": "sprite",
                      "material": "mat_child",
                      "local_space": false,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 1,
                      "rate": 0,
                      "bursts": [
                        { "time": 0.00, "min": 1, "max": 1 },
                        { "time": 0.04, "min": 1, "max": 1 }
                      ],
                      "lifetime": 0.02,
                      "start_speed": 0.0,
                      "start_direction": [0, 0, 1],
                      "unity_renderer": {
                        "pause_until_replaced": true,
                        "world_collision": {
                          "dynamic_colliders": false,
                          "max_dynamic_shapes": 4,
                          "layer_mask": -1,
                          "quality": "high",
                          "lifetime_loss": 1.0
                        },
                        "sub_emitters": [
                          { "target": "obs", "event": "death", "probability": 1.0, "inherit": 0 }
                        ]
                      }
                    },
                    {
                      "name": "obs",
                      "renderer": "sprite",
                      "material": "mat_obs",
                      "local_space": false,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 4,
                      "rate": 0,
                      "bursts": [ { "time": 0.0, "min": 1, "max": 1 } ],
                      "lifetime": 5.0,
                      "start_speed": 0.0,
                      "start_direction": [0, 1, 0],
                      "unity_renderer": {}
                    }
                  ]
                }
                """;
        EffectDefinition dbrDef = parse(pauseUntilReplacedJson);
        EmitterDefinition childDef = dbrDef.emitters().get(0);
        var emitter = new EmitterInstance(childDef, 42L, null, dbrDef.unityRenderers().get("child"),
                null, new UnityEmitterCapacity(1));
        var handle = new EffectHandle(Vec3.ZERO);
        emitter.update(0.0, handle.input(), true, true, Vec3.ZERO, null, null,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY, Map.of());
        // At t=0.03 (past natural lifetime 0.02, BEFORE 0.04 replacement), PauseUntilReplaced must pause A in ring without firing DEATH!
        var midSnap = emitter.update(0.03, handle.input(), true, true, Vec3.ZERO, null, null,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY, Map.of());
        emitter.syncRingSnapshot();
        emitter.flushPendingRingEvents();
        assertEquals(1, midSnap.particles().size(), "PauseUntilReplaced must keep expired particle paused in ring until replacement");
        assertEquals(1.0, midSnap.particles().get(0).normalizedAge(0.03), 1e-6);
        assertEquals(0, emitter.subEmitterEvents().size(), "Paused particle must not fire DEATH before replacement when no collision occurs");

        // At t=0.05 (after 0.04 replacement), B evicts A at real replacement time 0.04 -> DEATH fires at 0.04!
        emitter.update(0.05, handle.input(), true, true, Vec3.ZERO, null, null,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY, Map.of());
        emitter.syncRingSnapshot();
        emitter.flushPendingRingEvents();
        var deathEvents = emitter.subEmitterEvents();
        assertEquals(1, deathEvents.size(), "Replacement at 0.04 must evict paused particle A");
        assertEquals(0.04, deathEvents.get(0).time(), 1e-6, "Natural PauseUntilReplaced DEATH must fire at replacement time 0.04, not natural lifetime 0.02");

        // drainRing release via removeExpiredFrom must emit DEATH at release time (previousTime = 0.03), NOT backdate to natural lifetime 0.02
        var drainEmitter = new EmitterInstance(childDef, 42L, null, dbrDef.unityRenderers().get("child"),
                null, new UnityEmitterCapacity(1));
        drainEmitter.update(0.0, handle.input(), true, true, Vec3.ZERO, null, null,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY, Map.of());
        drainEmitter.update(0.03, handle.input(), true, true, Vec3.ZERO, null, null,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY, Map.of());
        drainEmitter.drainRingOnStop(true);
        drainEmitter.update(0.05, handle.input(), false, true, Vec3.ZERO, null, null,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY, Map.of());
        drainEmitter.syncRingSnapshot();
        drainEmitter.flushPendingRingEvents();
        var drainDeathEvents = drainEmitter.subEmitterEvents();
        assertEquals(1, drainDeathEvents.size(), "draining PauseUntilReplaced ring must evict paused particle A");
        assertEquals(0.03, drainDeathEvents.get(0).time(), 1e-6,
                "drainRing release via removeExpiredFrom must emit DEATH at release time 0.03, not natural lifetime 0.02");

        // Early collision death at ~0.015 (before natural lifetime and before 0.04 replacement) via plane_collision must evict & fire DEATH immediately
        String planeKillJson = """
                {
                  "format_version": 1,
                  "materials": { "mat_child": {}, "mat_obs": {} },
                  "emitters": [
                    {
                      "name": "child",
                      "renderer": "sprite",
                      "material": "mat_child",
                      "local_space": false,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 1,
                      "rate": 0,
                      "bursts": [
                        { "time": 0.00, "min": 1, "max": 1 },
                        { "time": 0.04, "min": 1, "max": 1 }
                      ],
                      "lifetime": 5.0,
                      "size": 0.2,
                      "start_speed": 1.0,
                      "start_direction": [0, 0, 1],
                      "unity_renderer": {
                        "pause_until_replaced": true,
                        "plane_collision": {
                          "planes": [ { "point": [0, 0, 0.02], "normal": [0, 0, -1], "slot": 0 } ],
                          "lifetime_loss": 1.0,
                          "radius_scale": 0.01
                        },
                        "sub_emitters": [
                          { "target": "obs", "event": "death", "probability": 1.0, "inherit": 0 }
                        ]
                      }
                    },
                    {
                      "name": "obs",
                      "renderer": "sprite",
                      "material": "mat_obs",
                      "local_space": false,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 4,
                      "rate": 0,
                      "lifetime": 5.0,
                      "start_speed": 0.0,
                      "start_direction": [0, 1, 0],
                      "unity_renderer": {}
                    }
                  ]
                }
                """;
        EffectDefinition pkDef = parse(planeKillJson);
        var collisionRingEmitter = new EmitterInstance(pkDef.emitters().get(0), 43L, null, pkDef.unityRenderers().get("child"),
                null, new UnityEmitterCapacity(1));
        collisionRingEmitter.update(0.0, handle.input(), true, true, Vec3.ZERO, null, null,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY, Map.of());
        collisionRingEmitter.update(0.05, handle.input(), true, true, Vec3.ZERO, null, null,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY, Map.of());
        collisionRingEmitter.syncRingSnapshot();
        collisionRingEmitter.flushPendingRingEvents();
        assertTrue(collisionRingEmitter.subEmitterEvents().stream().anyMatch(e ->
                e.link().event() == com.kltyton.lumenfx.effect.unity.UnitySubEmitter.Event.DEATH && e.time() < 0.03),
                "Early collision kill inside PauseUntilReplaced ring must still emit DEATH immediately at collision timestamp");

        // ParticleState.recycleFromRing: natural pause updates ringEndTime to replacement (0.04) keeping frozen deathTime (0.02),
        // while early expireAt (0.015) preserves earlier collision deathTime and ringEndTime!
        var naturalPaused = new ParticleState(Vec3.ZERO, new Vec3(0, 0, 1), 0.0, 0.02, 1.0, 1.0, 0.0, 0.5, 0L,
                new com.kltyton.lumenfx.effect.curve.ColorFunction.Color(1, 1, 1, 1));
        naturalPaused.retainInRing(0L, null);
        assertEquals(1.0, naturalPaused.normalizedAge(0.03), 1e-6);
        var pauseNoise = new net.minecraft.world.level.levelgen.synth.ImprovedNoise(net.minecraft.util.RandomSource.create(3821));
        assertEquals(0.03, naturalPaused.positionAt(0.03, childDef, pauseNoise).z, 1e-6);
        naturalPaused.recycleFromRing(0.04);
        assertEquals(0.04, naturalPaused.ringEndTime(), 1e-6);
        assertEquals(0.02, naturalPaused.deathTime(), 1e-6);
        assertEquals(0.04, naturalPaused.positionAt(0.06, childDef, pauseNoise).z, 1e-6);
        var killedAfterPause = new ParticleState(Vec3.ZERO, Vec3.ZERO, 0, .02, 1, 1, 0, .5, 0,
                new com.kltyton.lumenfx.effect.curve.ColorFunction.Color(1, 1, 1, 1));
        killedAfterPause.retainInRing(1L, null);
        killedAfterPause.expireAt(0.035);
        assertEquals(0.035, killedAfterPause.ringEndTime(), 1e-6);
        var collisionKilled = new ParticleState(Vec3.ZERO, Vec3.ZERO, 0.0, 0.05, 1.0, 1.0, 0.0, 0.5, 0L,
                new com.kltyton.lumenfx.effect.curve.ColorFunction.Color(1, 1, 1, 1));
        collisionKilled.retainInRing(1L, null);
        collisionKilled.expireAt(0.015);
        collisionKilled.recycleFromRing(0.04);
        assertEquals(0.015, collisionKilled.deathTime(), 1e-6);
        assertEquals(0.015, collisionKilled.ringEndTime(), 1e-6);

        // #2: CollisionModule.voxelSize round-trips through JSON and scales LOW/MEDIUM block voxel probes
        String voxelJson = """
                {
                  "format_version": 1,
                  "materials": { "mat": {} },
                  "emitters": [
                    {
                      "name": "e",
                      "renderer": "sprite",
                      "material": "mat",
                      "local_space": false,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 4,
                      "rate": 0,
                      "lifetime": 1.0,
                      "start_speed": 1.0,
                      "start_direction": [1, 0, 0],
                      "unity_renderer": {
                        "world_collision": {
                          "dynamic_colliders": false,
                          "max_dynamic_shapes": 4,
                          "layer_mask": -1,
                          "quality": "low",
                          "voxel_size": 0.25,
                          "collider_force": 10.0,
                          "multiply_force_by_collision_angle": true,
                          "multiply_force_by_particle_speed": true,
                          "multiply_force_by_particle_size": true,
                          "send_collision_messages": false
                        }
                      }
                    }
                  ]
                }
                """;
        EffectDefinition voxelDef = parse(voxelJson);
        var voxelCollision = voxelDef.unityRenderers().get("e").collision();
        assertEquals(0.25, voxelCollision.world().voxelSize(), 1e-6);
        assertEquals(10.0, voxelCollision.world().colliderForce(), 1e-6);
        assertTrue(voxelCollision.world().multiplyForceByCollisionAngle());
        assertTrue(voxelCollision.world().multiplyForceByParticleSpeed());
        assertTrue(voxelCollision.world().multiplyForceByParticleSize());
        assertFalse(voxelCollision.sendCollisionMessages());

        com.lowdragmc.photon.client.collision.BlockBoxSource narrowWallSource = (minX, minY, minZ, maxX, maxY, maxZ, mask, filter, out) -> {
            if (maxX > 0.6 && minX < 0.7 && maxY > 0.0 && minY < 1.0 && maxZ > 0.0 && minZ < 1.0) {
                out.add(0.6, 0.0, 0.0, 0.7, 1.0, 1.0, com.lowdragmc.photon.client.collision.Contact.BLOCK, 1L, 0L);
            }
            return true;
        };
        var fineLowContacts = new com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts(voxelCollision, true);
        var originPose = EffectHandle.Pose.at(Vec3.ZERO);
        fineLowContacts.prepareSources(narrowWallSource, null, 1, originPose, 0.0, 0.1,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY);
        var fineLowHit = fineLowContacts.move(0.1, 0.5, 0.5, 0.8, 0.0, 0.0, 8.0, 0.0, 0.0,
                0.1, 0.1, 0.05, 0.0, 1.0, 1.0, 0.5, null);
        assertFalse(fineLowHit.touched, "With voxelSize=0.25 and LOW 1-voxel budget, probe at (0.1,0.5,0.5) covers [0..0.25] and must not hit x=[0.6..0.7]");

        var fineMedCollision = new com.kltyton.lumenfx.effect.unity.UnityCollision(
                List.of(),
                new com.kltyton.lumenfx.effect.unity.UnityCollision.World(false, 4, -1,
                        com.kltyton.lumenfx.effect.unity.UnityCollision.Quality.MEDIUM,
                        com.kltyton.lumenfx.effect.unity.UnityCollision.Plane2D.NONE,
                        0.25, 0.0, false, false, false),
                voxelCollision.bounce(), voxelCollision.dampen(), voxelCollision.lifetimeLoss(), 0.0, 100.0, 1.0);
        var fineMedContacts = new com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts(fineMedCollision, true);
        fineMedContacts.prepareSources(narrowWallSource, null, 1, originPose, 0.0, 0.1,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY);
        var fineMedHit = fineMedContacts.move(0.58, 0.375, 0.375, 0.06, 0.0, 0.0, 0.6, 0.0, 0.0,
                0.1, 0.1, 0.02, 0.0, 1.0, 1.0, 0.5, null);
        assertTrue(fineMedHit.touched, "With voxelSize=0.25 in MEDIUM mode, swept voxel (2,2,2)=[0.5..0.75] must detect block at x=[0.6..0.7]");

        // #3: colliderForce + angle/speed/size multipliers produce ForceImpact even with sendCollisionMessages=false
        var forceCollision = new com.kltyton.lumenfx.effect.unity.UnityCollision(
                List.of(),
                new com.kltyton.lumenfx.effect.unity.UnityCollision.World(true, 4, -1,
                        com.kltyton.lumenfx.effect.unity.UnityCollision.Quality.HIGH,
                        com.kltyton.lumenfx.effect.unity.UnityCollision.Plane2D.NONE,
                        0.5, 10.0, true, true, true),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.0),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(1.0),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.0), 0.0, 100.0, 1.0);
        var targetEntity = new com.lowdragmc.photon.client.collision.EntityTarget("target", 77, 0.5, 0.0, 0.0, 1.0, 1.0, 1.0, 0, 0, 0, false);
        com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts.EntityBoxSource entitySrc =
                (bounds, maximum, time, speed) -> new com.lowdragmc.photon.client.collision.EntityTarget[] { targetEntity };
        var forceContacts = new com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts(forceCollision, false);
        forceContacts.prepareSources(null, entitySrc, 1, originPose, 0.0, 0.1,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY);
        List<com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts.Impact> impactsSlow = new ArrayList<>();
        forceContacts.move(0.1, 0.5, 0.5, 0.5, 0.0, 0.0, 5.0, 0.0, 0.0,
                0.1, 0.1, 0.05, 0.0, 1.0, 1.0, 0.5, impactsSlow::add);
        assertEquals(1, impactsSlow.size());
        assertTrue(impactsSlow.get(0).hit() != null && impactsSlow.get(0).hit().appliedForce() != null,
                "Entity collision with colliderForce must compute appliedForce even when sendCollisionMessages=false");
        double slowFx = impactsSlow.get(0).hit().appliedForce().x;
        assertTrue(slowFx > 0.0, "Normal collision along +X must apply positive +X force to entity");

        List<com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts.Impact> impactsFast = new ArrayList<>();
        forceContacts.move(0.1, 0.5, 0.5, 1.0, 0.0, 0.0, 10.0, 0.0, 0.0,
                0.1, 0.1, 0.05, 0.0, 1.0, 1.0, 0.5, impactsFast::add);
        assertEquals(slowFx * 2.0, impactsFast.get(0).hit().appliedForce().x, 1e-4,
                "Doubling particle speed with multiplyForceByParticleSpeed=true must double applied force");

        List<com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts.Impact> impactsLarger = new ArrayList<>();
        forceContacts.move(0.1, 0.5, 0.5, 0.5, 0.0, 0.0, 5.0, 0.0, 0.0,
                0.1, 0.1, 0.10, 0.0, 1.0, 1.0, 0.5, impactsLarger::add);
        assertEquals(slowFx * 2.0, impactsLarger.get(0).hit().appliedForce().x, 1e-4,
                "Doubling particle diameter with multiplyForceByParticleSize=true must double applied force");

        List<com.kltyton.lumenfx.api.client.UnityParticleCollisionEvent.ForceImpact> receivedForces = new ArrayList<>();
        List<com.kltyton.lumenfx.api.client.UnityParticleCollisionEvent> receivedMessages = new ArrayList<>();
        var forceHandle = new EffectHandle(Vec3.ZERO);
        forceHandle.setUnityColliderForceListener(receivedForces::add);
        forceHandle.setUnityCollisionListener(receivedMessages::add);
        var emittedForce = new com.kltyton.lumenfx.api.client.UnityParticleCollisionEvent.ForceImpact(
                "e", 77L, 0.1, impactsSlow.get(0).position(), impactsSlow.get(0).hit().appliedForce());
        forceHandle.publishUnityColliderForces(List.of(emittedForce));
        assertEquals(1, receivedForces.size());
        assertEquals(0, receivedMessages.size());

        // Host-wide VfxEffects.setUnityColliderForceConsumer dispatch contract
        List<com.kltyton.lumenfx.api.client.UnityParticleCollisionEvent.ForceImpact> hostForces = new ArrayList<>();
        com.kltyton.lumenfx.api.client.VfxEffects.setUnityColliderForceConsumer((lvl, f) -> hostForces.add(f));
        try {
            com.kltyton.lumenfx.client.VfxEngine.INSTANCE.dispatchUnityColliderForces(null, List.of(emittedForce));
            assertEquals(1, hostForces.size(), "VfxEngine must forward ForceImpact to registered host colliderForce consumer");
        } finally {
            com.kltyton.lumenfx.api.client.VfxEffects.setUnityColliderForceConsumer(null);
        }

        // #2b: LOW voxel cache preserves real solid block key instead of fabricating voxel-center air BlockPos
        long realSolidBlockKey = net.minecraft.core.BlockPos.asLong(0, 0, 0);
        long fakeAirCenterKey = net.minecraft.core.BlockPos.asLong(1, 1, 1);
        var coarseLowCollision = new com.kltyton.lumenfx.effect.unity.UnityCollision(
                List.of(),
                new com.kltyton.lumenfx.effect.unity.UnityCollision.World(false, 4, -1,
                        com.kltyton.lumenfx.effect.unity.UnityCollision.Quality.LOW,
                        com.kltyton.lumenfx.effect.unity.UnityCollision.Plane2D.NONE,
                        2.0, 0.0, false, false, false),
                voxelCollision.bounce(), voxelCollision.dampen(), voxelCollision.lifetimeLoss(), 0.0, 100.0, 1.0, true);
        com.lowdragmc.photon.client.collision.BlockBoxSource cornerSolidSource = (minX, minY, minZ, maxX, maxY, maxZ, mask, filter, out) -> {
            if (maxX > 0.0 && minX < 1.0 && maxY > 0.0 && minY < 1.0 && maxZ > 0.0 && minZ < 1.0) {
                out.add(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, com.lowdragmc.photon.client.collision.Contact.BLOCK, realSolidBlockKey, 0L);
            }
            return true;
        };
        var coarseLowContacts = new com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts(coarseLowCollision, true);
        coarseLowContacts.prepareSources(cornerSolidSource, null, 1, originPose, 0.0, 0.1,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY);
        List<com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts.Impact> lowImpacts = new ArrayList<>();
        // Step 1 caches voxel (-1,0,0) as empty; Step 2 spends its 1-voxel LOW budget probing voxel (0,0,0)=[0,2)^3!
        coarseLowContacts.move(-0.1, 1.2, 1.2, 0.3, 0.0, 0.0, 3.0, 0.0, 0.0,
                0.0, 0.1, 0.05, 0.0, 1.0, 1.0, 0.5, lowImpacts::add);
        coarseLowContacts.prepareSources(cornerSolidSource, null, 1, originPose, 0.1, 0.2,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY);
        coarseLowContacts.move(-0.1, 1.2, 1.2, 0.3, 0.0, 0.0, 3.0, 0.0, 0.0,
                0.1, 0.1, 0.05, 0.0, 1.0, 1.0, 0.5, lowImpacts::add);
        assertEquals(1, lowImpacts.size());
        assertEquals(realSolidBlockKey, lowImpacts.get(0).collider().key(),
                "LOW voxel collision must report real solid block key rather than voxel-center air BlockPos");
        assertFalse(lowImpacts.get(0).collider().key() == fakeAirCenterKey);

        // #1 (new re-audit): Chronological birth/death & capacity ordering for non-ring emitters
        String chronoJson = """
                {
                  "format_version": 1,
                  "materials": { "m": {} },
                  "emitters": [
                    {
                      "name": "chrono",
                      "renderer": "sprite",
                      "material": "m",
                      "local_space": false,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 1,
                      "rate": 0,
                      "bursts": [
                        { "time": 0.0, "min": 1, "max": 1 },
                        { "time": 0.01, "min": 1, "max": 1 },
                        { "time": 0.02, "min": 1, "max": 1 }
                      ],
                      "lifetime": 0.015,
                      "start_speed": 0,
                      "width": 1,
                      "height": 1,
                      "start_color": [1, 1, 1, 1],
                      "unity_renderer": {
                        "tint": [1, 1, 1, 1],
                        "sub_emitters": [
                          { "target": "child_death", "event": "death", "probability": 1.0, "inherit": 0 }
                        ]
                      }
                    },
                    {
                      "name": "child_death",
                      "renderer": "sprite",
                      "material": "m",
                      "local_space": false,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 4,
                      "rate": 0,
                      "lifetime": 1.0,
                      "unity_renderer": {}
                    }
                  ]
                }
                """;
        EffectDefinition chronoDef = parse(chronoJson);
        EmitterDefinition chronoEm = chronoDef.emitters().get(0);
        var chronoBind = chronoDef.unityRenderers().get("chrono");

        var singleStep = new EmitterInstance(chronoEm, 123L, null, chronoBind);
        singleStep.update(0.0, handle.input(), true);
        var singleStepAt02 = singleStep.update(0.02, handle.input(), true);
        assertEquals(1, singleStepAt02.particles().size(),
                "At 0.02, particle A died at 0.015 and burst C at 0.02 must spawn into freed slot");
        assertEquals(0.02, singleStepAt02.particles().get(0).born(), 1e-6,
                "Burst B at 0.01 must be rejected because A (death 0.015) is still alive, allowing burst C at 0.02");
        assertEquals(1, singleStep.subEmitterEvents().size());
        assertEquals(0.015, singleStep.subEmitterEvents().get(0).time(), 1e-6);

        var multiStep = new EmitterInstance(chronoEm, 123L, null, chronoBind);
        multiStep.update(0.0, handle.input(), true);
        multiStep.update(0.01, handle.input(), true);
        var multiStepAt02 = multiStep.update(0.02, handle.input(), true);
        assertEquals(1, multiStepAt02.particles().size());
        assertEquals(singleStepAt02.particles().get(0).born(), multiStepAt02.particles().get(0).born(), 1e-6,
                "Coarse update(0->0.02) and stepped update(0->0.01->0.02) must select identical births/deaths");

        // Multi-particle burst at 0.01 with life=0.005 during update(0.02) must still respect maxParticles=1 at birth instant 0.01
        String subFrameBurstJson = """
                {
                  "format_version": 1,
                  "materials": { "m": {} },
                  "emitters": [
                    {
                      "name": "subburst",
                      "renderer": "sprite",
                      "material": "m",
                      "local_space": false,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 1,
                      "rate": 0,
                      "bursts": [ { "time": 0.01, "min": 2, "max": 2 } ],
                      "lifetime": 0.005,
                      "start_speed": 0,
                      "width": 1,
                      "height": 1,
                      "start_color": [1, 1, 1, 1],
                      "unity_renderer": {
                        "tint": [1, 1, 1, 1],
                        "sub_emitters": [
                          { "target": "child_death", "event": "death", "probability": 1.0, "inherit": 0 }
                        ]
                      }
                    },
                    {
                      "name": "child_death",
                      "renderer": "sprite",
                      "material": "m",
                      "local_space": false,
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 4,
                      "rate": 0,
                      "lifetime": 1.0,
                      "unity_renderer": {}
                    }
                  ]
                }
                """;
        EffectDefinition subBurstDef = parse(subFrameBurstJson);
        var subBurstEm = new EmitterInstance(subBurstDef.emitters().get(0), 7L, null, subBurstDef.unityRenderers().get("subburst"));
        subBurstEm.update(0.0, handle.input(), true);
        subBurstEm.update(0.02, handle.input(), true);
        assertEquals(1, subBurstEm.subEmitterEvents().size(),
                "Second particle of same burst at 0.01 must be rejected by max_particles=1 even when dt exceeds lifetime");

        // #6 (new re-audit): MESH and HOST trigger colliders
        float[] boxPos = {
                0, 0, 0, 100, 0, 0, 100, 100, 0, 0, 100, 0,
                0, 0, 100, 100, 0, 100, 100, 100, 100, 0, 100, 100
        };
        int[] boxIds = { 0, 1, 2, 3, 4, 5, 6, 7 };
        float[] zero3 = new float[24], zero1 = new float[8], white4 = new float[32];
        java.util.Arrays.fill(white4, 1.0F);
        int[] boxIdx = {
                0, 2, 1, 0, 3, 2,
                4, 5, 6, 4, 6, 7,
                0, 1, 5, 0, 5, 4,
                2, 3, 7, 2, 7, 6,
                0, 4, 7, 0, 7, 3,
                1, 2, 6, 1, 6, 5
        };
        var triggerMesh = new com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry(
                boxIds, boxPos, zero3, zero3, zero1, white4, List.of(), boxIdx,
                List.of(new com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry.Section(0, "p", 0, boxIdx.length)));
        var meshCollider = new com.kltyton.lumenfx.effect.unity.UnityTrigger.Collider(
                "MeshTrig#0", com.kltyton.lumenfx.effect.unity.UnityTrigger.Shape.MESH,
                Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1),
                new Vec3(1, 1, 1), -1, triggerMesh, true, "");
        var hostSlot = com.kltyton.lumenfx.effect.unity.UnityTrigger.Collider.host("TargetSlot#1", "TargetEntity");
        var trigDef = new com.kltyton.lumenfx.effect.unity.UnityTrigger(
                List.of(meshCollider, hostSlot), 1.0,
                com.kltyton.lumenfx.effect.unity.UnityTrigger.Query.ALL,
                com.kltyton.lumenfx.effect.unity.UnityTrigger.Action.CALLBACK,
                com.kltyton.lumenfx.effect.unity.UnityTrigger.Action.IGNORE,
                com.kltyton.lumenfx.effect.unity.UnityTrigger.Action.IGNORE,
                com.kltyton.lumenfx.effect.unity.UnityTrigger.Action.IGNORE);
        var overlaps = new com.kltyton.lumenfx.effect.simulation.unity.UnityTriggerOverlaps(trigDef);
        overlaps.pose(EffectHandle.Pose.at(Vec3.ZERO), Map.of());
        assertEquals(1L, overlaps.overlap(new Vec3(0.5, 0.5, 0.5), 0.1),
                "Particle strictly inside convex MeshCollider must trigger bit 0; unbound HOST slot bit 1 stays 0");
        assertEquals(0L, overlaps.overlap(new Vec3(2.0, 0.5, 0.5), 0.1));

        var dynamicHostSphere = new com.kltyton.lumenfx.effect.unity.UnityTrigger.Collider(
                "TargetEntity", com.kltyton.lumenfx.effect.unity.UnityTrigger.Shape.SPHERE,
                new Vec3(5.0, 5.0, 5.0), new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1),
                new Vec3(1.0, 1.0, 1.0));
        handle.setUnityTriggerCollider("TargetEntity", dynamicHostSphere);
        overlaps.pose(EffectHandle.Pose.at(Vec3.ZERO), handle.unityTriggerColliders());
        assertEquals(2L, overlaps.overlap(new Vec3(5.2, 5.0, 5.0), 0.1),
                "Dynamic world-space host trigger bound via EffectHandle.setUnityTriggerCollider must activate HOST bit 1");

        // #5 (new re-audit): Server-side authored collider-force bounds

        // #1: ServerColliderForceSpecs registers & resolves authored spec and ServerColliderForceAuthority rejects unbacked hits
        var testEffectId = com.kltyton.lumenfx.LumenFx.id("test_server_force");
        try {
            com.kltyton.lumenfx.common.force.ServerColliderForceSpecs.registerFromJson(testEffectId,
                    com.google.gson.JsonParser.parseString("""
                            {
                              "format_version": 1,
                              "emitters": [{
                                "name": "sparks",
                                "unity_renderer": {
                                  "collision": {
                                    "world": {
                                      "collider_force": 1.2,
                                      "multiply_force_by_collision_angle": true,
                                      "multiply_force_by_particle_speed": true,
                                      "multiply_force_by_particle_size": false
                                    }
                                  }
                                }
                              }]
                            }
                            """).getAsJsonObject());
            var sparkForce = com.kltyton.lumenfx.common.force.ServerColliderForceSpecs.resolve(testEffectId, "sparks");
            assertTrue(sparkForce != null);
            assertEquals(1.2, sparkForce.colliderForce(), 1e-6);
            assertTrue(sparkForce.multiplyByAngle());
            assertTrue(sparkForce.multiplyBySpeed());
            assertFalse(sparkForce.multiplyBySize());
        } finally {
            com.kltyton.lumenfx.common.force.ServerColliderForceSpecs.clearRegistered();
        }

        // #3: Concave vs convex MeshCollider test
        var concaveMeshCollider = new com.kltyton.lumenfx.effect.unity.UnityTrigger.Collider(
                "ConcaveTrig#0", com.kltyton.lumenfx.effect.unity.UnityTrigger.Shape.MESH,
                Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1),
                new Vec3(1, 1, 1), -1, triggerMesh, false, "");
        var concaveDef = new com.kltyton.lumenfx.effect.unity.UnityTrigger(
                List.of(concaveMeshCollider), 1.0,
                com.kltyton.lumenfx.effect.unity.UnityTrigger.Query.ALL,
                com.kltyton.lumenfx.effect.unity.UnityTrigger.Action.CALLBACK,
                com.kltyton.lumenfx.effect.unity.UnityTrigger.Action.IGNORE,
                com.kltyton.lumenfx.effect.unity.UnityTrigger.Action.IGNORE,
                com.kltyton.lumenfx.effect.unity.UnityTrigger.Action.IGNORE);
        var concaveOverlaps = new com.kltyton.lumenfx.effect.simulation.unity.UnityTriggerOverlaps(concaveDef);
        concaveOverlaps.pose(EffectHandle.Pose.at(Vec3.ZERO), Map.of());
        assertEquals(0L, concaveOverlaps.overlap(new Vec3(0.5, 0.5, 0.5), 0.1),
                "Concave mesh collider should only overlap surface within radius, not hollow interior");
        assertEquals(1L, concaveOverlaps.overlap(new Vec3(0.0, 0.5, 0.5), 0.1),
                "Concave mesh collider must trigger on mesh triangle surface");
    }

    @Test
    void unityExternalForcesEvaluateDistanceCurvesHollowRangeWindZoneAndDynamicMembers() {
        // Sphere force field centered at (0,0,0) with hollow start=2.0, end=6.0 and radial direction.x curve:
        // distanceFraction=0 (r=2) -> 2.0, distanceFraction=1 (r=6) -> 10.0.
        // Plus authored directional WindZone along +Z with main=4.0 (initially disabled, toggled dynamically).
        String json = """
                {
                  "format_version": 1,
                  "materials": { "mat": {} },
                  "emitters": [
                    {
                      "name": "inside_hollow",
                      "renderer": "sprite",
                      "material": "mat",
                      "local_space": true,
                      "duration": 5.0,
                      "loop": false,
                      "max_particles": 4,
                      "rate": 0,
                      "bursts": [ { "time": 0, "min": 1, "max": 1 } ],
                      "lifetime": 5.0,
                      "start_speed": 0,
                      "unity_renderer": {
                        "emission_transform": { "position": [1.0, 0.0, 0.0], "rotation": [0, 0, 0, 1], "scale": [1, 1, 1], "shape_only_scale": true },
                        "external_forces": {
                          "multiplier": 1.0,
                          "fields": [
                            {
                              "source_id": "ff_sphere",
                              "shape": "sphere",
                              "center": [0, 0, 0],
                              "x_axis": [1, 0, 0],
                              "y_axis": [0, 1, 0],
                              "z_axis": [0, 0, 1],
                              "start": 2.0,
                              "end": 6.0,
                              "length": 0.0,
                              "direction": [
                                { "keys": [ { "time": 0.0, "value": 2.0, "interpolation": "linear" }, { "time": 1.0, "value": 10.0, "interpolation": "linear" } ] },
                                0.0,
                                0.0
                              ],
                              "gravity": 0.0,
                              "gravity_focus": 0.0,
                              "rotation_speed": 0.0,
                              "rotation_attraction": 0.0,
                              "rotation_randomness_x": 0.0,
                              "rotation_randomness_z": 0.0,
                              "drag": 0.0,
                              "drag_by_size": false,
                              "drag_by_speed": false,
                              "vector_speed": 0.0,
                              "vector_attraction": 0.0,
                              "enabled": true
                            }
                          ],
                          "wind_zones": [
                            {
                              "source_id": "wind_main",
                              "mode": "directional",
                              "center": [0, 0, 0],
                              "direction": [0, 0, 1],
                              "radius": 0.0,
                              "main": 4.0,
                              "turbulence": 0.0,
                              "pulse_magnitude": 0.0,
                              "pulse_frequency": 0.0,
                              "enabled": false
                            }
                          ]
                        }
                      }
                    },
                    {
                      "name": "inside_shell",
                      "renderer": "sprite",
                      "material": "mat",
                      "local_space": true,
                      "duration": 5.0,
                      "loop": false,
                      "max_particles": 4,
                      "rate": 0,
                      "bursts": [ { "time": 0, "min": 1, "max": 1 } ],
                      "lifetime": 5.0,
                      "start_speed": 0,
                      "unity_renderer": {
                        "emission_transform": { "position": [4.0, 0.0, 0.0], "rotation": [0, 0, 0, 1], "scale": [1, 1, 1], "shape_only_scale": true },
                        "external_forces": {
                          "multiplier": 1.0,
                          "fields": [
                            {
                              "source_id": "ff_sphere",
                              "shape": "sphere",
                              "center": [0, 0, 0],
                              "x_axis": [1, 0, 0],
                              "y_axis": [0, 1, 0],
                              "z_axis": [0, 0, 1],
                              "start": 2.0,
                              "end": 6.0,
                              "length": 0.0,
                              "direction": [
                                { "keys": [ { "time": 0.0, "value": 2.0, "interpolation": "linear" }, { "time": 1.0, "value": 10.0, "interpolation": "linear" } ] },
                                0.0,
                                0.0
                              ],
                              "gravity": 0.0,
                              "gravity_focus": 0.0,
                              "rotation_speed": 0.0,
                              "rotation_attraction": 0.0,
                              "rotation_randomness_x": 0.0,
                              "rotation_randomness_z": 0.0,
                              "drag": 0.0,
                              "drag_by_size": false,
                              "drag_by_speed": false,
                              "vector_speed": 0.0,
                              "vector_attraction": 0.0,
                              "enabled": true
                            }
                          ],
                          "wind_zones": [
                            {
                              "source_id": "wind_main",
                              "mode": "directional",
                              "center": [0, 0, 0],
                              "direction": [0, 0, 1],
                              "radius": 0.0,
                              "main": 4.0,
                              "turbulence": 0.0,
                              "pulse_magnitude": 0.0,
                              "pulse_frequency": 0.0,
                              "enabled": false
                            }
                          ]
                        }
                      }
                    }
                  ]
                }
                """;
        EffectDefinition def = parse(json);
        var hollowDef = def.emitters().get(0);
        var shellDef = def.emitters().get(1);
        var hollowHandle = new EffectHandle(Vec3.ZERO);
        var shellHandle = new EffectHandle(Vec3.ZERO);

        EmitterInstance hollow = new EmitterInstance(hollowDef, 501L, null, def.unityRenderers().get("inside_hollow"));
        EmitterInstance shell = new EmitterInstance(shellDef, 501L, null, def.unityRenderers().get("inside_shell"));

        hollow.update(0.0, hollowHandle.input(), true);
        shell.update(0.0, shellHandle.input(), true);

        // Counterexample: particle inside hollow inner sphere (r=1.0 < start=2.0) with wind disabled gets ZERO acceleration.
        var hollowSnap = hollow.update(0.25, hollowHandle.input(), true);
        double[] hollowVel = new double[3];
        hollowSnap.particles().get(0).sampleVelocity(0.25, hollowDef, hollowVel, 0);
        assertEquals(0.0, hollowVel[0], 1e-4, "Particle inside hollow start_range must not receive force field acceleration");
        assertEquals(0.0, hollowVel[2], 1e-4, "Disabled WindZone must not accelerate particles");

        // Particle at r=4.0 (distanceFraction = (4-2)/(6-2) = 0.5 -> curve value = 6.0) accelerates strongly along +X.
        var shellSnap = shell.update(0.25, shellHandle.input(), true);
        double[] shellVel = new double[3];
        shellSnap.particles().get(0).sampleVelocity(0.25, shellDef, shellVel, 0);
        assertTrue(shellVel[0] > 1.0, "Curved force field at r=4.0 (mid-shell) must accelerate along +X, got vx=" + shellVel[0]);
        assertEquals(0.0, shellVel[2], 1e-4);

        // Dynamically enable authored WindZone AND inject a brand-new runtime Spherical WindZone along +Y via EffectHandle!
        var dynamicWind = new com.kltyton.lumenfx.effect.unity.UnityExternalForces.WindZone(
                com.kltyton.lumenfx.effect.unity.UnityExternalForces.WindZone.Mode.SPHERICAL,
                new Vec3(1.0, -2.0, 0.0), new Vec3(0, 1, 0), 10.0f, 8.0f, 0.0f, 0.0f, 0.0f, "dyn_spherical", true);
        hollowHandle.setUnityForceFields(Map.of(
                "wind_main", new com.kltyton.lumenfx.effect.unity.UnityExternalForces.FieldState(true, null),
                "dyn_spherical", com.kltyton.lumenfx.effect.unity.UnityExternalForces.FieldState.dynamic(dynamicWind)));
        var windSnap = hollow.update(0.5, hollowHandle.input(), true, hollowHandle.unityForceFields());
        double[] windVel = new double[3];
        windSnap.particles().get(0).sampleVelocity(0.5, hollowDef, windVel, 0);
        assertTrue(windVel[2] > 0.5, "Enabling authored directional WindZone must accelerate particle along +Z, got vz=" + windVel[2]);
        assertTrue(windVel[1] > 0.5, "Dynamic runtime spherical WindZone below particle must push along +Y, got vy=" + windVel[1]);
    }

    @Test
    void overlappingDynamicForceFieldsAreDeterministicAcrossMapOrdersAndClientServerSnapshots() {
        var zero = com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0);
        var one = com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(1);
        var fieldA = new com.kltyton.lumenfx.effect.unity.UnityExternalForces.Field(
                com.lowdragmc.photon.client.gameobject.forcefield.ForceFieldConfig.Shape.Sphere,
                Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1),
                0f, 10f, 0f,
                List.of(com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(3.0), zero, zero),
                zero, 0f,
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(2.5),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.6),
                0.75f, 0.5f,
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.4), false, true,
                null, zero, zero, "dyn_a", true);
        var fieldB = new com.kltyton.lumenfx.effect.unity.UnityExternalForces.Field(
                com.lowdragmc.photon.client.gameobject.forcefield.ForceFieldConfig.Shape.Sphere,
                Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1),
                0f, 10f, 0f,
                List.of(zero, com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(-2.0),
                        com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(4.0)),
                zero, 0f,
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(-1.5),
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.8),
                0.6f, 0.9f,
                com.kltyton.lumenfx.effect.curve.ScalarFunction.constant(0.7), false, true,
                null, zero, zero, "dyn_b", true);

        var orderAB = new java.util.LinkedHashMap<String, com.kltyton.lumenfx.effect.unity.UnityExternalForces.FieldState>();
        orderAB.put("dyn_a", com.kltyton.lumenfx.effect.unity.UnityExternalForces.FieldState.dynamic(fieldA));
        orderAB.put("dyn_b", com.kltyton.lumenfx.effect.unity.UnityExternalForces.FieldState.dynamic(fieldB));

        var orderBA = new java.util.LinkedHashMap<String, com.kltyton.lumenfx.effect.unity.UnityExternalForces.FieldState>();
        orderBA.put("dyn_b", com.kltyton.lumenfx.effect.unity.UnityExternalForces.FieldState.dynamic(fieldB));
        orderBA.put("dyn_a", com.kltyton.lumenfx.effect.unity.UnityExternalForces.FieldState.dynamic(fieldA));

        var clientHandleAB = new EffectHandle(Vec3.ZERO);
        clientHandleAB.setUnityForceFields(orderAB);
        var clientHandleBA = new EffectHandle(Vec3.ZERO);
        clientHandleBA.setUnityForceFields(orderBA);
        assertEquals(List.of("dyn_a", "dyn_b"), List.copyOf(clientHandleAB.unityForceFields().keySet()));
        assertEquals(List.of("dyn_a", "dyn_b"), List.copyOf(clientHandleBA.unityForceFields().keySet()));

        var serverInputBA = new com.kltyton.lumenfx.api.server.LumenFxServerEffects.CollisionInput(
                EffectHandle.Pose.at(Vec3.ZERO), Vec3.ZERO,
                com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers.EMPTY,
                Map.of(), Map.of(), Map.of(), true, false, orderBA);
        assertEquals(List.of("dyn_a", "dyn_b"), List.copyOf(serverInputBA.forceFields().keySet()));

        // Even when UnityForceFields.pose is called directly with raw reversed LinkedHashMaps, trajectories match bit-for-bit.
        var emptyAuthored = new com.kltyton.lumenfx.effect.unity.UnityExternalForces(one, List.of(), List.of());
        var simDirectAB = new com.kltyton.lumenfx.effect.simulation.unity.UnityForceFields(emptyAuthored, true);
        var simDirectBA = new com.kltyton.lumenfx.effect.simulation.unity.UnityForceFields(emptyAuthored, true);
        var simServer = new com.kltyton.lumenfx.effect.simulation.unity.UnityForceFields(emptyAuthored, true);
        simDirectAB.pose(EffectHandle.Pose.at(Vec3.ZERO), EffectHandle.Pose.at(Vec3.ZERO), orderAB);
        simDirectBA.pose(EffectHandle.Pose.at(Vec3.ZERO), EffectHandle.Pose.at(Vec3.ZERO), orderBA);
        simServer.pose(EffectHandle.Pose.at(Vec3.ZERO), EffectHandle.Pose.at(Vec3.ZERO), serverInputBA.forceFields());

        assertTrue(simDirectAB.apply(1.2, 0.4, -0.7, 0.5, 0.2, -0.3, 0.25, 0.37, 1.0f, 0.1f, 0.25));
        assertTrue(simDirectBA.apply(1.2, 0.4, -0.7, 0.5, 0.2, -0.3, 0.25, 0.37, 1.0f, 0.1f, 0.25));
        assertTrue(simServer.apply(1.2, 0.4, -0.7, 0.5, 0.2, -0.3, 0.25, 0.37, 1.0f, 0.1f, 0.25));
        assertEquals(simDirectAB.velocity(), simDirectBA.velocity(),
                "Overlapping dynamic force fields must produce identical velocity regardless of map iteration order");
        assertEquals(simDirectAB.velocity(), simServer.velocity(),
                "Client and server dynamic force-field snapshots must produce identical velocity");
    }

    @Test
    void subEmitterInheritLifetimeUsesParentRemainingSeconds() {
        String json = """
                {
                  "format_version": 1,
                  "materials": { "mat": {} },
                  "emitters": [
                    {
                      "name": "parent",
                      "renderer": "sprite",
                      "material": "mat",
                      "duration": 2.0,
                      "loop": false,
                      "max_particles": 4,
                      "rate": 0,
                      "bursts": [ { "time": 0.04, "min": 1, "max": 1 } ],
                      "lifetime": 0.66,
                      "start_speed": 0,
                      "start_direction": [0, 0, 1],
                      "unity_renderer": {
                        "sub_emitters": [
                          { "target": "child", "event": "birth", "probability": 1.0, "inherit": 8 }
                        ]
                      }
                    },
                    {
                      "name": "child",
                      "renderer": "sprite",
                      "material": "mat",
                      "duration": 2.0,
                      "loop": false,
                      "max_particles": 8,
                      "rate": 4,
                      "bursts": [ { "time": 0.0, "min": 1, "max": 1 } ],
                      "lifetime": 0.5,
                      "start_speed": 0,
                      "start_direction": [0, 0, 1],
                      "unity_renderer": {}
                    }
                  ]
                }
                """;
        EffectDefinition def = parse(json);
        var handle = new EffectHandle(Vec3.ZERO);
        var instance = new EffectInstance(def, handle, 101L);
        var builder = new EffectMeshBuilder(0);
        // Step at 0.05s: parent born at 0.04s with 0.66s life -> child burst born at 0.04s inherits 0.5 * 0.66 = 0.33s life.
        for (int f = 0; f <= 8; f++) {
            builder.begin(Vec3.ZERO, new org.joml.Quaternionf());
            instance.beginSimulationFrame(f * 0.05);
            for (var emitterFrame : instance.packPreparedFrame()) emitterFrame.updateAndPack(builder);
            var meshes = builder.finish();
            int childLive = meshes.size() > 1 ? meshes.get(1).instanceCount() : 0;
            if (f == 1) assertEquals(1, childLive, "Burst child should spawn at t=0.04s");
            // At t=0.29s (frame 6), rate=4 emits 2nd child with parent remaining = 0.70 - 0.29 = 0.41s -> child life = 0.205s.
            // At t=0.40s (frame 8), 1st child died at 0.04 + 0.33 = 0.37s, only 2nd child (dies at 0.495s) remains!
            if (f == 8) assertEquals(1, childLive, "First child must expire at 0.04 + 0.5*0.66 = 0.37s, leaving only 2nd child");
        }
    }

    @Test
    void unityBoxShellAndBoxEdgeThicknessPlaceHalfOnExactOuterPlanesAndKeepAllWithinBounds() {
        String json = """
                {
                  "format_version": 1,
                  "materials": { "mat": {} },
                  "emitters": [
                    {
                      "name": "box_shell_thick",
                      "renderer": "sprite",
                      "material": "mat",
                      "duration": 5.0,
                      "loop": false,
                      "max_particles": 256,
                      "rate": 0,
                      "bursts": [ { "time": 0, "min": 240, "max": 240 } ],
                      "lifetime": 5.0,
                      "start_speed": 0,
                      "shape": {
                        "type": "box",
                        "surface": true,
                        "size": [1.0, 2.0, 3.0],
                        "unity": {
                          "rotation": [0, 0, 0, 1],
                          "box_thickness": [0.25, 0.5, 0.75]
                        }
                      },
                      "unity_renderer": {}
                    },
                    {
                      "name": "box_edge_thick",
                      "renderer": "sprite",
                      "material": "mat",
                      "duration": 5.0,
                      "loop": false,
                      "max_particles": 256,
                      "rate": 0,
                      "bursts": [ { "time": 0, "min": 240, "max": 240 } ],
                      "lifetime": 5.0,
                      "start_speed": 0,
                      "shape": {
                        "type": "box_edge",
                        "size": [1.0, 2.0, 3.0],
                        "unity": {
                          "rotation": [0, 0, 0, 1],
                          "box_thickness": [0.25, 0.5, 0.75]
                        }
                      },
                      "unity_renderer": {}
                    }
                  ]
                }
                """;
        EffectDefinition def = parse(json);
        var shellEmitter = new EmitterInstance(def.emitters().get(0), 2026L, null, def.unityRenderers().get("box_shell_thick"));
        var shellSnap = shellEmitter.update(0.0, inputAt(Vec3.ZERO, 0, 0, 0, 1, 1f), true);
        assertEquals(240, shellSnap.particles().size());
        int shellExactX = 0, shellExactY = 0, shellExactZ = 0, shellAnyExact = 0;
        for (ParticleState p : shellSnap.particles()) {
            Vec3 pos = p.position();
            assertTrue(Math.abs(pos.x) <= 1.0 + 1e-6 && Math.abs(pos.y) <= 2.0 + 1e-6 && Math.abs(pos.z) <= 3.0 + 1e-6,
                    "BoxShell particle must remain inside box bounds: " + pos);
            boolean exX = Math.abs(pos.x + 1.0) < 1e-5;
            boolean exY = Math.abs(pos.y + 2.0) < 1e-5;
            boolean exZ = Math.abs(pos.z - 3.0) < 1e-5;
            if (exX) shellExactX++;
            if (exY) shellExactY++;
            if (exZ) shellExactZ++;
            if (exX || exY || exZ) shellAnyExact++;
        }
        assertTrue(shellExactX > 15 && shellExactY > 15 && shellExactZ > 15,
                "BoxShell must distribute exact outer faces across all 3 axes: " + shellExactX + "/" + shellExactY + "/" + shellExactZ);
        assertTrue(shellAnyExact >= 90 && shellAnyExact <= 150,
                "BoxShell should place ~50% of births on exact outer planes, got " + shellAnyExact + "/240");

        var edgeEmitter = new EmitterInstance(def.emitters().get(1), 2026L, null, def.unityRenderers().get("box_edge_thick"));
        var edgeSnap = edgeEmitter.update(0.0, inputAt(Vec3.ZERO, 0, 0, 0, 1, 1f), true);
        assertEquals(240, edgeSnap.particles().size());
        int edgeExactX = 0, edgeExactY = 0, edgeExactZ = 0, edgeAnyExact = 0;
        for (ParticleState p : edgeSnap.particles()) {
            Vec3 pos = p.position();
            assertTrue(Math.abs(pos.x) <= 1.0 + 1e-6 && Math.abs(pos.y) <= 2.0 + 1e-6 && Math.abs(pos.z) <= 3.0 + 1e-6,
                    "BoxEdge particle must remain inside box bounds: " + pos);
            boolean exX = Math.abs(pos.x + 1.0) < 1e-5;
            boolean exY = Math.abs(pos.y + 2.0) < 1e-5;
            boolean exZ = Math.abs(pos.z - 3.0) < 1e-5;
            if (exX) edgeExactX++;
            if (exY) edgeExactY++;
            if (exZ) edgeExactZ++;
            if (exX || exY || exZ) edgeAnyExact++;
        }
        assertTrue(edgeExactX > 45 && edgeExactY > 45 && edgeExactZ > 45,
                "BoxEdge must distribute exact outer planes across all 3 axes: " + edgeExactX + "/" + edgeExactY + "/" + edgeExactZ);
        assertTrue(edgeAnyExact >= 150 && edgeAnyExact <= 210,
                "BoxEdge should place ~75% of births on at least one exact outer plane, got " + edgeAnyExact + "/240");
    }
}
