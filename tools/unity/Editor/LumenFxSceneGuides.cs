#if UNITY_EDITOR
using UnityEditor;
using UnityEngine;

namespace LumenFx.UnityExport
{
    /// <summary>
    /// Optional Scene View overlay drawing a 1x1x1 Minecraft block unit cube, a 5x5 ground grid,
    /// and Minecraft coordinate axis arrows (+X East, +Y Up, -Z North) around the selected effect root.
    /// </summary>
    [InitializeOnLoad]
    internal static class LumenFxSceneGuides
    {
        private const string PrefKey = "LumenFx.ShowMinecraftGuides";

        internal static bool Enabled
        {
            get { return EditorPrefs.GetBool(PrefKey, false); }
            set
            {
                EditorPrefs.SetBool(PrefKey, value);
                SceneView.RepaintAll();
            }
        }

        static LumenFxSceneGuides()
        {
            SceneView.duringSceneGui += OnSceneGUI;
        }

        [MenuItem("Assets/LumenFX/Toggle Minecraft Block Grid Guide", false, 140)]
        private static void ToggleMenu()
        {
            Enabled = !Enabled;
        }

        [MenuItem("Assets/LumenFX/Toggle Minecraft Block Grid Guide", true)]
        private static bool ToggleMenuValidate()
        {
            Menu.SetChecked("Assets/LumenFX/Toggle Minecraft Block Grid Guide", Enabled);
            return true;
        }

        private static void OnSceneGUI(SceneView view)
        {
            if (!Enabled) return;
            GameObject active = Selection.activeGameObject;
            if (active == null || active.GetComponentInChildren<ParticleSystem>(true) == null) return;

            Vector3 origin = active.transform.position;
            Color prev = Handles.color;

            // 5x5 Minecraft block ground grid at origin.y
            Handles.color = new Color(0.45f, 0.85f, 1.0f, 0.22f);
            for (int i = -2; i <= 2; i++)
            {
                Handles.DrawLine(origin + new Vector3(i, 0f, -2f), origin + new Vector3(i, 0f, 2f));
                Handles.DrawLine(origin + new Vector3(-2f, 0f, i), origin + new Vector3(2f, 0f, i));
            }

            // 1x1x1 reference Minecraft block centered on XZ, resting on Y=0
            Handles.color = new Color(0.25f, 1.0f, 0.55f, 0.7f);
            Handles.DrawWireCube(origin + new Vector3(0f, 0.5f, 0f), Vector3.one);

            // Axis mapping labels: Unity (X, Y, Z) -> Minecraft (X, Y, -Z)
            Handles.color = new Color(1.0f, 0.35f, 0.35f, 0.9f);
            Handles.DrawLine(origin, origin + Vector3.right * 1.2f);
            Handles.Label(origin + Vector3.right * 1.25f, "MC +X");

            Handles.color = new Color(0.35f, 1.0f, 0.35f, 0.9f);
            Handles.DrawLine(origin, origin + Vector3.up * 1.2f);
            Handles.Label(origin + Vector3.up * 1.25f, "MC +Y (1 block = 1m)");

            Handles.color = new Color(0.35f, 0.65f, 1.0f, 0.9f);
            Handles.DrawLine(origin, origin + Vector3.forward * 1.2f);
            Handles.Label(origin + Vector3.forward * 1.25f, "Unity +Z -> MC -Z");

            Handles.color = prev;
        }
    }
}
#endif
