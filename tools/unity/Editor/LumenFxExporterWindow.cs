#if UNITY_EDITOR
using System;
using System.Collections.Generic;
using System.IO;
using UnityEditor;
using UnityEngine;

namespace LumenFx.UnityExport
{
    internal sealed class LumenFxExporterWindow : EditorWindow
    {
        private GameObject root;
        private string exportNamespace = "lumenfx";
        private string effectName = "effect";
        private string outputFolder = "";
        private Vector2 scroll;

        [MenuItem("Assets/LumenFX/Export Selected Particle Systems...", false, 100)]
        private static void OpenFromSelection()
        {
            LumenFxExporterWindow window = GetWindow<LumenFxExporterWindow>("LumenFX Export");
            window.root = Selection.activeGameObject;
            if (window.root != null) window.effectName = Sanitize(window.root.name);
            window.Show();
        }

        private void OnGUI()
        {
            scroll = EditorGUILayout.BeginScrollView(scroll);
            EditorGUILayout.LabelField("Unity ParticleSystem to LumenFX", EditorStyles.boldLabel);
            EditorGUILayout.HelpBox(
                "The selected hierarchy becomes one LumenFX effect. All enabled modules and shaders are validated "
                + "before anything is written; unsupported features abort the export with contextual Console errors.",
                MessageType.Info);

            GameObject previous = root;
            root = (GameObject)EditorGUILayout.ObjectField("Root Hierarchy", root, typeof(GameObject), true);
            if (root != null && root != previous && string.IsNullOrEmpty(effectName))
                effectName = Sanitize(root.name);

            exportNamespace = EditorGUILayout.TextField("Namespace", exportNamespace);
            effectName = EditorGUILayout.TextField("Effect Name", effectName);

            EditorGUILayout.BeginHorizontal();
            outputFolder = EditorGUILayout.TextField("Output Folder", outputFolder);
            if (GUILayout.Button("Browse...", GUILayout.Width(80)))
            {
                string selected = EditorUtility.SaveFolderPanel("LumenFX output folder", outputFolder, "");
                if (!string.IsNullOrEmpty(selected)) outputFolder = selected;
            }
            EditorGUILayout.EndHorizontal();

            EditorGUILayout.Space();
            EditorGUILayout.BeginHorizontal();
            if (GUILayout.Button("Validate")) Run(false);
            if (GUILayout.Button("Export")) Run(true);
            EditorGUILayout.EndHorizontal();

            EditorGUILayout.Space();
            EditorGUILayout.LabelField("Output", EditorStyles.boldLabel);
            EditorGUILayout.LabelField("assets/<namespace>/lumenfx/effects/<effect>.json");
            EditorGUILayout.LabelField("assets/<namespace>/textures/effects/<effect>/*.png");
            EditorGUILayout.EndScrollView();
        }

        private void Run(bool write)
        {
            ExportSettings settings = new ExportSettings
            {
                Root = root,
                Namespace = (exportNamespace ?? "").Trim().ToLowerInvariant(),
                EffectName = (effectName ?? "").Trim().ToLowerInvariant(),
                OutputFolder = outputFolder
            };

            LumenFxBuildResult result = LumenFxExporter.Build(settings);
            int errorCount = 0;
            int warningCount = 0;
            foreach (ExportIssue issue in result.Issues)
            {
                if (issue.Severity == IssueSeverity.Warning)
                {
                    warningCount++;
                    Debug.LogWarning(issue.ToString());
                }
                else
                {
                    errorCount++;
                    Debug.LogError(issue.ToString());
                }
            }
            if (!result.Success)
            {
                EditorUtility.DisplayDialog("LumenFX export aborted",
                    errorCount + " validation error(s) found. No files were written. See the Console for details.", "OK");
                return;
            }

            if (!write)
            {
                string warningSuffix = warningCount > 0 ? " (" + warningCount + " warning(s); see Console)." : "";
                EditorUtility.DisplayDialog("LumenFX validation",
                    "The selection can be exported. " + result.Plan.Emitters.Count + " emitter(s) planned." + warningSuffix, "OK");
                return;
            }

            string jsonPath = Path.Combine(settings.OutputFolder, "assets", settings.Namespace,
                "lumenfx", "effects", settings.EffectName + ".json");
            if (File.Exists(jsonPath)
                && !EditorUtility.DisplayDialog("Overwrite LumenFX effect?",
                    jsonPath + " already exists. Overwrite it?", "Overwrite", "Cancel"))
                return;

            try
            {
                List<string> written = LumenFxExporter.Write(settings, result.Plan);
                foreach (string path in written) Debug.Log("LumenFX wrote " + path);
                EditorUtility.DisplayDialog("LumenFX export complete",
                    "Wrote " + written.Count + " file(s). See the Console for paths.", "OK");
            }
            catch (Exception exception)
            {
                Debug.LogError("LumenFX export failed: " + exception);
                EditorUtility.DisplayDialog("LumenFX export failed",
                    "Export did not finish. Files may be partially written. The Console contains the full error.", "OK");
            }
        }

        private static string Sanitize(string value)
        {
            if (string.IsNullOrEmpty(value)) return "effect";
            System.Text.StringBuilder builder = new System.Text.StringBuilder(value.Length);
            foreach (char character in value.ToLowerInvariant())
            {
                if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')
                    || character == '_' || character == '-' || character == '.')
                    builder.Append(character);
                else
                    builder.Append('_');
            }
            string result = builder.ToString().Trim('_');
            return string.IsNullOrEmpty(result) ? "effect" : result;
        }
    }
}
#endif
