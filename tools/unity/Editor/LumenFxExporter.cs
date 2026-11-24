#if UNITY_EDITOR
using System;
using System.Collections.Generic;
using System.Linq;
using System.IO;
using UnityEngine;
using UnityEngine.Rendering;

namespace LumenFx.UnityExport
{
    internal sealed class EmitterCommon
    {
        public readonly Dictionary<string, object> Fields = new Dictionary<string, object>();
        public ColorPlan StartColor = ColorPlan.Constant(Color.white);
        public ColorPlan ColorOverLife = ColorPlan.Constant(Color.white);
        public ScalarPlan Width = ScalarPlan.Constant(1);
        public ScalarPlan Height = ScalarPlan.Constant(1);
        public Dictionary<string, object> StartDepth;
        public int AtlasColumns = 1;
        public int AtlasRows = 1;
        public int AtlasRow = -1;
        public string AtlasRowSelection;
        public ScalarPlan AtlasFrame = ScalarPlan.Constant(0);
        public float? AtlasFps;
        public object AtlasSpeed;
        public int UvChannelMask = 1;
        public List<float[]> SpriteFrames;
        public Texture2D SpriteSheet;
        public int SortingLayer;
        public int SortingOrder;
        public float SortingFudge;
        public List<Dictionary<string, object>> SortingGroups;
        public int HierarchyOrder;
        public bool Prewarm;
        public float SimulationSpeed;
        public float MaximumParticleDeltaTime;
        public float FlipRotation;
        public double? StartDelayMaximum;
        public uint? RandomSeed;
        public bool PauseUntilReplaced;
        public float[] LifetimeLoop;
        public Dictionary<string, object> InheritVelocity;
        public Dictionary<string, object> VelocityLimit;
        public Dictionary<string, object> SpaceMotion;
        public Dictionary<string, object> MeshMotion;
        public readonly Dictionary<string, object> BillboardMotion = new Dictionary<string, object>();
        public Dictionary<string, object> Collision;
        public Dictionary<string, object> Trigger;
        public Dictionary<string, object> Lights;
        public Dictionary<string, object> CustomData;
        public Dictionary<string, object> Noise;
        public Dictionary<string, object> ExternalForces;
        public Dictionary<string, object> EmitterTransform;
        public Dictionary<string, object> EmissionTransform;
        public Dictionary<string, object> SpeedModules;
        public Dictionary<string, object> LifetimeByEmitterSpeed;
        public object[] SizeOverLifetimeAxes;
    }

    internal sealed class AxisMapper
    {
        private readonly bool coneForwardToUp;

        public AxisMapper(bool coneForwardToUp)
        {
            this.coneForwardToUp = coneForwardToUp;
        }

        public bool ConvertsForwardToUp { get { return coneForwardToUp; } }

        public Vector3 Vector(Vector3 value)
        {
            return coneForwardToUp
                ? new Vector3(value.x, value.z, -value.y)
                : new Vector3(value.x, value.y, -value.z);
        }

        public Vector3 Direction(Vector3 value)
        {
            return Vector(value);
        }

        public ScalarPlan[] MapScalars(ScalarPlan x, ScalarPlan y, ScalarPlan z, bool signed = true)
        {
            return coneForwardToUp
                ? new[] { x, z, signed ? y.Multiply(-1) : y }
                : new[] { x, y, signed ? z.Multiply(-1) : z };
        }

        public Vector3 Extent(Vector3 value)
        {
            Vector3 mapped = Vector(value);
            return new Vector3(Mathf.Abs(mapped.x), Mathf.Abs(mapped.y), Mathf.Abs(mapped.z));
        }

        public double Rotation(double radians)
        {
            return -radians;
        }

        public Quaternion Orientation(Quaternion value)
        {
            Vector3 imaginary = Vector(new Vector3(value.x, value.y, value.z));
            if (!coneForwardToUp) imaginary = -imaginary;
            return new Quaternion(imaginary.x, imaginary.y, imaginary.z, value.w);
        }
    }

    internal static class LumenFxExporter
    {
        private const int UnityClearCoatLightingFeature = 4096;

        public static LumenFxBuildResult Build(ExportSettings settings)
        {
            List<ExportIssue> issues = new List<ExportIssue>();
            LumenFxPlan plan = new LumenFxPlan();

            if (settings == null)
            {
                issues.Add(new ExportIssue("Export", "No export settings were supplied"));
                return new LumenFxBuildResult(plan, issues);
            }

            if (settings.Root == null)
                issues.Add(new ExportIssue("Export", "Select a GameObject containing one or more ParticleSystems"));
            if (!IsValidNamespace(settings.Namespace))
                issues.Add(new ExportIssue("Export", "Namespace must contain only a-z, 0-9, '_', '-' and '.'"));
            if (!IsValidEffectName(settings.EffectName))
                issues.Add(new ExportIssue("Export", "Effect name must contain only a-z, 0-9, '_', '-' and '.'"));
            if (string.IsNullOrEmpty(settings.OutputFolder))
                issues.Add(new ExportIssue("Export", "Choose an output folder"));

            if (issues.Count != 0 || settings.Root == null) return new LumenFxBuildResult(plan, issues);

            Transform root = settings.Root.transform;
            if (!Finite(root.lossyScale) || root.lossyScale.x <= 0 || root.lossyScale.y <= 0 || root.lossyScale.z <= 0)
                issues.Add(new ExportIssue("Export", "The selected root scale must have finite, positive components"));

            ParticleSystem[] systems = settings.Root.GetComponentsInChildren<ParticleSystem>(true);
            if (systems.Length == 0)
            {
                issues.Add(new ExportIssue("Export", "No ParticleSystem was found under the selected GameObject"));
                return new LumenFxBuildResult(plan, issues);
            }

            bool coneAxis = false;
            foreach (ParticleSystem system in systems)
            {
                ParticleSystem.ShapeModule shape = system.shape;
                if (shape.enabled && shape.shapeType == ParticleSystemShapeType.ConeVolume)
                {
                    coneAxis = true;
                    break;
                }
            }

            AxisMapper mapper = new AxisMapper(coneAxis);
            HashSet<string> usedEmitterNames = new HashSet<string>();
            HashSet<string> usedTexturePaths = new HashSet<string>();
            var primary = new Dictionary<ParticleSystem, Dictionary<string, object>>();
            var linkedSystems = LumenFxSubEmitters.CollectLinkedSystems(systems);
            var hierarchyOrder = CollectHierarchyOrder(root);

            foreach (ParticleSystem system in systems)
            {
                int before = plan.Emitters.Count;
                BuildSystem(settings, plan, issues, system, root, mapper, usedEmitterNames, usedTexturePaths,
                    linkedSystems.Contains(system), hierarchyOrder);
                if (plan.Emitters.Count > before) primary.Add(system, plan.Emitters[before]);
            }
            LumenFxSubEmitters.WriteLinks(systems, primary, issues);

            if (plan.Emitters.Count == 0 && issues.Count == 0)
                issues.Add(new ExportIssue("Export", "No particle emitter was produced"));

            return new LumenFxBuildResult(plan, issues);
        }

        public static List<string> Write(ExportSettings settings, LumenFxPlan plan)
        {
            List<string> written = new List<string>();
            string outputRoot = Path.GetFullPath(settings.OutputFolder);

            foreach (MaterialPlan material in plan.Materials.Values)
            {
                if (material.PngData != null && !string.IsNullOrEmpty(material.TextureRelativePath))
                {
                    string path = Path.Combine(outputRoot,
                        material.TextureRelativePath.Replace('/', Path.DirectorySeparatorChar));
                    string directory = Path.GetDirectoryName(path);
                    if (!string.IsNullOrEmpty(directory)) Directory.CreateDirectory(directory);
                    File.WriteAllBytes(path, material.PngData);
                    written.Add(path);
                }
                foreach (KeyValuePair<string, byte[]> extra in material.ExtraTexturePayloads)
                {
                    if (extra.Value == null || string.IsNullOrEmpty(extra.Key)) continue;
                    string path = Path.Combine(outputRoot, extra.Key.Replace('/', Path.DirectorySeparatorChar));
                    string directory = Path.GetDirectoryName(path);
                    if (!string.IsNullOrEmpty(directory)) Directory.CreateDirectory(directory);
                    File.WriteAllBytes(path, extra.Value);
                    written.Add(path);
                }
                foreach (KeyValuePair<string, string> extraText in material.ExtraTextPayloads)
                {
                    if (extraText.Value == null || string.IsNullOrEmpty(extraText.Key)) continue;
                    string path = Path.Combine(outputRoot, extraText.Key.Replace('/', Path.DirectorySeparatorChar));
                    string directory = Path.GetDirectoryName(path);
                    if (!string.IsNullOrEmpty(directory)) Directory.CreateDirectory(directory);
                    File.WriteAllText(path, extraText.Value, new System.Text.UTF8Encoding(false));
                    written.Add(path);
                }
            }
            foreach (var payload in plan.MeshGeometry.Concat(plan.ShapeTextures))
            {
                string path = Path.Combine(outputRoot, payload.Key.Replace('/', Path.DirectorySeparatorChar));
                Directory.CreateDirectory(Path.GetDirectoryName(path));
                File.WriteAllBytes(path, payload.Value);
                written.Add(path);
            }
            foreach (var mesh in plan.MeshAssets)
            {
                string path = Path.Combine(outputRoot, mesh.Key.Replace('/', Path.DirectorySeparatorChar));
                Directory.CreateDirectory(Path.GetDirectoryName(path));
                File.WriteAllText(path, LumenFxJson.Write(mesh.Value), new System.Text.UTF8Encoding(false));
                written.Add(path);
            }

            Dictionary<string, object> materials = new Dictionary<string, object>();
            foreach (MaterialPlan material in plan.Materials.Values)
            {
                Dictionary<string, object> textures = new Dictionary<string, object>();
                foreach (KeyValuePair<string, string> texture in material.Textures)
                    textures[texture.Key] = texture.Value;
                var materialEntry = new Dictionary<string, object>
                {
                    { "fragment_shader", material.FragmentShader },
                    { "textures", textures },
                    { "blend", material.Blend },
                    { "depth_test", material.DepthTest },
                    { "depth_compare", material.DepthCompare },
                    { "depth_write", material.DepthWrite },
                    { "cull", material.Cull },
                    { "dynamic_slots", material.DynamicSlots },
                    { "parameters", new object[] { material.Parameters[0], material.Parameters[1],
                        material.Parameters[2], material.Parameters[3] } }
                };
                if (material.TextureSamplers.Count > 0)
                    materialEntry["texture_samplers"] = material.TextureSamplers;
                if (material.LightFunction)
                {
                    materialEntry["light_function"] = true;
                    materialEntry["light_function_atlas_compatible"] = true;
                }
                if (!string.IsNullOrEmpty(material.VertexMaterial))
                {
                    materialEntry["vertex_material"] = material.VertexMaterial;
                    materialEntry["world_position_offset"] = material.WorldPositionOffset;
                    if (material.VertexBasis) materialEntry["vertex_basis"] = true;
                    materialEntry["customized_uv_mask"] = 0;
                    materialEntry["maximum_vertex_displacement"] = material.MaximumVertexDisplacement;
                }
                int materialView = material.MaterialView | (material.SceneColor ? 2 : 0);
                if (materialView != 0) materialEntry["material_view"] = materialView;
                if (material.SgUv4Mask != 0) materialEntry["sg_uv4_mask"] = material.SgUv4Mask;
                if (!string.IsNullOrEmpty(material.BlendOp) && material.BlendOp != "add")
                    materialEntry["blend_op"] = material.BlendOp;
                if (material.GammaBlend)
                {
                    materialEntry["render"] = new Dictionary<string, object> { { "blend_color_space", "unity_gamma" } };
                    // Standard Unlit is drawn in the camera's transparent queue ahead of image effects.
                    materialEntry["translucency_pass"] = "before_dof";
                }
                if (material.DepthProjection)
                    materialEntry["depth_projection"] = true;
                if (material.SceneDepth)
                    materialEntry["scene_depth"] = true;
                if (material.SceneColor)
                    materialEntry["scene_color"] = true;
                if (material.DefaultLitSurface && material.Blend == "opaque")
                    materialEntry["default_lit_surface"] = true;
                if (material.ClearCoatSurface && material.Blend == "opaque")
                {
                    materialEntry["custom_surface_data"] = true;
                    materialEntry["particle_lighting_features"] = UnityClearCoatLightingFeature;
                }
                if (material.ForwardLitSurface)
                    materialEntry["translucency_lighting_volume"] = "surface_per_pixel";
                if (material.RuntimeParameters.Count > 0)
                    materialEntry["runtime_parameters"] = material.RuntimeParameters;
                materials[material.Id] = materialEntry;
            }

            Dictionary<string, object> root = new Dictionary<string, object>
            {
                { "format_version", 1 },
                { "materials", materials },
                { "emitters", plan.Emitters }
            };

            string jsonPath = Path.Combine(outputRoot, "assets", settings.Namespace,
                "lumenfx", "effects", settings.EffectName + ".json");
            Directory.CreateDirectory(Path.GetDirectoryName(jsonPath));
            File.WriteAllText(jsonPath, LumenFxJson.Write(root), new System.Text.UTF8Encoding(false));
            written.Add(jsonPath);
            return written;
        }

        private static void BuildSystem(ExportSettings settings, LumenFxPlan plan, List<ExportIssue> issues,
            ParticleSystem system, Transform root, AxisMapper mapper,
            HashSet<string> usedEmitterNames, HashSet<string> usedTexturePaths, bool linked,
            Dictionary<Transform, int> hierarchyOrder)
        {
            string context = PathOf(root, system.transform);
            var main = system.main;
            var simSpace = main.simulationSpace;
            Transform customSpace = main.customSimulationSpace;
            bool usesCustomSpace = simSpace == ParticleSystemSimulationSpace.Custom
                || (system.velocityOverLifetime.enabled && system.velocityOverLifetime.space == ParticleSystemSimulationSpace.Custom)
                || (system.forceOverLifetime.enabled && system.forceOverLifetime.space == ParticleSystemSimulationSpace.Custom)
                || (system.limitVelocityOverLifetime.enabled && system.limitVelocityOverLifetime.separateAxes
                    && system.limitVelocityOverLifetime.space == ParticleSystemSimulationSpace.Custom);
            if (usesCustomSpace && customSpace == null)
                issues.Add(new ExportIssue(context, "Custom Simulation Space requires a Custom Simulation Space transform"));

            Transform simulationOrigin = simSpace == ParticleSystemSimulationSpace.Custom && customSpace != null
                ? customSpace
                : system.transform;
            var transformScaling = simSpace == ParticleSystemSimulationSpace.Custom && simulationOrigin != system.transform
                ? ParticleSystemScalingMode.Hierarchy
                : main.scalingMode;
            var transform = BuildEmitterTransform(root, simulationOrigin, transformScaling, linked, mapper, context, issues);
            Dictionary<string, object> emissionTransform = null;
            if (simSpace == ParticleSystemSimulationSpace.Custom && customSpace != null && customSpace != system.transform)
                emissionTransform = BuildEmitterTransform(customSpace, system.transform, main.scalingMode, false, mapper,
                    context + " emission", issues, inAnchorScaledSpace: true);

            ParticleSystemRenderer renderer = system.GetComponent<ParticleSystemRenderer>();
            ParticleSystem.TrailModule trails = system.trails;
            bool trailsEnabled = trails.enabled;
            bool perParticle = trailsEnabled && trails.mode == ParticleSystemTrailMode.PerParticle;
            bool visible = renderer != null && renderer.enabled;
            var renderMode = visible ? renderer.renderMode : ParticleSystemRenderMode.None;
            bool emitSprite = renderMode == ParticleSystemRenderMode.Billboard || renderMode == ParticleSystemRenderMode.Stretch
                || renderMode == ParticleSystemRenderMode.HorizontalBillboard || renderMode == ParticleSystemRenderMode.VerticalBillboard;
            bool emitMesh = renderMode == ParticleSystemRenderMode.Mesh;
            if (!emitSprite && !emitMesh && renderMode != ParticleSystemRenderMode.None)
            {
                issues.Add(new ExportIssue(context, "Unsupported particle render mode " + renderer.renderMode));
                return;
            }
            bool emitRibbon = visible && trailsEnabled;
            if (emitRibbon && !perParticle && trails.ribbonCount <= 0)
                issues.Add(new ExportIssue(context, "Ribbon Count must be positive"));

            string baseName = Sanitize(system.name);
            if (string.IsNullOrEmpty(baseName)) baseName = "emitter";

            MaterialPlan spriteMaterial = null;
            MaterialPlan meshMaterial = null;
            MaterialPlan ribbonMaterial = null;
            bool alignAtBirth = system.shape.enabled && system.shape.alignToDirection;
            if (alignAtBirth && emitSprite && renderer.renderMode != ParticleSystemRenderMode.Billboard)
                issues.Add(new ExportIssue(context, "Shape Align to Direction is not supported for " + renderer.renderMode));
            EmitterCommon common = BuildCommon(system, emitSprite || emitMesh, emitMesh ? renderer.meshCount : 0,
                mapper, context, issues);
            if (common == null) return;
            if (emitSprite || emitMesh || emitRibbon)
            {
                common.SortingLayer = SortingLayer.GetLayerValueFromID(renderer.sortingLayerID);
                common.SortingOrder = renderer.sortingOrder;
                common.SortingFudge = renderer.sortingFudge;
                if (!Finite(common.SortingFudge)) issues.Add(new ExportIssue(context, "Sorting Fudge must be finite"));
                common.SortingGroups = BuildSortingGroups(root, system.transform, mapper, hierarchyOrder, context, issues);
                int order;
                common.HierarchyOrder = hierarchyOrder.TryGetValue(system.transform, out order) ? order : 0;
            }
            common.Fields["shape"] = LumenFxShape.Build(system, mapper, context, issues,
                settings, plan, baseName + "/shape_" + plan.EmitterShapes++);
            common.EmitterTransform = transform;
            common.EmissionTransform = emissionTransform;
            if (emitSprite)
            {
                spriteMaterial = LumenFxMaterialBuilder.Build(renderer.sharedMaterial,
                    UniqueMaterialId(plan, baseName + "_sprite"), context + " sprite material",
                    settings.Namespace, settings.EffectName, usedTexturePaths, issues, spriteSheet: common.SpriteSheet);
            }
            if (emitMesh)
                meshMaterial = LumenFxMaterialBuilder.Build(renderer.sharedMaterial,
                    UniqueMaterialId(plan, baseName + "_mesh"), context + " mesh material",
                    settings.Namespace, settings.EffectName, usedTexturePaths, issues, true, common.SpriteSheet,
                    mapper.ConvertsForwardToUp, renderer.enableGPUInstancing && renderer.sharedMaterial != null
                    && renderer.sharedMaterial.enableInstancing);
            if (emitRibbon)
            {
                Material trailMaterial = renderer.trailMaterial != null ? renderer.trailMaterial : renderer.sharedMaterial;
                ribbonMaterial = LumenFxMaterialBuilder.Build(trailMaterial,
                    UniqueMaterialId(plan, baseName + "_trail"), context + " trail material",
                    settings.Namespace, settings.EffectName, usedTexturePaths, issues, true);
            }

            if (system.collision.enabled)
                common.Collision = LumenFxCollision.Build(system.collision, root, mapper, context, issues);
            if (system.trigger.enabled)
                common.Trigger = LumenFxTriggers.Build(system.trigger, root, mapper, settings, plan,
                    settings.EffectName + "/" + UniqueTriggerPrefix(usedEmitterNames, baseName), context, issues);
            if (system.lights.enabled)
                common.Lights = LumenFxLights.Build(system.lights, mapper, settings, plan,
                    UniqueMaterialId(plan, baseName + "_light_cookie"), usedTexturePaths, context, issues);
            if ((emitSprite || emitMesh) && system.customData.enabled)
                common.CustomData = LumenFxCustomData.Build(system.customData, renderer, context, issues);
            if (system.noise.enabled)
                common.Noise = LumenFxNoise.Build(system.noise, mapper, context + " Noise", issues);
            if (system.externalForces.enabled)
                common.ExternalForces = LumenFxExternalForces.Build(system.externalForces, root, mapper,
                    context + " External Forces", issues);

            if (emitRibbon && !perParticle && !emitMesh && (trails.sizeAffectsWidth || trails.sizeAffectsLifetime)
                && !common.Width.ContentEquals(common.Height))
                issues.Add(new ExportIssue(context, "Ribbon size inheritance requires a defined particle size for unequal X/Y"));

            string particleSource = null;
            if (emitSprite && spriteMaterial != null)
                particleSource = AddEmitter(plan, common, spriteMaterial, "SPRITE", baseName, false, usedEmitterNames,
                    billboard: BuildBillboard(renderer, alignAtBirth, context, issues));
            if (emitMesh && meshMaterial != null)
            {
                var alignment = RendererAlignment(renderer, alignAtBirth, context, issues);
                common.MeshMotion["alignment"] = alignment;
                common.MeshMotion["order"] = RendererOrder(renderer, context, issues);
                Vector2 scale = meshMaterial.TextureScale, offset = meshMaterial.TextureOffset;
                if (!Finite(scale.x) || !Finite(scale.y) || !Finite(offset.x) || !Finite(offset.y))
                    issues.Add(new ExportIssue(context, "Mesh material UV transform must be finite"));
                common.MeshMotion["scale_u"] = scale.x;
                common.MeshMotion["scale_v"] = scale.y;
                common.MeshMotion["offset_u"] = offset.x;
                common.MeshMotion["offset_v"] = offset.y;
                var flip = renderer.flip;
                if (!(flip.x >= 0 && flip.x <= 1 && flip.y >= 0 && flip.y <= 1 && flip.z >= 0 && flip.z <= 1))
                    issues.Add(new ExportIssue(context, "Mesh Flip requires three probabilities between zero and one"));
                if (flip != Vector3.zero) common.MeshMotion["flip"] = new[] { flip.x, flip.y, flip.z };
#if UNITY_2021_2_OR_NEWER
                if (renderer.meshDistribution == ParticleSystemMeshDistribution.NonUniformRandom)
                {
                    var weights = new float[renderer.meshCount];
                    if (renderer.GetMeshWeightings(weights) != weights.Length
                        || Array.Exists(weights, weight => !Finite(weight) || weight < 0))
                        issues.Add(new ExportIssue(context, "Mesh weightings must contain one finite nonnegative weight per mesh"));
                    else
                        common.MeshMotion["weights"] = weights;
                }
#endif
                string name = UniqueEmitterName(usedEmitterNames, baseName);
                Dictionary<string, object> mesh;
                if (LumenFxMeshAssets.Build(settings, plan, name, renderer, meshMaterial, mapper, context, issues, out mesh))
                    particleSource = AddEmitter(plan, common, meshMaterial, "MESH", name, false, usedEmitterNames, mesh: mesh);
            }
            if (emitRibbon && ribbonMaterial != null)
                AddEmitter(plan, common, ribbonMaterial, "RIBBON",
                    baseName + "_trail", !perParticle && trails.attachRibbonsToTransform, usedEmitterNames, particleSource,
                    BuildParticleTrail(trails, context, issues, perParticle ? "per_particle" : "ribbon"),
                    perParticle ? (int?)null : trails.ribbonCount, BuildTrailTexture(trails, ribbonMaterial, context, issues));
            if (!emitSprite && !emitMesh && !emitRibbon)
                AddEmitter(plan, common, null, "SIMULATION", baseName, false, usedEmitterNames);
        }

        private static object Sum(ScalarPlan a, ScalarPlan b)
        {
            if (a.IsZero()) return b.ToJson();
            if (b.IsZero()) return a.ToJson();
            return new Dictionary<string, object> { { "add", new object[] { a.ToJson(), b.ToJson() } } };
        }

        private static object[] VectorCurves(ScalarPlan[] first, ScalarPlan[] second)
        {
            return new object[] { Sum(first[0], second[0]), Sum(first[1], second[1]), Sum(first[2], second[2]) };
        }

        private static EmitterCommon BuildCommon(ParticleSystem system,
            bool needsAtlas, int meshCount, AxisMapper mapper, string context, List<ExportIssue> issues)
        {
            bool mesh = meshCount != 0;
            int errorsBefore = issues.Count;
            ParticleSystem.MainModule main = system.main;
            EmitterCommon common = new EmitterCommon();
            Dictionary<string, object> fields = common.Fields;

            float duration = main.duration;
            if (!(duration > 0) || float.IsInfinity(duration))
                issues.Add(new ExportIssue(context, "Duration must be a finite positive value"));
            fields["duration"] = duration;
            fields["loop"] = main.loop;

            common.Prewarm = main.loop && main.prewarm;
            if (!system.useAutoRandomSeed) common.RandomSeed = system.randomSeed;
            common.MaximumParticleDeltaTime = Time.maximumParticleDeltaTime;
            if (!Finite(common.MaximumParticleDeltaTime) || common.MaximumParticleDeltaTime <= 0)
                issues.Add(new ExportIssue(context, "Maximum Particle Timestep must be positive and finite"));
            common.SimulationSpeed = main.simulationSpeed;
            if (!(common.SimulationSpeed >= 0) || float.IsInfinity(common.SimulationSpeed))
                issues.Add(new ExportIssue(context, "Simulation Speed must be finite and non-negative"));
            common.FlipRotation = main.flipRotation;
            if (!(common.FlipRotation >= 0 && common.FlipRotation <= 1))
                issues.Add(new ExportIssue(context, "Flip Rotation must be between zero and one"));
            common.PauseUntilReplaced = main.ringBufferMode == ParticleSystemRingBufferMode.PauseUntilReplaced;
            if (main.ringBufferMode == ParticleSystemRingBufferMode.LoopUntilReplaced)
            {
                var range = main.ringBufferLoopRange;
                if (!(range.x >= 0 && range.x <= range.y && range.y <= 1))
                    issues.Add(new ExportIssue(context, "Ring Buffer lifetime loop must be an ordered range between zero and one"));
                else
                    common.LifetimeLoop = new[] { range.x, range.y };
            }

            bool local = main.simulationSpace != ParticleSystemSimulationSpace.World;
            fields["local_space"] = local;
            if (!local && system.inheritVelocity.enabled)
                common.InheritVelocity = BuildInheritVelocity(system.inheritVelocity, main.emitterVelocityMode, context, issues);
            if (system.limitVelocityOverLifetime.enabled)
                common.VelocityLimit = BuildVelocityLimit(system.limitVelocityOverLifetime, main.simulationSpace,
                    system.transform, main.customSimulationSpace, mapper, context, issues);

            int maximumParticles = main.maxParticles;
            if (maximumParticles < 1 || maximumParticles > 262144)
                issues.Add(new ExportIssue(context, "Max Particles must be between 1 and 262144"));
            fields["max_particles"] = maximumParticles;

            ScalarPlan startSpeed = LumenFxCurves.FromMinMaxCurve(main.startSpeed, 1,
                context + " startSpeed", issues);
            RejectNegative(startSpeed, context, "Start Speed", issues);
            fields["start_speed"] = startSpeed.ToJson();
            fields["start_direction"] = VectorJson(mapper.Direction(Vector3.forward));

            ScalarPlan delay = common.Prewarm ? ScalarPlan.Constant(0) : LumenFxCurves.FromMinMaxCurve(main.startDelay, 1,
                context + " startDelay", issues);
            double delayMinimum, delayMaximum;
            if (!delay.TryGetConstantRange(out delayMinimum, out delayMaximum)
                || !Finite(delayMinimum) || !Finite(delayMaximum)
                || delayMinimum < 0 || delayMaximum < delayMinimum)
                issues.Add(new ExportIssue(context, "Start Delay must use finite non-negative constants in ascending order"));
            fields["delay"] = delayMinimum;
            if (delayMaximum > delayMinimum) common.StartDelayMaximum = delayMaximum;

            ScalarPlan lifetime = LumenFxCurves.FromMinMaxCurve(main.startLifetime, 1,
                context + " startLifetime", issues);
            RejectNegative(lifetime, context, "Start Lifetime", issues);
            fields["lifetime"] = lifetime.ToJson();

            ScalarPlan width, height;
            if (main.startSize3D)
            {
                width = LumenFxCurves.FromMinMaxCurve(main.startSizeX, 1, context + " startSizeX", issues);
                height = LumenFxCurves.FromMinMaxCurve(main.startSizeY, 1, context + " startSizeY", issues);
            }
            else
            {
                ScalarPlan size = LumenFxCurves.FromMinMaxCurve(main.startSize, 1, context + " startSize", issues);
                width = size;
                height = size;
            }
            RejectNegative(width, context, "Start Size X", issues);
            RejectNegative(height, context, "Start Size Y", issues);
            common.Width = width;
            common.Height = height;
            fields["width"] = width.ToJson();
            fields["height"] = height.ToJson();
            ScalarPlan depth = main.startSize3D
                ? LumenFxCurves.FromMinMaxCurve(main.startSizeZ, 1, context + " startSizeZ", issues) : width;
            RejectNegative(depth, context, "Start Size Z", issues);
            if (mesh)
            {
                common.MeshMotion = new Dictionary<string, object>
                {
                    { "depth", depth.ToJson() },
                    { "forward_to_up", mapper.ConvertsForwardToUp }
                };
            }
            else
                common.StartDepth = new Dictionary<string, object>
                {
                    { "size", depth.ToJson() },
                    { "forward_to_up", mapper.ConvertsForwardToUp }
                };

            ScalarPlan rotation;
            if (main.startRotation3D)
            {
                ScalarPlan x = LumenFxCurves.FromMinMaxCurve(main.startRotationX, 1, context + " startRotationX", issues);
                ScalarPlan y = LumenFxCurves.FromMinMaxCurve(main.startRotationY, 1, context + " startRotationY", issues);
                ScalarPlan z = LumenFxCurves.FromMinMaxCurve(main.startRotationZ, 1, context + " startRotationZ", issues);
                if (mesh)
                    common.MeshMotion["start_rotation_xy"] = new[] { x.ToJson(), y.ToJson() };
                else if (!x.IsZero() || !y.IsZero())
                    common.BillboardMotion["start_rotation_xy"] = new[] { x.ToJson(), y.ToJson() };
                rotation = z.Multiply(-1);
            }
            else
            {
                rotation = LumenFxCurves.FromMinMaxCurve(main.startRotation, 1, context + " startRotation", issues).Multiply(-1);
            }
            fields["rotation"] = rotation.ToJson();

            common.StartColor = LumenFxCurves.FromStartColor(main.startColor,
                context + " startColor", issues);
            ParticleSystem.ColorOverLifetimeModule colorOverLifetime = system.colorOverLifetime;
            common.ColorOverLife = colorOverLifetime.enabled
                ? LumenFxCurves.FromColorOverLifetime(colorOverLifetime.color,
                    context + " colorOverLifetime", issues)
                : ColorPlan.Constant(Color.white);
            fields["color"] = common.ColorOverLife.ToJson();

            ParticleSystem.EmissionModule emission = system.emission;
            ScalarPlan rate = emission.enabled
                ? LumenFxCurves.FromMinMaxCurve(emission.rateOverTime, 1, context + " rateOverTime", issues)
                : ScalarPlan.Constant(0);
            RejectNegative(rate, context, "Rate over Time", issues);
            fields["rate"] = rate.ToJson();

            double distanceSpacing = 0;
            if (emission.enabled)
            {
                ScalarPlan distance = LumenFxCurves.FromMinMaxCurve(emission.rateOverDistance, 1,
                    context + " rateOverDistance", issues);
                RejectNegative(distance, context, "Rate over Distance", issues);
                double minimum, maximum;
                if (distance.TryGetConstantRange(out minimum, out maximum)
                    && CurvePlan.Nearly(minimum, maximum))
                {
                    if (minimum > 0)
                    {
                        distanceSpacing = 1.0 / minimum;
                        if (!Finite(distanceSpacing))
                        {
                            issues.Add(new ExportIssue(context, "Rate over Distance is too small to represent"));
                            distanceSpacing = 0;
                        }
                    }
                }
                else if (!distance.IsZero())
                {
                    fields["rate_over_distance"] = distance.ToJson();
                }
            }
            fields["distance_spacing"] = distanceSpacing;
            fields["bursts"] = BuildBursts(emission, context, issues);

            ScalarPlan[] velocityOverLife = BuildVelocity(system, context, issues);
            ScalarPlan[] forceOverLife = BuildForce(system, context, issues);

            ScalarPlan gravityModifier = LumenFxCurves.FromMinMaxCurve(main.gravityModifier, 1,
                context + " gravityModifier", issues);
            ScalarPlan[] mappedVelocity = mapper.MapScalars(velocityOverLife[0], velocityOverLife[1], velocityOverLife[2]);
            ScalarPlan[] mappedForce = mapper.MapScalars(forceOverLife[0], forceOverLife[1], forceOverLife[2]);
            var gravity = mapper.Vector(Physics.gravity);
            var mappedGravity = new[] { gravityModifier.Multiply(gravity.x), gravityModifier.Multiply(gravity.y),
                gravityModifier.Multiply(gravity.z) };
            var zero = new[] { ScalarPlan.Constant(0), ScalarPlan.Constant(0), ScalarPlan.Constant(0) };
            var spaceMotion = new Dictionary<string, object>();
            if (system.velocityOverLifetime.enabled)
            {
                ScalarPlan speedModifier = LumenFxCurves.FromMinMaxCurve(system.velocityOverLifetime.speedModifier, 1,
                    context + " velocityOverLifetime.speedModifier", issues);
                if (!IsOne(speedModifier)) spaceMotion["speed_modifier"] = speedModifier.ToJson();
                WriteOrbit(system.velocityOverLifetime, mapper, context + " velocityOverLifetime", issues, spaceMotion);
            }
            bool hasVelocity = !mappedVelocity[0].IsZero() || !mappedVelocity[1].IsZero() || !mappedVelocity[2].IsZero();
            bool velocityMatches = !system.velocityOverLifetime.enabled
                || system.velocityOverLifetime.space == main.simulationSpace;
            fields["velocity_over_life"] = VectorCurves(velocityMatches ? mappedVelocity : zero, zero);
            if (!velocityMatches && hasVelocity)
            {
                spaceMotion["velocity"] = VectorCurves(mappedVelocity, zero);
                var velocitySpace = BuildSpaceTransform(main.simulationSpace, system.velocityOverLifetime.space,
                    system.transform, main.customSimulationSpace, mapper);
                if (velocitySpace != null) spaceMotion["velocity_space"] = velocitySpace;
            }

            bool forceActive = system.forceOverLifetime.enabled;
            bool randomizedForce = forceActive && system.forceOverLifetime.randomized;
            if (randomizedForce) spaceMotion["randomized_force"] = true;
            bool hasForce = !mappedForce[0].IsZero() || !mappedForce[1].IsZero() || !mappedForce[2].IsZero();
            bool forceMatches = !forceActive || system.forceOverLifetime.space == main.simulationSpace;
            if (!local)
            {
                fields["force_over_life"] = VectorCurves(forceMatches ? mappedForce : zero, mappedGravity);
                if (!forceMatches && hasForce)
                {
                    spaceMotion["force"] = VectorCurves(mappedForce, zero);
                    var forceSpace = BuildSpaceTransform(main.simulationSpace, system.forceOverLifetime.space,
                        system.transform, main.customSimulationSpace, mapper);
                    if (forceSpace != null) spaceMotion["force_space"] = forceSpace;
                }
            }
            else if (gravityModifier.IsZero())
            {
                fields["force_over_life"] = VectorCurves(forceMatches ? mappedForce : zero, zero);
                if (!forceMatches && hasForce)
                {
                    spaceMotion["force"] = VectorCurves(mappedForce, zero);
                    var forceSpace = BuildSpaceTransform(main.simulationSpace, system.forceOverLifetime.space,
                        system.transform, main.customSimulationSpace, mapper);
                    if (forceSpace != null) spaceMotion["force_space"] = forceSpace;
                }
            }
            else if (forceMatches || !hasForce)
            {
                fields["force_over_life"] = VectorCurves(forceMatches ? mappedForce : zero, zero);
                spaceMotion["force"] = VectorCurves(zero, mappedGravity);
                spaceMotion["force_space"] = new Dictionary<string, object> { { "fixed", false } };
            }
            else if (system.forceOverLifetime.space == ParticleSystemSimulationSpace.World)
            {
                fields["force_over_life"] = VectorCurves(zero, zero);
                spaceMotion["force"] = VectorCurves(mappedForce, mappedGravity);
                spaceMotion["force_space"] = new Dictionary<string, object> { { "fixed", false } };
            }
            else
            {
                Quaternion relative = RelativeSpaceRotation(main.simulationSpace, system.forceOverLifetime.space,
                    system.transform, main.customSimulationSpace, mapper);
                if (Quaternion.Angle(relative, Quaternion.identity) <= 0.01f)
                {
                    fields["force_over_life"] = VectorCurves(mappedForce, zero);
                    spaceMotion["force"] = VectorCurves(zero, mappedGravity);
                    spaceMotion["force_space"] = new Dictionary<string, object> { { "fixed", false } };
                }
                else
                {
                    if (randomizedForce)
                    {
                        issues.Add(new ExportIssue(context,
                            "Randomized Force over Lifetime in rotated " + system.forceOverLifetime.space
                            + " space cannot be combined with non-zero Gravity Modifier"));
                    }
                    fields["force_over_life"] = RotateVectorCurves(relative, mappedForce);
                    spaceMotion["force"] = VectorCurves(zero, mappedGravity);
                    spaceMotion["force_space"] = new Dictionary<string, object> { { "fixed", false } };
                }
            }
            if (spaceMotion.Count != 0) common.SpaceMotion = spaceMotion;
            fields["velocity_min"] = new object[] { 0.0, 0.0, 0.0 };
            fields["velocity_max"] = new object[] { 0.0, 0.0, 0.0 };
            fields["acceleration"] = new object[] { 0.0, 0.0, 0.0 };
            fields["drag"] = 0.0;

            ScalarPlan sizeOverLife = ScalarPlan.Constant(1);
            ParticleSystem.SizeOverLifetimeModule sizeModule = system.sizeOverLifetime;
            if (sizeModule.enabled)
            {
                if (sizeModule.separateAxes)
                {
                    ScalarPlan x = LumenFxCurves.FromMinMaxCurve(sizeModule.x, 1, context + " sizeOverLifetime.x", issues);
                    ScalarPlan y = LumenFxCurves.FromMinMaxCurve(sizeModule.y, 1, context + " sizeOverLifetime.y", issues);
                    ScalarPlan z = LumenFxCurves.FromMinMaxCurve(sizeModule.z, 1, context + " sizeOverLifetime.z", issues);
                    RejectNegative(x, context, "Size over Lifetime X", issues);
                    RejectNegative(y, context, "Size over Lifetime Y", issues);
                    if (mesh) RejectNegative(z, context, "Size over Lifetime Z", issues);
                    common.SizeOverLifetimeAxes = new[] { x.ToJson(), y.ToJson(), z.ToJson() };
                    sizeOverLife = x;
                }
                else
                {
                    sizeOverLife = LumenFxCurves.FromMinMaxCurve(sizeModule.size, 1,
                        context + " sizeOverLifetime", issues);
                }
            }
            fields["size_over_life"] = sizeOverLife.ToJson();

            ScalarPlan angular = ScalarPlan.Constant(0);
            ParticleSystem.RotationOverLifetimeModule rotationModule = system.rotationOverLifetime;
            if (rotationModule.enabled)
            {
                ScalarPlan z;
                if (rotationModule.separateAxes)
                {
                    ScalarPlan x = LumenFxCurves.FromMinMaxCurve(rotationModule.x, 1, context + " rotationOverLifetime.x", issues);
                    ScalarPlan y = LumenFxCurves.FromMinMaxCurve(rotationModule.y, 1, context + " rotationOverLifetime.y", issues);
                    z = LumenFxCurves.FromMinMaxCurve(rotationModule.z, 1, context + " rotationOverLifetime.z", issues);
                    if (mesh)
                        common.MeshMotion["angular_velocity_xy"] = new[] { x.ToJson(), y.ToJson() };
                    else if (!x.IsZero() || !y.IsZero())
                        common.BillboardMotion["angular_velocity_xy"] = new[] { x.ToJson(), y.ToJson() };
                }
                else
                {
                    z = LumenFxCurves.FromMinMaxCurve(rotationModule.z, 1, context + " rotationOverLifetime.z", issues);
                }

                angular = z.Multiply(-1);
            }
            fields["angular_velocity"] = angular.ToJson();

            common.SpeedModules = LumenFxSpeedModules.Build(system, context, issues);
            if (system.lifetimeByEmitterSpeed.enabled)
                common.LifetimeByEmitterSpeed = LumenFxSpeedModules.Lifetime(system.lifetimeByEmitterSpeed,
                    main.emitterVelocityMode, context, issues);

            fields["custom_data"] = new object[] { 0.0, 0.0, 0.0, 0.0 };
            fields["noise"] = new Dictionary<string, object>
            {
                { "strength", new object[] { 0.0, 0.0, 0.0 } },
                { "frequency", 1.0 },
                { "scroll", new object[] { 0.0, 0.0, 0.0 } }
            };
            fields["subdivisions"] = 1;

            BuildAtlas(system.textureSheetAnimation, needsAtlas, meshCount, context, issues, common);

            if (issues.Count != errorsBefore) return null;
            return common;
        }

        private static Dictionary<string, object> BuildInheritVelocity(ParticleSystem.InheritVelocityModule module,
            ParticleSystemEmitterVelocityMode source, string context, List<ExportIssue> issues)
        {
            ScalarPlan multiplier = LumenFxCurves.FromMinMaxCurve(module.curve, 1, context + " inheritVelocity.curve", issues);
            return new Dictionary<string, object>
            {
                { "mode", module.mode == ParticleSystemInheritVelocityMode.Current ? "current" : "initial" },
                { "source", EmitterVelocitySource(source, context + " Inherit Velocity", issues) },
                { "multiplier", multiplier.ToJson() }
            };
        }

        internal static string EmitterVelocitySource(ParticleSystemEmitterVelocityMode source, string context, List<ExportIssue> issues)
        {
            if (source == ParticleSystemEmitterVelocityMode.Transform) return "transform";
            if (source == ParticleSystemEmitterVelocityMode.Rigidbody) return "rigidbody";
            issues.Add(new ExportIssue(context, "Unsupported emitter velocity source " + source));
            return null;
        }

        private static ScalarPlan[] BuildVelocity(ParticleSystem system, string context,
            List<ExportIssue> issues)
        {
            ScalarPlan zero = ScalarPlan.Constant(0);
            ScalarPlan[] result = { zero, zero, zero };
            ParticleSystem.VelocityOverLifetimeModule velocity = system.velocityOverLifetime;
            if (!velocity.enabled) return result;

            result[0] = LumenFxCurves.FromMinMaxCurve(velocity.x, 1, context + " velocityOverLifetime.x", issues);
            result[1] = LumenFxCurves.FromMinMaxCurve(velocity.y, 1, context + " velocityOverLifetime.y", issues);
            result[2] = LumenFxCurves.FromMinMaxCurve(velocity.z, 1, context + " velocityOverLifetime.z", issues);
            return result;
        }

        private static Quaternion RelativeSpaceRotation(ParticleSystemSimulationSpace simSpace,
            ParticleSystemSimulationSpace moduleSpace, Transform systemTransform, Transform customSpace,
            AxisMapper mapper)
        {
            Transform simRef = simSpace == ParticleSystemSimulationSpace.Custom && customSpace != null
                ? customSpace : systemTransform;
            Transform modRef = moduleSpace == ParticleSystemSimulationSpace.Custom && customSpace != null
                ? customSpace : systemTransform;
            return mapper.Orientation(Quaternion.Inverse(simRef.rotation) * modRef.rotation);
        }

        private static Dictionary<string, object> BuildSpaceTransform(ParticleSystemSimulationSpace simSpace,
            ParticleSystemSimulationSpace moduleSpace, Transform systemTransform, Transform customSpace,
            AxisMapper mapper)
        {
            if (moduleSpace == simSpace) return null;
            if (simSpace == ParticleSystemSimulationSpace.World)
            {
                if (moduleSpace == ParticleSystemSimulationSpace.Local || customSpace == null)
                    return new Dictionary<string, object> { { "fixed", false } };
                Quaternion rel = mapper.Orientation(Quaternion.Inverse(systemTransform.rotation) * customSpace.rotation);
                return SpaceTransformJson(false, rel);
            }
            if (moduleSpace == ParticleSystemSimulationSpace.World)
                return new Dictionary<string, object> { { "fixed", false } };
            Quaternion fixedRel = RelativeSpaceRotation(simSpace, moduleSpace, systemTransform, customSpace, mapper);
            return SpaceTransformJson(true, fixedRel);
        }

        private static Dictionary<string, object> SpaceTransformJson(bool isFixed, Quaternion rotation)
        {
            var dict = new Dictionary<string, object> { { "fixed", isFixed } };
            if (Quaternion.Angle(rotation, Quaternion.identity) > 0.01f)
                dict["rotation"] = new object[] { rotation.x, rotation.y, rotation.z, rotation.w };
            return dict;
        }

        private static object[] RotateVectorCurves(Quaternion rotation, ScalarPlan[] vectors)
        {
            Matrix4x4 matrix = Matrix4x4.Rotate(rotation);
            return new[]
            {
                LinearSumJson(vectors, matrix.m00, matrix.m01, matrix.m02),
                LinearSumJson(vectors, matrix.m10, matrix.m11, matrix.m12),
                LinearSumJson(vectors, matrix.m20, matrix.m21, matrix.m22)
            };
        }

        private static object LinearSumJson(ScalarPlan[] vectors, float c0, float c1, float c2)
        {
            var terms = new List<object>();
            AddScaledTerm(terms, vectors[0], c0);
            AddScaledTerm(terms, vectors[1], c1);
            AddScaledTerm(terms, vectors[2], c2);
            if (terms.Count == 0) return 0.0;
            if (terms.Count == 1) return terms[0];
            return new Dictionary<string, object> { { "add", terms } };
        }

        private static void AddScaledTerm(List<object> terms, ScalarPlan plan, float coefficient)
        {
            if (Mathf.Abs(coefficient) <= 1e-6f || plan.IsZero()) return;
            terms.Add((Mathf.Abs(coefficient - 1f) <= 1e-6f ? plan : plan.Multiply(coefficient)).ToJson());
        }

        private static void WriteOrbit(ParticleSystem.VelocityOverLifetimeModule velocity, AxisMapper mapper,
            string context, List<ExportIssue> issues, Dictionary<string, object> spaceMotion)
        {
            // The default axis map reflects handedness. The forward-to-up map is a proper rotation.
            double angularSign = mapper.ConvertsForwardToUp ? 1 : -1;
            var angular = mapper.MapScalars(
                LumenFxCurves.FromMinMaxCurve(velocity.orbitalX, angularSign, context + ".orbitalX", issues),
                LumenFxCurves.FromMinMaxCurve(velocity.orbitalY, angularSign, context + ".orbitalY", issues),
                LumenFxCurves.FromMinMaxCurve(velocity.orbitalZ, angularSign, context + ".orbitalZ", issues));
            var radial = LumenFxCurves.FromMinMaxCurve(velocity.radial, 1, context + ".radial", issues);
            if (angular[0].IsZero() && angular[1].IsZero() && angular[2].IsZero() && radial.IsZero()) return;

            var offset = mapper.MapScalars(
                LumenFxCurves.FromMinMaxCurve(velocity.orbitalOffsetX, 1, context + ".orbitalOffsetX", issues),
                LumenFxCurves.FromMinMaxCurve(velocity.orbitalOffsetY, 1, context + ".orbitalOffsetY", issues),
                LumenFxCurves.FromMinMaxCurve(velocity.orbitalOffsetZ, 1, context + ".orbitalOffsetZ", issues));
            spaceMotion["orbit"] = new Dictionary<string, object>
            {
                { "angular", new[] { angular[0].ToJson(), angular[1].ToJson(), angular[2].ToJson() } },
                { "offset", new[] { offset[0].ToJson(), offset[1].ToJson(), offset[2].ToJson() } },
                { "radial", radial.ToJson() }
            };
        }

        private static Dictionary<string, object> BuildVelocityLimit(ParticleSystem.LimitVelocityOverLifetimeModule module,
            ParticleSystemSimulationSpace simSpace, Transform systemTransform, Transform customSpace,
            AxisMapper mapper, string context, List<ExportIssue> issues)
        {
            if (!Finite(module.dampen) || module.dampen < 0 || module.dampen > 1)
                issues.Add(new ExportIssue(context, "Velocity Limit Dampen must be between 0 and 1"));
            ScalarPlan drag = LumenFxCurves.FromMinMaxCurve(module.drag, 1, context + " limitVelocity.drag", issues);
            RejectNegative(drag, context, "Velocity Limit Drag", issues);
            var result = new Dictionary<string, object>
            {
                { "dampen", module.dampen },
                { "drag", drag.ToJson() },
                { "multiply_by_size", module.multiplyDragByParticleSize },
                { "multiply_by_velocity", module.multiplyDragByParticleVelocity }
            };
            if (module.separateAxes)
            {
                var x = LumenFxCurves.FromMinMaxCurve(module.limitX, 1, context + " limitVelocity.limitX", issues);
                var y = LumenFxCurves.FromMinMaxCurve(module.limitY, 1, context + " limitVelocity.limitY", issues);
                var z = LumenFxCurves.FromMinMaxCurve(module.limitZ, 1, context + " limitVelocity.limitZ", issues);
                RejectNegative(x, context, "Velocity Limit X", issues);
                RejectNegative(y, context, "Velocity Limit Y", issues);
                RejectNegative(z, context, "Velocity Limit Z", issues);
                var axes = mapper.MapScalars(x, y, z, false);
                result["axes"] = new object[] { axes[0].ToJson(), axes[1].ToJson(), axes[2].ToJson() };
                result["local_space"] = module.space != ParticleSystemSimulationSpace.World;
                var spaceTransform = BuildSpaceTransform(simSpace, module.space, systemTransform, customSpace, mapper);
                if (spaceTransform != null) result["space_transform"] = spaceTransform;
            }
            else
            {
                var speed = LumenFxCurves.FromMinMaxCurve(module.limit, 1, context + " limitVelocity.limit", issues);
                RejectNegative(speed, context, "Velocity Limit Speed", issues);
                result["speed"] = speed.ToJson();
            }
            return result;
        }

        private static ScalarPlan[] BuildForce(ParticleSystem system, string context,
            List<ExportIssue> issues)
        {
            ScalarPlan zero = ScalarPlan.Constant(0);
            ScalarPlan[] result = { zero, zero, zero };
            ParticleSystem.ForceOverLifetimeModule force = system.forceOverLifetime;
            if (!force.enabled) return result;

            result[0] = LumenFxCurves.FromMinMaxCurve(force.x, 1, context + " forceOverLifetime.x", issues);
            result[1] = LumenFxCurves.FromMinMaxCurve(force.y, 1, context + " forceOverLifetime.y", issues);
            result[2] = LumenFxCurves.FromMinMaxCurve(force.z, 1, context + " forceOverLifetime.z", issues);
            return result;
        }

        private static List<object> BuildBursts(ParticleSystem.EmissionModule emission,
            string context, List<ExportIssue> issues)
        {
            List<object> result = new List<object>();
            if (!emission.enabled || emission.burstCount <= 0) return result;

            ParticleSystem.Burst[] bursts = new ParticleSystem.Burst[emission.burstCount];
            emission.GetBursts(bursts);
            foreach (ParticleSystem.Burst burst in bursts)
            {
                if (!Finite(burst.probability) || burst.probability < 0 || burst.probability > 1)
                    issues.Add(new ExportIssue(context, "Burst probability must be between 0 and 1"));
                if (burst.cycleCount < 0 || burst.cycleCount == 0 && !(burst.repeatInterval > 0))
                {
                    issues.Add(new ExportIssue(context, "An unlimited Burst requires a positive repeat interval"));
                    continue;
                }
                if (!Finite(burst.time) || !Finite(burst.repeatInterval))
                    issues.Add(new ExportIssue(context, "Burst time/interval is not finite"));
                if (burst.time < 0) issues.Add(new ExportIssue(context, "Burst time cannot be negative"));
                if (burst.repeatInterval < 0) issues.Add(new ExportIssue(context, "Burst repeat interval cannot be negative"));

                ScalarPlan count = LumenFxCurves.FromMinMaxCurve(burst.count, 1,
                    context + " burst count", issues);
                var group = new Dictionary<string, object>
                {
                    { "time", burst.time },
                    { "cycles", burst.cycleCount },
                    { "interval", burst.repeatInterval },
                    { "probability", burst.probability }
                };
                double minimum, maximum;
                if (!count.TryGetConstantRange(out minimum, out maximum))
                {
                    RejectNegative(count, context, "Burst Count", issues);
                    group["count"] = count.ToJson();
                    result.Add(group);
                    continue;
                }
                int minimumCount = (int)Math.Round(minimum);
                int maximumCount = (int)Math.Round(maximum);
                if (!CurvePlan.Nearly(minimum, minimumCount) || !CurvePlan.Nearly(maximum, maximumCount))
                    issues.Add(new ExportIssue(context, "Burst count must be an integer"));
                if (minimumCount < 0 || maximumCount < minimumCount)
                {
                    issues.Add(new ExportIssue(context, "Burst count range is invalid"));
                    continue;
                }

                group["min"] = minimumCount;
                group["max"] = maximumCount;
                result.Add(group);
            }
            return result;
        }

        private static void BuildAtlas(ParticleSystem.TextureSheetAnimationModule sheet, bool needed, int meshCount,
            string context, List<ExportIssue> issues, EmitterCommon common)
        {
            if (!needed || !sheet.enabled) return;
            int uvMask = (int)sheet.uvChannelMask;
            if (uvMask == 0) return;

            bool spriteList = sheet.mode == ParticleSystemAnimationMode.Sprites;
            if (!spriteList && sheet.mode != ParticleSystemAnimationMode.Grid)
            {
                issues.Add(new ExportIssue(context, "Unknown Texture Sheet Animation source " + sheet.mode));
                return;
            }
            bool usesFps = sheet.timeMode == ParticleSystemAnimationTimeMode.FPS;
            bool usesSpeed = sheet.timeMode == ParticleSystemAnimationTimeMode.Speed;
            if (!usesFps && !usesSpeed && sheet.timeMode != ParticleSystemAnimationTimeMode.Lifetime)
            {
                issues.Add(new ExportIssue(context, "Unsupported Texture Sheet Animation time mode " + sheet.timeMode));
                return;
            }
            if (usesFps && (!Finite(sheet.fps) || sheet.fps < 0))
            {
                issues.Add(new ExportIssue(context, "Texture Sheet FPS must be finite and nonnegative"));
                return;
            }
            int columns = spriteList ? sheet.spriteCount : sheet.numTilesX;
            int rows = spriteList ? 1 : sheet.numTilesY;
            if (columns < 1 || rows < 1 || (long)columns * rows > 65536)
            {
                issues.Add(new ExportIssue(context, "Texture Sheet grid must have between 1 and 65536 total tiles"));
                return;
            }
            if (!usesFps && sheet.cycleCount < 1)
            {
                issues.Add(new ExportIssue(context, "Texture Sheet cycle count must be at least 1"));
                return;
            }
            if (usesSpeed)
            {
                Vector2 range = sheet.speedRange;
                if (!Finite(range.x) || !Finite(range.y) || range.x < 0 || range.y <= range.x)
                {
                    issues.Add(new ExportIssue(context, "Texture Sheet Speed needs a finite increasing nonnegative range"));
                    return;
                }
                common.AtlasSpeed = new Dictionary<string, object>
                {
                    { "min", range.x }, { "max", range.y }, { "cycles", sheet.cycleCount }
                };
            }

            if (spriteList)
            {
                common.SpriteFrames = LumenFxSpriteFrames.Read(sheet, context, issues, out common.SpriteSheet);
                if (common.SpriteFrames == null) return;
            }
            else if (sheet.animation != ParticleSystemAnimationType.WholeSheet
                && sheet.animation != ParticleSystemAnimationType.SingleRow)
            {
                issues.Add(new ExportIssue(context, "Unsupported Texture Sheet Animation type " + sheet.animation));
                return;
            }

            bool singleRow = !spriteList && sheet.animation == ParticleSystemAnimationType.SingleRow;
            if (singleRow)
            {
                switch (sheet.rowMode)
                {
                    case ParticleSystemAnimationRowMode.Custom:
                        if (sheet.rowIndex < 0 || sheet.rowIndex >= rows)
                        {
                            issues.Add(new ExportIssue(context, "Texture Sheet row index is out of range"));
                            return;
                        }
                        common.AtlasRow = sheet.rowIndex;
                        break;
                    case ParticleSystemAnimationRowMode.Random:
                        common.AtlasRowSelection = "random";
                        break;
                    case ParticleSystemAnimationRowMode.MeshIndex:
                        if (meshCount == 0)
                        {
                            issues.Add(new ExportIssue(context, "Mesh Index Texture Sheet Animation requires renderer meshes"));
                            return;
                        }
                        common.AtlasRowSelection = "mesh_index";
                        break;
                    default:
                        issues.Add(new ExportIssue(context, "Unknown Texture Sheet row selection mode"));
                        return;
                }
            }

            common.AtlasColumns = columns;
            common.AtlasRows = rows;
            int span = singleRow ? columns : columns * rows;
            ScalarPlan frame = usesFps || usesSpeed ? ScalarPlan.Constant(0) : LumenFxCurves.FromMinMaxCurve(sheet.frameOverTime,
                (double)span * sheet.cycleCount, context + " frameOverTime", issues);
            // Unity's inspector displays the normalized source phase in units of the selected sequence.
            ScalarPlan start = LumenFxCurves.FromMinMaxCurve(sheet.startFrame, span,
                context + " startFrame", issues);
            double startMinimum, startMaximum;
            if (!start.TryGetConstantRange(out startMinimum, out startMaximum))
            {
                issues.Add(new ExportIssue(context, "Start Frame must be constant or a constant random range"));
            }
            else
            {
                frame = frame.Offset(startMinimum, startMaximum);
            }
            common.AtlasFrame = frame;
            if (usesFps) common.AtlasFps = sheet.fps;
            common.UvChannelMask = uvMask;
        }

        private static Dictionary<string, object> BuildTrailTexture(ParticleSystem.TrailModule trails, MaterialPlan material,
            string context, List<ExportIssue> issues)
        {
            string mode;
            switch (trails.textureMode)
            {
                case ParticleSystemTrailTextureMode.Stretch: mode = "stretch"; break;
                case ParticleSystemTrailTextureMode.Tile: mode = "tile"; break;
                case ParticleSystemTrailTextureMode.DistributePerSegment: mode = "distribute_per_segment"; break;
                case ParticleSystemTrailTextureMode.RepeatPerSegment: mode = "repeat_per_segment"; break;
                default:
                    issues.Add(new ExportIssue(context, "Unsupported trail texture mode " + trails.textureMode));
                    return null;
            }
            Vector2 scale = material.TextureScale, offset = material.TextureOffset;
#if UNITY_2022_1_OR_NEWER
            // The Trails module scales U from its origin and V about the center of the strip.
            Vector2 trailScale = trails.textureScale;
            offset.y += scale.y * (1f - trailScale.y) * 0.5f;
            scale = Vector2.Scale(scale, trailScale);
#endif
            if (!Finite(scale.x) || !Finite(scale.y) || !Finite(offset.x) || !Finite(offset.y))
            {
                issues.Add(new ExportIssue(context, "Invalid trail texture scale or offset"));
                return null;
            }
            return new Dictionary<string, object>
            {
                { "mode", mode },
                { "scale_u", scale.x },
                { "scale_v", scale.y },
                { "offset_u", offset.x },
                { "offset_v", offset.y }
            };
        }

        private static Dictionary<string, object> BuildBillboard(ParticleSystemRenderer renderer, bool alignAtBirth, string context,
            List<ExportIssue> issues)
        {
            string mode = renderer.renderMode == ParticleSystemRenderMode.HorizontalBillboard ? "Horizontal"
                : renderer.renderMode == ParticleSystemRenderMode.VerticalBillboard ? "VerticalBillboard"
                : renderer.renderMode == ParticleSystemRenderMode.Stretch ? "StretchedBillboard" : "Billboard";
            if (!Finite(renderer.pivot.x) || !Finite(renderer.pivot.y) || !Mathf.Approximately(renderer.pivot.z, 0))
                issues.Add(new ExportIssue(context, "Billboard pivot requires finite X/Y and zero Z"));
            if (!Finite(renderer.minParticleSize) || renderer.minParticleSize < 0
                || !Finite(renderer.maxParticleSize) || renderer.maxParticleSize < 0)
                issues.Add(new ExportIssue(context, "Billboard viewport size limits must be finite and nonnegative"));
            var result = new Dictionary<string, object>
            {
                { "mode", mode },
                { "alignment", RendererAlignment(renderer, alignAtBirth && renderer.renderMode == ParticleSystemRenderMode.Billboard,
                    context, issues) },
                { "order", RendererOrder(renderer, context, issues) },
                { "pivot_x", renderer.pivot.x },
                { "pivot_y", renderer.pivot.y },
                { "allow_roll", renderer.allowRoll },
                { "viewport_size", new Dictionary<string, object> {
                    { "minimum", renderer.minParticleSize }, { "maximum", renderer.maxParticleSize } } }
            };
            var flip = renderer.flip;
            if (!(flip.x >= 0 && flip.x <= 1 && flip.y >= 0 && flip.y <= 1 && flip.z == 0))
                issues.Add(new ExportIssue(context, "Billboard Flip requires X/Y probabilities and zero Z"));
            if (flip.x != 0) result["flip_x"] = flip.x;
            if (flip.y != 0) result["flip_y"] = flip.y;
            if (renderer.renderMode == ParticleSystemRenderMode.Stretch)
            {
                if (!Finite(renderer.lengthScale) || renderer.lengthScale < 0
                    || !Finite(renderer.velocityScale) || renderer.velocityScale < 0
                    || !Finite(renderer.cameraVelocityScale))
                    issues.Add(new ExportIssue(context, "Invalid billboard stretch scale"));
                result["length_scale"] = renderer.lengthScale;
                result["velocity_scale"] = renderer.velocityScale;
                if (renderer.cameraVelocityScale != 0)
                    result["camera_velocity_scale"] = renderer.cameraVelocityScale;
                result["freeform_stretching"] = renderer.freeformStretching;
                result["rotate_with_stretch"] = renderer.rotateWithStretchDirection;
            }
            return result;
        }

        private static string RendererAlignment(ParticleSystemRenderer renderer, bool alignAtBirth, string context,
            List<ExportIssue> issues)
        {
            if (alignAtBirth)
            {
#if UNITY_6000_0_OR_NEWER
                if (renderer.alignment == ParticleSystemRenderSpace.World) return "world";
#endif
                return "local";
            }
            switch (renderer.alignment)
            {
                case ParticleSystemRenderSpace.View:
                case ParticleSystemRenderSpace.World:
                case ParticleSystemRenderSpace.Local:
                case ParticleSystemRenderSpace.Facing:
                case ParticleSystemRenderSpace.Velocity:
                    return renderer.alignment.ToString().ToLowerInvariant();
                default:
                    issues.Add(new ExportIssue(context, "Unsupported particle alignment " + renderer.alignment));
                    return "world";
            }
        }

        private static string RendererOrder(ParticleSystemRenderer renderer, string context, List<ExportIssue> issues)
        {
            switch (renderer.sortMode)
            {
                case ParticleSystemSortMode.None: return "none";
                case ParticleSystemSortMode.Distance: return "distance";
                case ParticleSystemSortMode.Depth: return "depth";
                case ParticleSystemSortMode.OldestInFront: return "oldest_in_front";
                case ParticleSystemSortMode.YoungestInFront: return "youngest_in_front";
                default:
                    // The reverse modes are named by newer editors and absent from the 2020.3 API.
                    string name = renderer.sortMode.ToString();
                    if (name == "DistanceReverse") return "distance_reverse";
                    if (name == "DepthReverse") return "depth_reverse";
                    issues.Add(new ExportIssue(context, "Unsupported particle sort mode " + renderer.sortMode));
                    return "none";
            }
        }

        private static Dictionary<string, object> BuildParticleTrail(ParticleSystem.TrailModule trails, string context,
            List<ExportIssue> issues, string mode)
        {
            if (trails.mode != ParticleSystemTrailMode.PerParticle && trails.mode != ParticleSystemTrailMode.Ribbon)
                issues.Add(new ExportIssue(context, "Unsupported trail mode " + trails.mode));
            if (!Finite(trails.ratio) || trails.ratio < 0 || trails.ratio > 1
                || !Finite(trails.minVertexDistance) || trails.minVertexDistance < 0)
                issues.Add(new ExportIssue(context, "Invalid trail ratio or minimum vertex distance"));
            ScalarPlan lifetime = LumenFxCurves.FromMinMaxCurve(trails.lifetime, 1, context + " trail lifetime", issues);
            ScalarPlan width = LumenFxCurves.FromMinMaxCurve(trails.widthOverTrail, 1, context + " trail widthOverTrail", issues);
            RejectNegative(lifetime, context, "Trail Lifetime", issues);
            RejectNegative(width, context, "Trail Width over Trail", issues);
            var trail = new Dictionary<string, object>
            {
                { "mode", mode },
                { "ratio", trails.ratio },
                { "minimum_vertex_distance", trails.minVertexDistance },
                { "world_space", trails.worldSpace },
                { "die_with_particles", trails.dieWithParticles },
                { "size_affects_width", trails.sizeAffectsWidth },
                { "size_affects_lifetime", trails.sizeAffectsLifetime },
                { "inherit_color", trails.inheritParticleColor },
                { "lifetime", lifetime.ToJson() },
                { "width_over_trail", width.ToJson() },
                { "color_over_trail", LumenFxCurves.FromColorOverLifetime(trails.colorOverTrail, context + " colorOverTrail", issues).ToJson() },
                { "color_over_lifetime", LumenFxCurves.FromColorOverLifetime(trails.colorOverLifetime, context + " trail colorOverLifetime", issues).ToJson() }
            };
            if (trails.mode == ParticleSystemTrailMode.PerParticle)
            {
                float maximumStep = Time.maximumParticleDeltaTime;
                if (!Finite(maximumStep) || maximumStep <= 0)
                    issues.Add(new ExportIssue(context, "Maximum Particle Timestep must be positive and finite"));
                trail["maximum_particle_delta_time"] = maximumStep;
            }
            return trail;
        }

        private static string AddEmitter(LumenFxPlan plan, EmitterCommon common, MaterialPlan material,
            string rendererType, string baseName, bool attachHead, HashSet<string> usedNames, string source = null,
            Dictionary<string, object> particleTrail = null, int? ribbonCount = null,
            Dictionary<string, object> trailTexture = null, Dictionary<string, object> billboard = null,
            Dictionary<string, object> mesh = null)
        {
            Dictionary<string, object> fields = new Dictionary<string, object>(common.Fields);
            string name = mesh != null ? baseName : UniqueEmitterName(usedNames, baseName);
            fields["name"] = name;
            fields["renderer"] = rendererType;
            if (material != null) fields["material"] = material.Id;
            fields["start_color"] = common.StartColor.ToJson();
            fields["color"] = common.ColorOverLife.ToJson();
            var binding = new Dictionary<string, object>
            {
                { "tint", ColorPlan.Constant(material == null ? Color.white : material.Tint).ToJson() }
            };
            if (material != null && material.Blend != "opaque")
            {
                var sorting = new Dictionary<string, object>
                {
                    { "layer", common.SortingLayer }, { "order", common.SortingOrder },
                    { "queue", material.RenderQueue }, { "distance_bias", common.SortingFudge },
                    { "hierarchy_order", common.HierarchyOrder * 2 + (source != null ? 1 : 0) }
                };
                if (common.SortingGroups != null && common.SortingGroups.Count != 0)
                    sorting["groups"] = common.SortingGroups;
                binding["sorting"] = sorting;
            }
            if (source != null) binding["source"] = source;
            else
            {
                if (common.Prewarm) binding["prewarm"] = true;
                binding["maximum_particle_delta_time"] = common.MaximumParticleDeltaTime;
                if (common.SimulationSpeed != 1) binding["simulation_speed"] = common.SimulationSpeed;
                if (common.FlipRotation != 0) binding["flip_rotation"] = common.FlipRotation;
                if (common.StartDelayMaximum.HasValue) binding["start_delay_maximum"] = common.StartDelayMaximum.Value;
                if (common.RandomSeed.HasValue) binding["random_seed"] = (long)common.RandomSeed.Value;
                if (common.PauseUntilReplaced) binding["pause_until_replaced"] = true;
                if (common.LifetimeLoop != null) binding["lifetime_loop"] = common.LifetimeLoop;
                if (common.InheritVelocity != null) binding["inherit_velocity"] = common.InheritVelocity;
                if (common.VelocityLimit != null) binding["velocity_limit"] = common.VelocityLimit;
                if (common.SpaceMotion != null) binding["space_motion"] = common.SpaceMotion;
                if (common.SpeedModules.Count != 0) binding["speed_modules"] = common.SpeedModules;
                if (common.LifetimeByEmitterSpeed != null) binding["lifetime_by_emitter_speed"] = common.LifetimeByEmitterSpeed;
                if (common.SizeOverLifetimeAxes != null) binding["size_over_life_axes"] = common.SizeOverLifetimeAxes;
                if (common.StartDepth != null) binding["start_depth"] = common.StartDepth;
                if (common.Collision != null)
                    binding[common.Collision.ContainsKey("planes") ? "plane_collision" : "world_collision"] = common.Collision;
                if (common.Trigger != null) binding["trigger"] = common.Trigger;
                if (common.Lights != null) binding["lights"] = common.Lights;
                if (common.Noise != null) binding["noise"] = common.Noise;
                if (common.ExternalForces != null) binding["external_forces"] = common.ExternalForces;
                if (common.EmitterTransform != null) binding["transform"] = common.EmitterTransform;
                if (common.EmissionTransform != null) binding["emission_transform"] = common.EmissionTransform;
            }
            if (particleTrail != null) binding["particle_trail"] = particleTrail;
            if (ribbonCount.HasValue) binding["ribbon_count"] = ribbonCount.Value;
            if (trailTexture != null) binding["trail_texture"] = trailTexture;
            if (billboard == null && source == null && mesh == null
                && common.BillboardMotion.Count != 0)
                billboard = new Dictionary<string, object>();
            if (billboard != null)
            {
                foreach (KeyValuePair<string, object> kv in common.BillboardMotion)
                    billboard[kv.Key] = kv.Value;
                binding["billboard"] = billboard;
            }
            if (mesh != null)
            {
                fields["mesh"] = mesh;
                binding["mesh_motion"] = common.MeshMotion;
            }
            fields["unity_renderer"] = binding;
            fields["uv_mode"] = "stretch";
            fields["uv_distance"] = 1.0;
            fields["attach_head"] = attachHead;
            if (rendererType == "SPRITE" || rendererType == "MESH")
            {
                if (common.CustomData != null)
                {
                    binding["custom_data"] = common.CustomData;
                    material.DynamicSlots = (common.CustomData.ContainsKey("custom1") ? 1 : 0)
                        | (common.CustomData.ContainsKey("custom2") ? 2 : 0);
                }
                if (common.AtlasRowSelection != null) binding["atlas_row_selection"] = common.AtlasRowSelection;
                if (common.AtlasFps.HasValue) binding["atlas_fps"] = common.AtlasFps.Value;
                if (common.AtlasSpeed != null) binding["atlas_speed"] = common.AtlasSpeed;
                if (common.SpriteFrames != null) binding["sprite_frames"] = common.SpriteFrames;
                if (common.UvChannelMask != 1) binding["uv_channel_mask"] = common.UvChannelMask;
                fields["atlas_columns"] = common.AtlasColumns;
                fields["atlas_rows"] = common.AtlasRows;
                fields["atlas_row"] = common.AtlasRow;
                fields["atlas_frame"] = common.AtlasFrame.ToJson();
            }
            else
            {
                fields["atlas_columns"] = 1;
                fields["atlas_rows"] = 1;
                fields["atlas_row"] = -1;
                fields["atlas_frame"] = 0.0;
            }
            plan.Emitters.Add(fields);
            if (material != null) plan.Materials[material.Id] = material;
            return name;
        }

        private static bool IsOne(ScalarPlan value)
        {
            double minimum, maximum;
            return value.TryGetConstantRange(out minimum, out maximum)
                   && CurvePlan.Nearly(minimum, 1) && CurvePlan.Nearly(maximum, 1);
        }

        private static void RejectNegative(ScalarPlan value, string context, string name,
            List<ExportIssue> issues)
        {
            foreach (CurveKeyPlan key in value.Min.Keys)
                if (key.Value < -1e-6)
                {
                    issues.Add(new ExportIssue(context, name + " cannot contain negative values"));
                    return;
                }
            foreach (CurveKeyPlan key in value.Max.Keys)
                if (key.Value < -1e-6)
                {
                    issues.Add(new ExportIssue(context, name + " cannot contain negative values"));
                    return;
                }
        }

        private static bool IsConstantWhite(ParticleSystem.MinMaxGradient gradient)
        {
            switch (gradient.mode)
            {
                case ParticleSystemGradientMode.Color:
                    return IsWhite(gradient.color);
                case ParticleSystemGradientMode.TwoColors:
                    return IsWhite(gradient.colorMin) && IsWhite(gradient.colorMax);
                case ParticleSystemGradientMode.Gradient:
                    return IsConstantColor(gradient.gradient, Color.white);
                case ParticleSystemGradientMode.TwoGradients:
                    return IsConstantColor(gradient.gradientMin, Color.white)
                           && IsConstantColor(gradient.gradientMax, Color.white);
                default:
                    return false;
            }
        }

        private static bool IsWhite(Color color)
        {
            return Mathf.Abs(color.r - 1) <= 1e-4f && Mathf.Abs(color.g - 1) <= 1e-4f
                   && Mathf.Abs(color.b - 1) <= 1e-4f && Mathf.Abs(color.a - 1) <= 1e-4f;
        }

        private static bool IsConstantColor(Gradient gradient, Color expected)
        {
            if (gradient == null) return false;
            foreach (GradientColorKey key in gradient.colorKeys)
                if (!Approximately(key.color, expected)) return false;
            foreach (GradientAlphaKey key in gradient.alphaKeys)
                if (Mathf.Abs(key.alpha - expected.a) > 1e-4f) return false;
            return true;
        }

        private static object[] VectorJson(Vector3 value)
        {
            return new object[] { value.x, value.y, value.z };
        }

        internal static bool Finite(double value)
        {
            return !double.IsNaN(value) && !double.IsInfinity(value);
        }

        internal static bool Finite(Vector3 value)
        {
            return Finite(value.x) && Finite(value.y) && Finite(value.z);
        }

        internal static bool Approximately(Vector3 a, Vector3 b)
        {
            return Mathf.Abs(a.x - b.x) <= 1e-4f && Mathf.Abs(a.y - b.y) <= 1e-4f && Mathf.Abs(a.z - b.z) <= 1e-4f;
        }

        private static bool Approximately(Color a, Color b)
        {
            return Mathf.Abs(a.r - b.r) <= 1e-4f && Mathf.Abs(a.g - b.g) <= 1e-4f
                   && Mathf.Abs(a.b - b.b) <= 1e-4f && Mathf.Abs(a.a - b.a) <= 1e-4f;
        }

        internal static Matrix4x4 UnscaledRootWorld(Transform root)
        {
            return Matrix4x4.TRS(root.position, root.rotation, Vector3.one);
        }

        private static string UniqueTriggerPrefix(HashSet<string> usedNames, string baseName)
        {
            string id = Sanitize(baseName);
            if (string.IsNullOrEmpty(id)) id = "emitter";
            int suffix = 0;
            string prefix = id;
            while (usedNames.Contains(prefix)) prefix = id + "_" + (++suffix + 1);
            return prefix;
        }

        private static Dictionary<string, object> BuildEmitterTransform(Transform root, Transform target,
            ParticleSystemScalingMode scalingMode, bool linked, AxisMapper mapper, string context, List<ExportIssue> issues,
            bool inAnchorScaledSpace = false)
        {
            Matrix4x4 rootFrame = inAnchorScaledSpace ? root.localToWorldMatrix : UnscaledRootWorld(root);
            var relative = rootFrame.inverse * target.localToWorldMatrix;
            Quaternion rotation = Quaternion.Inverse(root.rotation) * target.rotation;
            Vector3 x = relative.MultiplyVector(Vector3.right), y = relative.MultiplyVector(Vector3.up),
                z = relative.MultiplyVector(Vector3.forward);
            Vector3 scale;
            if (scalingMode == ParticleSystemScalingMode.Local)
            {
                scale = target.localScale;
                if (inAnchorScaledSpace)
                {
                    Vector3 anchorScale = root.lossyScale;
                    if (!Finite(anchorScale) || anchorScale.x <= 0 || anchorScale.y <= 0 || anchorScale.z <= 0)
                    {
                        issues.Add(new ExportIssue(context, "Custom simulation space must have a finite, positive scale"));
                        return null;
                    }
                    scale = new Vector3(scale.x / anchorScale.x, scale.y / anchorScale.y, scale.z / anchorScale.z);
                }
            }
            else
            {
                scale = new Vector3(x.magnitude, y.magnitude, z.magnitude);
            }
            bool aligned = Approximately(x.normalized, rotation * Vector3.right)
                && Approximately(y.normalized, rotation * Vector3.up)
                && Approximately(z.normalized, rotation * Vector3.forward);
            if (!Finite(scale) || scale.x <= 0 || scale.y <= 0 || scale.z <= 0
                || scalingMode != ParticleSystemScalingMode.Local && !aligned)
            {
                issues.Add(new ExportIssue(context, "Particle system hierarchy must have a finite, positive, unsheared transform"));
                return null;
            }
            Vector3 position = mapper.Vector(relative.MultiplyPoint3x4(Vector3.zero));
            if (!linked && Approximately(position, Vector3.zero) && Quaternion.Angle(rotation, Quaternion.identity) <= .01f
                && Approximately(scale, Vector3.one)) return null;
            var mapped = mapper.Orientation(rotation);
            scale = mapper.Extent(scale);
            return new Dictionary<string, object>
            {
                { "position", new[] { position.x, position.y, position.z } },
                { "rotation", new[] { mapped.x, mapped.y, mapped.z, mapped.w } },
                { "scale", new[] { scale.x, scale.y, scale.z } },
                { "shape_only_scale", scalingMode == ParticleSystemScalingMode.Shape }
            };
        }

        private static Dictionary<Transform, int> CollectHierarchyOrder(Transform root)
        {
            var order = new Dictionary<Transform, int>();
            int next = 0;
            CollectHierarchyOrder(root, order, ref next);
            return order;
        }

        private static void CollectHierarchyOrder(Transform current, Dictionary<Transform, int> order, ref int next)
        {
            order[current] = next++;
            for (int i = 0; i < current.childCount; i++)
                CollectHierarchyOrder(current.GetChild(i), order, ref next);
        }

        private static List<Dictionary<string, object>> BuildSortingGroups(Transform root, Transform target,
            AxisMapper mapper, Dictionary<Transform, int> hierarchyOrder, string context, List<ExportIssue> issues)
        {
            var chain = new List<Dictionary<string, object>>();
            Matrix4x4 inverseRoot = UnscaledRootWorld(root).inverse;
            Transform current = target;
            while (current != null)
            {
                SortingGroup group = current.GetComponent<SortingGroup>();
                if (group != null && group.enabled)
                {
                    Vector3 localPosition = mapper.Vector(inverseRoot.MultiplyPoint3x4(current.position));
                    if (!Finite(localPosition))
                    {
                        issues.Add(new ExportIssue(context, "Sorting Group '" + PathOf(root, current) + "' must have a finite position"));
                    }
                    else
                    {
                        int order;
                        int groupHierarchy = hierarchyOrder.TryGetValue(current, out order) ? order * 2 : 0;
                        chain.Add(new Dictionary<string, object>
                        {
                            { "id", SortingGroupId(root, current) },
                            { "layer", SortingLayer.GetLayerValueFromID(group.sortingLayerID) },
                            { "order", group.sortingOrder },
                            { "position", new[] { localPosition.x, localPosition.y, localPosition.z } },
                            { "hierarchy_order", groupHierarchy }
                        });
                    }
#if UNITY_2022_2_OR_NEWER
                    if (group.sortAtRoot) break;
#endif
                }
                if (current == root) break;
                current = current.parent;
            }
            if (chain.Count > 1) chain.Reverse();
            return chain;
        }

        private static string SortingGroupId(Transform root, Transform target)
        {
            List<string> parts = new List<string>();
            Transform current = target;
            while (current != null)
            {
                string escaped = EscapeSortingGroupSegment(current.name);
                parts.Add(current == root ? escaped + "#root" : escaped + "#" + current.GetSiblingIndex());
                if (current == root) break;
                current = current.parent;
            }
            parts.Reverse();
            return string.Join("/", parts.ToArray());
        }

        private static string EscapeSortingGroupSegment(string name)
        {
            if (string.IsNullOrEmpty(name)) return string.Empty;
            return name.Replace("%", "%25").Replace("/", "%2F").Replace("#", "%23");
        }

        private static string PathOf(Transform root, Transform target)
        {
            List<string> parts = new List<string>();
            Transform current = target;
            while (current != null)
            {
                parts.Add(current.name);
                if (current == root) break;
                current = current.parent;
            }
            parts.Reverse();
            return string.Join("/", parts.ToArray());
        }

        private static string UniqueMaterialId(LumenFxPlan plan, string baseId)
        {
            string id = Sanitize(baseId);
            if (string.IsNullOrEmpty(id)) id = "material";
            string candidate = id;
            int suffix = 2;
            while (plan.Materials.ContainsKey(candidate)) candidate = id + "_" + suffix++;
            return candidate;
        }

        private static string UniqueEmitterName(HashSet<string> used, string baseName)
        {
            string id = Sanitize(baseName);
            if (string.IsNullOrEmpty(id)) id = "emitter";
            string candidate = id;
            int suffix = 2;
            while (!used.Add(candidate)) candidate = id + "_" + suffix++;
            return candidate;
        }

        private static string Sanitize(string value)
        {
            if (string.IsNullOrEmpty(value)) return "";
            System.Text.StringBuilder builder = new System.Text.StringBuilder(value.Length);
            foreach (char character in value.ToLowerInvariant())
            {
                if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')
                    || character == '_' || character == '-' || character == '.')
                    builder.Append(character);
                else
                    builder.Append('_');
            }
            return builder.ToString().Trim('_');
        }

        private static bool IsValidNamespace(string value)
        {
            if (string.IsNullOrEmpty(value)) return false;
            foreach (char character in value)
                if (!((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')
                      || character == '_' || character == '-' || character == '.')) return false;
            return true;
        }

        private static bool IsValidEffectName(string value)
        {
            return IsValidNamespace(value);
        }
    }
}
#endif
