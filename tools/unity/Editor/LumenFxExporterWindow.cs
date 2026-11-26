#if UNITY_EDITOR
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using UnityEditor;
using UnityEngine;

namespace LumenFx.UnityExport
{
    internal sealed class LumenFxExporterWindow : EditorWindow
    {
        private const string PrefNamespace = "LumenFx.Export.Namespace";
        private const string PrefOutputFolder = "LumenFx.Export.OutputFolder";
        private const string PrefGeneratePack = "LumenFx.Export.GeneratePackMcmeta";
        private const string PrefPackFormat = "LumenFx.Export.PackFormat";

        private GameObject root;
        private string exportNamespace = "lumenfx";
        private string effectName = "effect";
        private string outputFolder = "";
        private bool batchSelectedRoots;
        private bool generatePackMcmeta = true;
        private int packFormat = 55;
        private Vector2 scroll;

        private readonly List<ExportIssue> lastIssues = new List<ExportIssue>();
        private string lastStatusMessage = "";
        private MessageType lastStatusType = MessageType.None;
        private int lastEmitterCount;

        [MenuItem("Assets/LumenFX/Export Selected Particle Systems...", false, 100)]
        [MenuItem("Window/LumenFX/Particle Exporter", false, 100)]
        private static void OpenFromSelection()
        {
            LumenFxExporterWindow window = GetWindow<LumenFxExporterWindow>("LumenFX Export");
            window.minSize = new Vector2(440f, 480f);
            window.LoadPrefs();
            if (Selection.activeGameObject != null)
            {
                window.root = Selection.activeGameObject;
                window.effectName = Sanitize(window.root.name);
            }
            window.Show();
        }

        private void OnEnable()
        {
            LoadPrefs();
        }

        private void LoadPrefs()
        {
            exportNamespace = EditorPrefs.GetString(PrefNamespace, string.IsNullOrEmpty(exportNamespace) ? "lumenfx" : exportNamespace);
            outputFolder = EditorPrefs.GetString(PrefOutputFolder, outputFolder ?? "");
            generatePackMcmeta = EditorPrefs.GetBool(PrefGeneratePack, true);
            packFormat = EditorPrefs.GetInt(PrefPackFormat, 55);
        }

        private void SavePrefs()
        {
            EditorPrefs.SetString(PrefNamespace, exportNamespace ?? "lumenfx");
            EditorPrefs.SetString(PrefOutputFolder, outputFolder ?? "");
            EditorPrefs.SetBool(PrefGeneratePack, generatePackMcmeta);
            EditorPrefs.SetInt(PrefPackFormat, packFormat);
        }

        private void OnGUI()
        {
            scroll = EditorGUILayout.BeginScrollView(scroll);

            EditorGUILayout.LabelField("LumenFX — Unity ParticleSystem Exporter", EditorStyles.boldLabel);
            EditorGUILayout.HelpBox(
                "1 Unity unit = 1 Minecraft block. Axis mapping: Unity (X, Y, Z) -> Minecraft (X, Y, -Z). "
                + "All enabled modules and shaders are validated before writing files.",
                MessageType.Info);

            // Creator Quick Actions bar
            EditorGUILayout.BeginVertical(EditorStyles.helpBox);
            EditorGUILayout.LabelField("Creator Tools", EditorStyles.boldLabel);
            EditorGUILayout.BeginHorizontal();
            bool guides = EditorGUILayout.ToggleLeft("MC 1-Block Grid Guide", LumenFxSceneGuides.Enabled, GUILayout.Width(175));
            if (guides != LumenFxSceneGuides.Enabled) LumenFxSceneGuides.Enabled = guides;
            if (GUILayout.Button("+ Spark")) LumenFxCreatorTemplates.CreateSparkBurst();
            if (GUILayout.Button("+ Trail")) LumenFxCreatorTemplates.CreateRibbonTrail();
            if (GUILayout.Button("+ Ring")) LumenFxCreatorTemplates.CreateAuraRing();
            if (GUILayout.Button("+ Stream")) LumenFxCreatorTemplates.CreateMagicStream();
            EditorGUILayout.EndHorizontal();
            EditorGUILayout.EndVertical();

            EditorGUILayout.Space(4f);

            // Target selection
            batchSelectedRoots = EditorGUILayout.Toggle("Batch Export Selected Roots", batchSelectedRoots);
            if (batchSelectedRoots)
            {
                GameObject[] selected = CollectSelectedParticleRoots();
                EditorGUILayout.LabelField("Selected Particle Roots: " + selected.Length, EditorStyles.miniBoldLabel);
                for (int i = 0; i < Mathf.Min(selected.Length, 8); i++)
                {
                    EditorGUILayout.LabelField("  • " + selected[i].name + "  ->  " + Sanitize(selected[i].name) + ".json", EditorStyles.miniLabel);
                }
                if (selected.Length > 8)
                {
                    EditorGUILayout.LabelField("  ... and " + (selected.Length - 8) + " more", EditorStyles.miniLabel);
                }
            }
            else
            {
                GameObject previous = root;
                EditorGUILayout.BeginHorizontal();
                root = (GameObject)EditorGUILayout.ObjectField("Root Hierarchy", root, typeof(GameObject), true);
                if (GUILayout.Button("Use Active", GUILayout.Width(80)) && Selection.activeGameObject != null)
                {
                    root = Selection.activeGameObject;
                }
                EditorGUILayout.EndHorizontal();
                if (root != null && root != previous)
                {
                    effectName = Sanitize(root.name);
                }
                effectName = EditorGUILayout.TextField("Effect Name", effectName);
            }

            exportNamespace = EditorGUILayout.TextField("Namespace", exportNamespace);

            EditorGUILayout.BeginHorizontal();
            outputFolder = EditorGUILayout.TextField("Output Folder", outputFolder);
            if (GUILayout.Button("Browse...", GUILayout.Width(80)))
            {
                string selected = EditorUtility.SaveFolderPanel("LumenFX Resource Pack Output Folder", outputFolder, "");
                if (!string.IsNullOrEmpty(selected))
                {
                    outputFolder = selected;
                    SavePrefs();
                }
            }
            EditorGUILayout.EndHorizontal();

            EditorGUILayout.BeginHorizontal();
            generatePackMcmeta = EditorGUILayout.ToggleLeft("Generate pack.mcmeta (ready-to-use Resource Pack)", generatePackMcmeta);
            if (generatePackMcmeta)
            {
                EditorGUILayout.LabelField("Format", GUILayout.Width(46));
                packFormat = Mathf.Clamp(EditorGUILayout.IntField(packFormat, GUILayout.Width(48)), 15, 99);
            }
            EditorGUILayout.EndHorizontal();

            EditorGUILayout.Space(6f);

            EditorGUILayout.BeginHorizontal();
            if (GUILayout.Button("Validate", GUILayout.Height(28f)))
            {
                SavePrefs();
                RunAction(false);
            }
            if (GUILayout.Button(batchSelectedRoots ? "Batch Export" : "Export", GUILayout.Height(28f)))
            {
                SavePrefs();
                RunAction(true);
            }
            if (GUILayout.Button("Open Folder", GUILayout.Width(96f), GUILayout.Height(28f)))
            {
                if (!string.IsNullOrEmpty(outputFolder) && Directory.Exists(outputFolder))
                {
                    EditorUtility.RevealInFinder(outputFolder);
                }
                else
                {
                    EditorUtility.DisplayDialog("LumenFX", "Output folder does not exist yet.", "OK");
                }
            }
            EditorGUILayout.EndHorizontal();

            if (!string.IsNullOrEmpty(lastStatusMessage))
            {
                EditorGUILayout.Space(4f);
                EditorGUILayout.HelpBox(lastStatusMessage, lastStatusType);
            }

            // Inline clickable validation inspector
            if (lastIssues.Count > 0)
            {
                EditorGUILayout.Space(4f);
                EditorGUILayout.LabelField("Validation Report (" + lastIssues.Count + " item(s))", EditorStyles.boldLabel);
                for (int i = 0; i < lastIssues.Count; i++)
                {
                    ExportIssue issue = lastIssues[i];
                    MessageType mt = issue.Severity == IssueSeverity.Warning ? MessageType.Warning : MessageType.Error;
                    EditorGUILayout.BeginHorizontal();
                    EditorGUILayout.HelpBox(issue.ToString(), mt);
                    if (!string.IsNullOrEmpty(issue.Context) && root != null && GUILayout.Button("Ping", GUILayout.Width(48f), GUILayout.Height(36f)))
                    {
                        PingContextObject(root, issue.Context);
                    }
                    EditorGUILayout.EndHorizontal();
                }
            }

            EditorGUILayout.Space(6f);
            EditorGUILayout.LabelField("Output Layout", EditorStyles.boldLabel);
            string ns = string.IsNullOrWhiteSpace(exportNamespace) ? "<namespace>" : exportNamespace.Trim().ToLowerInvariant();
            string eff = batchSelectedRoots ? "<root_name>" : (string.IsNullOrWhiteSpace(effectName) ? "<effect>" : effectName.Trim().ToLowerInvariant());
            EditorGUILayout.SelectableLabel(
                "assets/" + ns + "/lumenfx/effects/" + eff + ".json\n"
                + "assets/" + ns + "/textures/effects/" + eff + "/*.png\n"
                + "assets/" + ns + "/lumenfx/meshes/" + eff + "/*.lfmesh",
                EditorStyles.miniLabel, GUILayout.Height(46f));

            EditorGUILayout.EndScrollView();
        }

        private void RunAction(bool write)
        {
            lastIssues.Clear();
            lastEmitterCount = 0;

            if (batchSelectedRoots)
            {
                GameObject[] targets = CollectSelectedParticleRoots();
                if (targets.Length == 0)
                {
                    SetStatus("Select at least one GameObject containing a ParticleSystem in the Hierarchy.", MessageType.Error);
                    return;
                }
                int totalWritten = 0;
                int totalErrors = 0;
                int totalWarnings = 0;
                int succeeded = 0;
                foreach (GameObject target in targets)
                {
                    ExportSettings settings = new ExportSettings
                    {
                        Root = target,
                        Namespace = (exportNamespace ?? "").Trim().ToLowerInvariant(),
                        EffectName = Sanitize(target.name),
                        OutputFolder = outputFolder
                    };
                    LumenFxBuildResult result = LumenFxExporter.Build(settings);
                    RecordIssues(target.name, result.Issues, ref totalErrors, ref totalWarnings);
                    if (!result.Success) continue;
                    lastEmitterCount += result.Plan.Emitters.Count;
                    if (write)
                    {
                        List<string> written = LumenFxExporter.Write(settings, result.Plan);
                        totalWritten += written.Count;
                    }
                    succeeded++;
                }
                if (write && succeeded > 0 && generatePackMcmeta)
                {
                    WritePackMcmetaIfNeeded(outputFolder, packFormat);
                }
                if (totalErrors > 0)
                {
                    SetStatus("Batch finished with " + totalErrors + " error(s), " + totalWarnings + " warning(s). "
                        + succeeded + "/" + targets.Length + " effect(s) valid" + (write ? (" (" + totalWritten + " file(s) written).") : "."),
                        MessageType.Error);
                }
                else
                {
                    SetStatus((write ? "Batch exported " : "Batch validated ") + succeeded + " effect(s) ("
                        + lastEmitterCount + " emitters" + (write ? (", " + totalWritten + " files written") : "")
                        + (totalWarnings > 0 ? (", " + totalWarnings + " warning(s)") : "") + ").",
                        totalWarnings > 0 ? MessageType.Warning : MessageType.Info);
                }
                Repaint();
                return;
            }

            ExportSettings singleSettings = new ExportSettings
            {
                Root = root,
                Namespace = (exportNamespace ?? "").Trim().ToLowerInvariant(),
                EffectName = (effectName ?? "").Trim().ToLowerInvariant(),
                OutputFolder = outputFolder
            };

            LumenFxBuildResult singleResult = LumenFxExporter.Build(singleSettings);
            int errorCount = 0;
            int warningCount = 0;
            RecordIssues(null, singleResult.Issues, ref errorCount, ref warningCount);

            if (!singleResult.Success)
            {
                SetStatus("Validation failed with " + errorCount + " error(s) and " + warningCount
                    + " warning(s). No files were written.", MessageType.Error);
                EditorUtility.DisplayDialog("LumenFX export aborted",
                    errorCount + " validation error(s) found. No files were written. See the exporter report or Console.", "OK");
                Repaint();
                return;
            }

            lastEmitterCount = singleResult.Plan.Emitters.Count;
            if (!write)
            {
                string warningSuffix = warningCount > 0 ? " (" + warningCount + " warning(s))." : ".";
                SetStatus("Valid! " + lastEmitterCount + " emitter(s) ready to export" + warningSuffix,
                    warningCount > 0 ? MessageType.Warning : MessageType.Info);
                Repaint();
                return;
            }

            string jsonPath = Path.Combine(singleSettings.OutputFolder, "assets", singleSettings.Namespace,
                "lumenfx", "effects", singleSettings.EffectName + ".json");
            if (File.Exists(jsonPath)
                && !EditorUtility.DisplayDialog("Overwrite LumenFX effect?",
                    jsonPath + " already exists. Overwrite it?", "Overwrite", "Cancel"))
                return;

            try
            {
                List<string> written = LumenFxExporter.Write(singleSettings, singleResult.Plan);
                if (generatePackMcmeta)
                {
                    string meta = WritePackMcmetaIfNeeded(singleSettings.OutputFolder, packFormat);
                    if (!string.IsNullOrEmpty(meta)) written.Add(meta);
                }
                foreach (string path in written) Debug.Log("LumenFX wrote " + path);
                SetStatus("Export complete: wrote " + written.Count + " file(s) (" + lastEmitterCount + " emitter(s)).",
                    warningCount > 0 ? MessageType.Warning : MessageType.Info);
                EditorUtility.DisplayDialog("LumenFX export complete",
                    "Wrote " + written.Count + " file(s). Ready to use in Minecraft.", "OK");
            }
            catch (Exception exception)
            {
                Debug.LogError("LumenFX export failed: " + exception);
                SetStatus("Export failed: " + exception.Message, MessageType.Error);
                EditorUtility.DisplayDialog("LumenFX export failed",
                    "Export did not finish. The Console contains the full error.", "OK");
            }
            Repaint();
        }

        private void RecordIssues(string prefix, List<ExportIssue> issues, ref int errors, ref int warnings)
        {
            foreach (ExportIssue issue in issues)
            {
                string ctx = string.IsNullOrEmpty(prefix) ? issue.Context
                    : (string.IsNullOrEmpty(issue.Context) ? prefix : prefix + "/" + issue.Context);
                lastIssues.Add(new ExportIssue(ctx, issue.Message, issue.Severity));
                if (issue.Severity == IssueSeverity.Warning)
                {
                    warnings++;
                    Debug.LogWarning(issue.ToString());
                }
                else
                {
                    errors++;
                    Debug.LogError(issue.ToString());
                }
            }
        }

        private void SetStatus(string message, MessageType type)
        {
            lastStatusMessage = message;
            lastStatusType = type;
        }

        private static GameObject[] CollectSelectedParticleRoots()
        {
            List<GameObject> roots = new List<GameObject>();
            foreach (GameObject go in Selection.gameObjects)
            {
                if (go != null && go.GetComponentInChildren<ParticleSystem>(true) != null)
                {
                    roots.Add(go);
                }
            }
            return roots.ToArray();
        }

        private static void PingContextObject(GameObject rootObj, string context)
        {
            Transform found = rootObj.transform.Find(context);
            if (found == null)
            {
                foreach (Transform t in rootObj.GetComponentsInChildren<Transform>(true))
                {
                    if (string.Equals(t.name, context, StringComparison.OrdinalIgnoreCase)
                        || context.EndsWith("/" + t.name, StringComparison.OrdinalIgnoreCase))
                    {
                        found = t;
                        break;
                    }
                }
            }
            EditorGUIUtility.PingObject(found != null ? (UnityEngine.Object)found.gameObject : rootObj);
        }

        private static string WritePackMcmetaIfNeeded(string outputRoot, int format)
        {
            if (string.IsNullOrWhiteSpace(outputRoot)) return null;
            string metaPath = Path.Combine(outputRoot, "pack.mcmeta");
            if (File.Exists(metaPath)) return null;
            Directory.CreateDirectory(outputRoot);
            string json = "{\n  \"pack\": {\n    \"pack_format\": " + format
                + ",\n    \"description\": \"LumenFX Unity Exported Particle Pack\"\n  }\n}\n";
            File.WriteAllText(metaPath, json, new UTF8Encoding(false));
            return metaPath;
        }

        private static string Sanitize(string value)
        {
            if (string.IsNullOrEmpty(value)) return "effect";
            StringBuilder builder = new StringBuilder(value.Length);
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
