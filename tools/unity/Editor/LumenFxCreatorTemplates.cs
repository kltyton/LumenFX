#if UNITY_EDITOR
using UnityEditor;
using UnityEngine;
using UnityEngine.Rendering;

namespace LumenFx.UnityExport
{
    /// <summary>
    /// Creates Minecraft-scaled (1 Unity unit = 1 block) LumenFX-ready ParticleSystem presets
    /// using only supported modules and shaders so creators can start authoring and exporting immediately.
    /// </summary>
    internal static class LumenFxCreatorTemplates
    {
        [MenuItem("GameObject/Effects/LumenFX/Spark Burst (Sprite)", false, 10)]
        [MenuItem("Assets/LumenFX/Create Preset/Spark Burst (Sprite)", false, 120)]
        internal static void CreateSparkBurst()
        {
            GameObject root = CreateRoot("lumenfx_spark_burst");
            ParticleSystem ps = root.AddComponent<ParticleSystem>();
            ConfigureCommon(ps, duration: 1.0f, loop: false, maxParticles: 128);

            var main = ps.main;
            main.startLifetime = new ParticleSystem.MinMaxCurve(0.35f, 0.75f);
            main.startSpeed = new ParticleSystem.MinMaxCurve(2.0f, 5.0f);
            main.startSize = new ParticleSystem.MinMaxCurve(0.12f, 0.28f);
            main.startColor = new Color(1.0f, 0.78f, 0.35f, 1.0f);
            main.gravityModifier = 0.6f;

            var emission = ps.emission;
            emission.rateOverTime = 0f;
            emission.SetBursts(new[] { new ParticleSystem.Burst(0f, 24, 36) });

            var shape = ps.shape;
            shape.enabled = true;
            shape.shapeType = ParticleSystemShapeType.Sphere;
            shape.radius = 0.25f;

            var colorOverLife = ps.colorOverLifetime;
            colorOverLife.enabled = true;
            Gradient fade = new Gradient();
            fade.SetKeys(
                new[] { new GradientColorKey(Color.white, 0f), new GradientColorKey(new Color(1f, 0.4f, 0.1f), 1f) },
                new[] { new GradientAlphaKey(1f, 0f), new GradientAlphaKey(0f, 1f) });
            colorOverLife.color = new ParticleSystem.MinMaxGradient(fade);

            var sizeOverLife = ps.sizeOverLifetime;
            sizeOverLife.enabled = true;
            sizeOverLife.size = new ParticleSystem.MinMaxCurve(1f, AnimationCurve.EaseInOut(0f, 1f, 1f, 0f));

            ApplyDefaultMaterial(root.GetComponent<ParticleSystemRenderer>(), additive: true);
            FinalizeCreatedObject(root);
        }

        [MenuItem("GameObject/Effects/LumenFX/Ribbon Trail", false, 11)]
        [MenuItem("Assets/LumenFX/Create Preset/Ribbon Trail", false, 121)]
        internal static void CreateRibbonTrail()
        {
            GameObject root = CreateRoot("lumenfx_ribbon_trail");
            ParticleSystem ps = root.AddComponent<ParticleSystem>();
            ConfigureCommon(ps, duration: 2.0f, loop: true, maxParticles: 256);

            var main = ps.main;
            main.simulationSpace = ParticleSystemSimulationSpace.World;
            main.startLifetime = 0.6f;
            main.startSpeed = 0.2f;
            main.startSize = 0.22f;
            main.startColor = new Color(0.35f, 0.85f, 1.0f, 1.0f);

            var emission = ps.emission;
            emission.rateOverTime = 0f;
            emission.rateOverDistance = 12f;

            var shape = ps.shape;
            shape.enabled = false;

            var trails = ps.trails;
            trails.enabled = true;
            trails.mode = ParticleSystemTrailMode.Ribbon;
            trails.ribbonCount = 1;
            trails.attachRibbonsToTransform = true;
            trails.widthOverTrail = new ParticleSystem.MinMaxCurve(1f, AnimationCurve.Linear(0f, 1f, 1f, 0f));

            Gradient trailGrad = new Gradient();
            trailGrad.SetKeys(
                new[] { new GradientColorKey(new Color(0.55f, 0.95f, 1f), 0f), new GradientColorKey(new Color(0.2f, 0.4f, 1f), 1f) },
                new[] { new GradientAlphaKey(1f, 0f), new GradientAlphaKey(0f, 1f) });
            trails.colorOverTrail = new ParticleSystem.MinMaxGradient(trailGrad);

            ParticleSystemRenderer renderer = root.GetComponent<ParticleSystemRenderer>();
            renderer.renderMode = ParticleSystemRenderMode.None;
            ApplyDefaultMaterial(renderer, additive: true, assignTrail: true);
            FinalizeCreatedObject(root);
        }

        [MenuItem("GameObject/Effects/LumenFX/Aura Ring (Horizontal)", false, 12)]
        [MenuItem("Assets/LumenFX/Create Preset/Aura Ring (Horizontal)", false, 122)]
        internal static void CreateAuraRing()
        {
            GameObject root = CreateRoot("lumenfx_aura_ring");
            ParticleSystem ps = root.AddComponent<ParticleSystem>();
            ConfigureCommon(ps, duration: 3.0f, loop: true, maxParticles: 128);

            var main = ps.main;
            main.simulationSpace = ParticleSystemSimulationSpace.Local;
            main.startLifetime = 1.2f;
            main.startSpeed = 0.0f;
            main.startSize = 0.18f;
            main.startColor = new Color(0.65f, 0.45f, 1.0f, 0.9f);

            var emission = ps.emission;
            emission.rateOverTime = 28f;

            var shape = ps.shape;
            shape.enabled = true;
            shape.shapeType = ParticleSystemShapeType.Circle;
            shape.radius = 1.0f;
            shape.radiusThickness = 0.05f;
            shape.rotation = new Vector3(90f, 0f, 0f);

            var velocity = ps.velocityOverLifetime;
            velocity.enabled = true;
            velocity.y = new ParticleSystem.MinMaxCurve(0.8f);
            velocity.orbitalY = new ParticleSystem.MinMaxCurve(1.8f);

            var colorOverLife = ps.colorOverLifetime;
            colorOverLife.enabled = true;
            Gradient fade = new Gradient();
            fade.SetKeys(
                new[] { new GradientColorKey(Color.white, 0f), new GradientColorKey(Color.white, 1f) },
                new[] { new GradientAlphaKey(0f, 0f), new GradientAlphaKey(1f, 0.25f), new GradientAlphaKey(0f, 1f) });
            colorOverLife.color = new ParticleSystem.MinMaxGradient(fade);

            ParticleSystemRenderer renderer = root.GetComponent<ParticleSystemRenderer>();
            renderer.renderMode = ParticleSystemRenderMode.Billboard;
            ApplyDefaultMaterial(renderer, additive: true);
            FinalizeCreatedObject(root);
        }

        [MenuItem("GameObject/Effects/LumenFX/Magic Stream (Stretched)", false, 13)]
        [MenuItem("Assets/LumenFX/Create Preset/Magic Stream (Stretched)", false, 123)]
        internal static void CreateMagicStream()
        {
            GameObject root = CreateRoot("lumenfx_magic_stream");
            ParticleSystem ps = root.AddComponent<ParticleSystem>();
            ConfigureCommon(ps, duration: 2.0f, loop: true, maxParticles: 256);

            var main = ps.main;
            main.simulationSpace = ParticleSystemSimulationSpace.World;
            main.startLifetime = new ParticleSystem.MinMaxCurve(0.45f, 0.85f);
            main.startSpeed = new ParticleSystem.MinMaxCurve(3.0f, 5.0f);
            main.startSize = new ParticleSystem.MinMaxCurve(0.08f, 0.16f);
            main.startColor = new Color(0.25f, 1.0f, 0.65f, 1.0f);

            var emission = ps.emission;
            emission.rateOverTime = 45f;

            var shape = ps.shape;
            shape.enabled = true;
            shape.shapeType = ParticleSystemShapeType.Cone;
            shape.angle = 12f;
            shape.radius = 0.15f;

            var noise = ps.noise;
            noise.enabled = true;
            noise.strength = 0.45f;
            noise.frequency = 1.2f;
            noise.scrollSpeed = 0.5f;

            ParticleSystemRenderer renderer = root.GetComponent<ParticleSystemRenderer>();
            renderer.renderMode = ParticleSystemRenderMode.Stretch;
            renderer.velocityScale = 0.25f;
            renderer.lengthScale = 1.4f;
            ApplyDefaultMaterial(renderer, additive: true);
            FinalizeCreatedObject(root);
        }

        private static GameObject CreateRoot(string name)
        {
            GameObject go = new GameObject(name);
            Undo.RegisterCreatedObjectUndo(go, "Create " + name);
            if (Selection.activeTransform != null)
            {
                go.transform.SetParent(Selection.activeTransform, false);
            }
            return go;
        }

        private static void ConfigureCommon(ParticleSystem ps, float duration, bool loop, int maxParticles)
        {
            var main = ps.main;
            main.duration = duration;
            main.loop = loop;
            main.maxParticles = maxParticles;
            main.playOnAwake = true;
        }

        private static void ApplyDefaultMaterial(ParticleSystemRenderer renderer, bool additive, bool assignTrail = false)
        {
            bool isUrp = GraphicsSettings.currentRenderPipeline != null
                && GraphicsSettings.currentRenderPipeline.GetType().Name.Contains("Universal");
            Shader shader = Shader.Find(isUrp
                ? "Universal Render Pipeline/Particles/Unlit"
                : (additive ? "Particles/Additive" : "Particles/Standard Unlit"));
            if (shader == null)
            {
                shader = Shader.Find("Sprites/Default");
            }
            if (shader == null) return;

            Material mat = new Material(shader);
            mat.name = additive ? "LumenFX_DefaultAdditive" : "LumenFX_DefaultAlpha";
            if (isUrp)
            {
                if (mat.HasProperty("_Surface")) mat.SetFloat("_Surface", 1f);
                if (mat.HasProperty("_Blend")) mat.SetFloat("_Blend", additive ? 2f : 0f);
                if (mat.HasProperty("_SrcBlend")) mat.SetFloat("_SrcBlend", (float)BlendMode.SrcAlpha);
                if (mat.HasProperty("_DstBlend")) mat.SetFloat("_DstBlend", (float)(additive ? BlendMode.One : BlendMode.OneMinusSrcAlpha));
                if (mat.HasProperty("_ZWrite")) mat.SetFloat("_ZWrite", 0f);
            }
            renderer.sharedMaterial = mat;
            if (assignTrail)
            {
                renderer.trailMaterial = mat;
            }
        }

        private static void FinalizeCreatedObject(GameObject root)
        {
            Selection.activeGameObject = root;
            SceneView.lastActiveSceneView?.FrameSelected();
        }
    }
}
#endif
