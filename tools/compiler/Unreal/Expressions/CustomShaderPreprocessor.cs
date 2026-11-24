using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace LumenFx.Compiler.Unreal.Expressions;

internal sealed class CustomShaderPreprocessor
{
    internal sealed record DefineEntry(string Name, string Value);

    private sealed class MacroDefinition
    {
        internal string Name { get; }
        internal IReadOnlyList<string>? Parameters { get; }
        internal bool IsVariadic { get; }
        internal string Body { get; }
        internal bool IsFunctionLike => Parameters is not null;

        internal MacroDefinition(string name, IReadOnlyList<string>? parameters, bool isVariadic, string body)
        {
            Name = name;
            Parameters = parameters;
            IsVariadic = isVariadic;
            Body = body;
        }
    }

    private sealed class BranchState
    {
        internal bool ParentActive;
        internal bool BranchActive;
        internal bool AnyBranchTaken;
        internal bool SawElse;
    }

    private static readonly Regex IdentifierPattern = new(@"^[A-Za-z_][A-Za-z0-9_]*", RegexOptions.Compiled);
    private static readonly Regex IncludePattern = new(@"^\s*(?:""([^""]+)""|<([^>]+)>)", RegexOptions.Compiled);
    private static readonly Regex MacroSignaturePattern = new(@"^([A-Za-z_][A-Za-z0-9_]*)(?:\(([^)]*)\))?(?:\s+(.*))?$", RegexOptions.Compiled | RegexOptions.Singleline);

    private static readonly HashSet<string> GeneratedEngineStubs = new(StringComparer.OrdinalIgnoreCase)
    {
        "Definitions.usf",
        "GeneratedUniformBufferTypes.ush",
        "GeneratedUniformBuffers.ush",
        "GeneratedInstancedStereo.ush",
        "CommonViewUniformBuffer.ush"
    };

    private static readonly Dictionary<string, string> CompatRelativeFiles = new(StringComparer.OrdinalIgnoreCase)
    {
        ["/Engine/Private/Common.ush"] = "Common.ush",
        ["/Engine/Public/Platform.ush"] = "Platform.ush",
        ["/Engine/Private/ViewCompat.ush"] = "ViewCompat.ush",
        ["/Engine/Public/ViewCompat.ush"] = "ViewCompat.ush"
    };

    private readonly string? unrealSourceRoot;
    private readonly IReadOnlyDictionary<string, string> directoryMappings;
    private readonly string? compatDirectory;
    private readonly Dictionary<string, string>? pluginShaderDirectories;
    private readonly Dictionary<string, MacroDefinition> macros = new(StringComparer.Ordinal);
    private readonly HashSet<string> onceFiles = new(StringComparer.OrdinalIgnoreCase);
    private readonly List<string> activeIncludeStack = new();

    internal CustomShaderPreprocessor(
        string? unrealSourceRoot,
        IReadOnlyDictionary<string, string>? directoryMappings,
        bool isVertexStage,
        MaterialGraphPermutation? permutation = null)
    {
        this.unrealSourceRoot = string.IsNullOrWhiteSpace(unrealSourceRoot)
            ? null
            : Path.GetFullPath(unrealSourceRoot);
        this.directoryMappings = directoryMappings ?? new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        compatDirectory = FindCompatDirectory(this.unrealSourceRoot);
        pluginShaderDirectories = this.unrealSourceRoot is not null && Directory.Exists(Path.Combine(this.unrealSourceRoot, "Engine", "Plugins"))
            ? DiscoverPluginShaderDirectories(Path.Combine(this.unrealSourceRoot, "Engine", "Plugins"))
            : null;
        SeedDefaultMacros(isVertexStage, permutation ?? new MaterialGraphPermutation());
    }

    internal (string Preamble, string Body) Process(
        IEnumerable<DefineEntry> additionalDefines,
        IEnumerable<string> includePaths,
        string? preamble,
        string code)
    {
        foreach (DefineEntry define in additionalDefines) {
            if (string.IsNullOrWhiteSpace(define.Name)) continue;
            DefineObjectMacro(define.Name.Trim(), define.Value ?? "");
        }

        StringBuilder preambleBuilder = new();
        foreach (string rawInclude in includePaths) {
            string path = rawInclude.Trim();
            if (path.Length == 0) continue;
            string resolved = LoadIncludeContent(path, null, out string? resolvedDiskPath);
            ProcessText(resolved, resolvedDiskPath, preambleBuilder);
        }

        if (!string.IsNullOrWhiteSpace(preamble)) {
            ProcessText(preamble, null, preambleBuilder);
        }

        StringBuilder bodyBuilder = new();
        ProcessText(code, null, bodyBuilder);
        return (preambleBuilder.ToString(), bodyBuilder.ToString());
    }

    private void SeedDefaultMacros(bool isVertexStage, MaterialGraphPermutation permutation)
    {
        MaterialFeatureInput featureLevel = permutation.EffectiveFeatureLevel;
        MaterialShadingInput shadingPath = permutation.ShadingPath;
        int featureValue = featureLevel switch {
            MaterialFeatureInput.Es3_1 => 2,
            MaterialFeatureInput.Sm6 => 6,
            _ => 5
        };
        int qualityValue = permutation.Quality switch {
            MaterialQualityInput.Low => 0,
            MaterialQualityInput.Medium => 3,
            MaterialQualityInput.Epic => 2,
            MaterialQualityInput.High => 1,
            _ => 1
        };
        DefineObjectMacro("PIXELSHADER", isVertexStage ? "0" : "1");
        DefineObjectMacro("VERTEXSHADER", isVertexStage ? "1" : "0");
        DefineObjectMacro("COMPUTESHADER", "0");
        DefineObjectMacro("MATERIALSHADER", "1");
        DefineObjectMacro("ES3_1_PROFILE", featureLevel == MaterialFeatureInput.Es3_1 ? "1" : "0");
        DefineObjectMacro("SM5_PROFILE", featureLevel == MaterialFeatureInput.Sm5 ? "1" : "0");
        DefineObjectMacro("SM6_PROFILE", featureLevel == MaterialFeatureInput.Sm6 ? "1" : "0");
        DefineObjectMacro("FEATURE_LEVEL_ES2_REMOVED", "1");
        DefineObjectMacro("FEATURE_LEVEL_ES3_1", "2");
        DefineObjectMacro("FEATURE_LEVEL_SM3", "3");
        DefineObjectMacro("FEATURE_LEVEL_SM4", "4");
        DefineObjectMacro("FEATURE_LEVEL_SM5", "5");
        DefineObjectMacro("FEATURE_LEVEL_SM6", "6");
        DefineObjectMacro("FEATURE_LEVEL", featureValue.ToString(CultureInfo.InvariantCulture));
        DefineObjectMacro("SHADING_PATH_DEFERRED", shadingPath == MaterialShadingInput.Deferred ? "1" : "0");
        DefineObjectMacro("SHADING_PATH_FORWARD", shadingPath == MaterialShadingInput.Forward ? "1" : "0");
        DefineObjectMacro("SHADING_PATH_MOBILE", shadingPath == MaterialShadingInput.Mobile ? "1" : "0");
        DefineObjectMacro("QUALITY_LEVEL", qualityValue.ToString(CultureInfo.InvariantCulture));
        DefineObjectMacro("COMPILER_HLSL", "1");
        DefineObjectMacro("COMPILER_DXC", "1");
        DefineObjectMacro("COMPILER_HLSLCC", "0");
        DefineObjectMacro("COMPILER_GLSL", "0");
        DefineObjectMacro("COMPILER_GLSL_ES3_1", "0");
        DefineObjectMacro("COMPILER_METAL", "0");
        DefineObjectMacro("COMPILER_VULKAN", "0");
        DefineObjectMacro("PLATFORM_SUPPORTS_REAL_TYPES", "0");
        DefineObjectMacro("PLATFORM_SUPPORTS_RELAXED_PRECISION", "0");
        DefineObjectMacro("PLATFORM_USES_SPIRV_RESOURCE_TYPES", "1");
        DefineObjectMacro("FORCE_FLOATS", "1");
        DefineObjectMacro("FORCE_MATERIAL_FLOAT_FULL_PRECISION", "1");
        DefineObjectMacro("UE_DF_NO_FAST_MATH", "1");
        DefineObjectMacro("UE_LWC_RENDER_TILE_SIZE", "2097152.0f");
        DefineObjectMacro("UE_LWC_RENDER_TILE_SIZE_SQRT", "1448.1546630859375f");
        DefineObjectMacro("UE_LWC_RENDER_TILE_SIZE_RSQRT", "0.0006905339660024879f");
        DefineObjectMacro("UE_LWC_RENDER_TILE_SIZE_RCP", "0.000000476837158203125f");
        DefineObjectMacro("UE_LWC_RENDER_TILE_SIZE_FMOD_PI", "2.7611389f");
        DefineObjectMacro("UE_LWC_RENDER_TILE_SIZE_FMOD_2PI", "5.9027314f");
    }

    private void DefineObjectMacro(string name, string value)
        => macros[name] = new MacroDefinition(name, null, false, value.Trim());

    private void ProcessText(string source, string? currentFilePath, StringBuilder output)
    {
        if (currentFilePath is not null) {
            if (activeIncludeStack.Contains(currentFilePath, StringComparer.OrdinalIgnoreCase))
                throw new ConversionException($"Cyclic custom shader #include detected: {currentFilePath}");
            if (activeIncludeStack.Count >= 64)
                throw new ConversionException($"Custom shader #include nesting exceeded 64 levels at {currentFilePath}");
            activeIncludeStack.Add(currentFilePath);
        }

        try {
            string clean = StripCommentsPreservingStrings(source);
            List<string> lines = SplitPhysicalLinesJoined(clean);
            Stack<BranchState> conditionals = new();

            bool IsActive() => conditionals.Count == 0 || conditionals.Peek().BranchActive;

            for (int index = 0; index < lines.Count; index++) {
                string line = lines[index];
                string trimmed = line.TrimStart();
                if (!trimmed.StartsWith('#')) {
                    if (IsActive()) output.AppendLine(ExpandMacros(line));
                    continue;
                }

                string directiveBody = trimmed[1..].TrimStart();
                if (directiveBody.Length == 0) continue;

                Match dirMatch = IdentifierPattern.Match(directiveBody);
                if (!dirMatch.Success) {
                    if (IsActive()) output.AppendLine(line);
                    continue;
                }

                string directive = dirMatch.Value;
                string rest = directiveBody[directive.Length..].TrimStart();

                switch (directive) {
                    case "ifdef" or "ifndef": {
                        bool parentActive = IsActive();
                        bool defined = TryReadLeadingIdentifier(rest, out string symbol) && macros.ContainsKey(symbol);
                        bool cond = directive == "ifdef" ? defined : !defined;
                        conditionals.Push(new BranchState {
                            ParentActive = parentActive,
                            BranchActive = parentActive && cond,
                            AnyBranchTaken = cond
                        });
                        break;
                    }
                    case "if": {
                        bool parentActive = IsActive();
                        bool cond = parentActive && EvaluateCondition(rest);
                        conditionals.Push(new BranchState {
                            ParentActive = parentActive,
                            BranchActive = cond,
                            AnyBranchTaken = cond
                        });
                        break;
                    }
                    case "elif": {
                        if (conditionals.Count == 0 || conditionals.Peek().SawElse)
                            throw new ConversionException("Unmatched #elif in custom shader");
                        BranchState state = conditionals.Peek();
                        if (!state.ParentActive || state.AnyBranchTaken) {
                            state.BranchActive = false;
                        } else {
                            bool cond = EvaluateCondition(rest);
                            state.BranchActive = cond;
                            if (cond) state.AnyBranchTaken = true;
                        }
                        break;
                    }
                    case "else": {
                        if (conditionals.Count == 0 || conditionals.Peek().SawElse)
                            throw new ConversionException("Unmatched #else in custom shader");
                        BranchState state = conditionals.Peek();
                        state.SawElse = true;
                        state.BranchActive = state.ParentActive && !state.AnyBranchTaken;
                        state.AnyBranchTaken = true;
                        break;
                    }
                    case "endif": {
                        if (conditionals.Count == 0)
                            throw new ConversionException("Unmatched #endif in custom shader");
                        conditionals.Pop();
                        break;
                    }
                    default: {
                        if (!IsActive()) break;
                        switch (directive) {
                            case "define":
                                ParseAndStoreMacro(rest);
                                break;
                            case "undef":
                                if (TryReadLeadingIdentifier(rest, out string undefName))
                                    macros.Remove(undefName);
                                break;
                            case "pragma":
                                if (Regex.IsMatch(rest, @"^\s*once\b")) {
                                    if (currentFilePath is not null && !onceFiles.Add(currentFilePath))
                                        return;
                                }
                                break;
                            case "include": {
                                string expandedInclude = ExpandMacros(rest);
                                Match match = IncludePattern.Match(expandedInclude);
                                if (!match.Success)
                                    throw new ConversionException($"Malformed #include directive in custom shader: {rest}");
                                string includeTarget = match.Groups[1].Success ? match.Groups[1].Value : match.Groups[2].Value;
                                string included = LoadIncludeContent(includeTarget, currentFilePath, out string? includedDiskPath);
                                if (included.Length > 0)
                                    ProcessText(included, includedDiskPath, output);
                                break;
                            }
                            case "error":
                                throw new ConversionException($"Custom shader preprocessor #error: {rest}");
                            case "line":
                                break;
                            default:
                                // Preserve unrecognized preprocessor directives (rare) only outside inactive branches
                                break;
                        }
                        break;
                    }
                }
            }

            if (conditionals.Count != 0)
                throw new ConversionException("Unterminated #if/#ifdef directive in custom shader");
        } finally {
            if (currentFilePath is not null)
                activeIncludeStack.RemoveAt(activeIncludeStack.Count - 1);
        }
    }

    private void ParseAndStoreMacro(string definition)
    {
        if (!TryReadLeadingIdentifier(definition, out string name))
            throw new ConversionException($"Invalid #define macro name: {definition}");

        int pos = name.Length;
        // Function-like macro ONLY if '(' immediately follows macro name with NO whitespace.
        if (pos < definition.Length && definition[pos] == '(') {
            int close = definition.IndexOf(')', pos + 1);
            if (close < 0)
                throw new ConversionException($"Unterminated parameter list in #define {name}");
            string paramText = definition[(pos + 1)..close];
            List<string> parameters = new();
            bool variadic = false;
            foreach (string rawParam in paramText.Split(',', StringSplitOptions.TrimEntries)) {
                if (rawParam.Length == 0) continue;
                if (rawParam == "...") {
                    variadic = true;
                    continue;
                }
                if (rawParam.EndsWith("...", StringComparison.Ordinal)) {
                    variadic = true;
                    parameters.Add(rawParam[..^3].Trim());
                    continue;
                }
                parameters.Add(rawParam);
            }
            string body = close + 1 < definition.Length ? definition[(close + 1)..].Trim() : "";
            macros[name] = new MacroDefinition(name, parameters, variadic, body);
        } else {
            string body = pos < definition.Length ? definition[pos..].Trim() : "";
            macros[name] = new MacroDefinition(name, null, false, body);
        }
    }

    private string LoadIncludeContent(string includePath, string? includingFilePath, out string? resolvedDiskPath)
    {
        string normalized = includePath.Replace('\\', '/').Trim();
        string fileName = Path.GetFileName(normalized);
        if (normalized.StartsWith("/Engine/Generated/", StringComparison.OrdinalIgnoreCase)
                || GeneratedEngineStubs.Contains(fileName)) {
            resolvedDiskPath = null;
            return "";
        }

        if (CompatRelativeFiles.TryGetValue(normalized, out string? compatName) && compatDirectory is not null) {
            string compatCandidate = Path.GetFullPath(Path.Combine(compatDirectory, compatName));
            if (File.Exists(compatCandidate)) {
                resolvedDiskPath = compatCandidate;
                return onceFiles.Contains(compatCandidate) ? "" : File.ReadAllText(compatCandidate);
            }
        }

        string? resolved = TryResolveDiskPath(normalized, includingFilePath);
        if (resolved is null)
            throw new ConversionException($"Custom material include file could not be resolved: {includePath}");

        resolvedDiskPath = resolved;
        if (onceFiles.Contains(resolved))
            return "";
        return File.ReadAllText(resolved);
    }

    private string? TryResolveDiskPath(string normalizedPath, string? includingFilePath)
    {
        if (Path.IsPathRooted(normalizedPath) && File.Exists(normalizedPath))
            return Path.GetFullPath(normalizedPath);

        if (!normalizedPath.StartsWith('/')) {
            if (includingFilePath is not null) {
                string? dir = Path.GetDirectoryName(includingFilePath);
                if (dir is not null) {
                    string relativeToCaller = Path.GetFullPath(Path.Combine(dir, normalizedPath));
                    if (File.Exists(relativeToCaller)) return relativeToCaller;
                }
            }
            if (compatDirectory is not null && Path.GetDirectoryName(includingFilePath ?? "")?.Equals(compatDirectory, StringComparison.OrdinalIgnoreCase) == true) {
                string compatRel = Path.GetFullPath(Path.Combine(compatDirectory, normalizedPath));
                if (File.Exists(compatRel)) return compatRel;
            }
            if (unrealSourceRoot is not null) {
                string enginePrivate = Path.GetFullPath(Path.Combine(unrealSourceRoot, "Engine", "Shaders", "Private", normalizedPath));
                if (File.Exists(enginePrivate)) return enginePrivate;
                string enginePublic = Path.GetFullPath(Path.Combine(unrealSourceRoot, "Engine", "Shaders", "Public", normalizedPath));
                if (File.Exists(enginePublic)) return enginePublic;
                string engineShaders = Path.GetFullPath(Path.Combine(unrealSourceRoot, "Engine", "Shaders", normalizedPath));
                if (File.Exists(engineShaders)) return engineShaders;
            }
            return null;
        }

        string? longestPrefix = null;
        string? longestTarget = null;
        foreach (var entry in directoryMappings) {
            string prefix = "/" + entry.Key.Replace('\\', '/').Trim('/') + "/";
            if (normalizedPath.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)
                    && (longestPrefix is null || prefix.Length > longestPrefix.Length)) {
                longestPrefix = prefix;
                longestTarget = entry.Value;
            }
        }
        if (longestPrefix is not null && longestTarget is not null) {
            string root = Path.GetFullPath(longestTarget);
            string mapped = Path.GetFullPath(normalizedPath[longestPrefix.Length..], root);
            string relative = Path.GetRelativePath(root, mapped);
            if (relative == ".." || relative.StartsWith(".." + Path.DirectorySeparatorChar, StringComparison.Ordinal)
                    || Path.IsPathRooted(relative))
                throw new ConversionException($"Custom shader include escapes {longestPrefix}: {normalizedPath}");
            return File.Exists(mapped) ? mapped : null;
        }

        if (normalizedPath.StartsWith("/Engine/", StringComparison.OrdinalIgnoreCase) && unrealSourceRoot is not null) {
            string tail = normalizedPath["/Engine/".Length..];
            if (tail.StartsWith("Shaders/", StringComparison.OrdinalIgnoreCase))
                tail = tail["Shaders/".Length..];
            string engineShader = Path.GetFullPath(Path.Combine(unrealSourceRoot, "Engine", "Shaders", tail));
            if (File.Exists(engineShader)) return engineShader;
            string engineRootFile = Path.GetFullPath(Path.Combine(unrealSourceRoot, "Engine", normalizedPath["/Engine/".Length..]));
            if (File.Exists(engineRootFile)) return engineRootFile;
        }

        if (normalizedPath.StartsWith("/Plugin/", StringComparison.OrdinalIgnoreCase) && pluginShaderDirectories is not null) {
            string afterPlugin = normalizedPath["/Plugin/".Length..];
            foreach (var entry in pluginShaderDirectories.OrderByDescending(pair => pair.Key.Length)) {
                string prefix = entry.Key + "/";
                if (!afterPlugin.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) continue;
                string remainder = afterPlugin[prefix.Length..];
                if (remainder.StartsWith("Shaders/", StringComparison.OrdinalIgnoreCase))
                    remainder = remainder["Shaders/".Length..];
                string pluginFile = Path.GetFullPath(Path.Combine(entry.Value, remainder));
                if (File.Exists(pluginFile)) return pluginFile;
            }
        }

        return null;
    }

    private string ExpandMacros(string text)
        => ExpandMacrosInternal(text, new HashSet<string>(StringComparer.Ordinal));

    private string ExpandMacrosInternal(string text, HashSet<string> expanding)
    {
        if (macros.Count == 0 || string.IsNullOrEmpty(text))
            return text;

        StringBuilder result = new(text.Length);
        int i = 0;
        while (i < text.Length) {
            char c = text[i];
            if (c == '"' || c == '\'') {
                int end = SkipStringLiteral(text, i);
                result.Append(text, i, end - i);
                i = end;
                continue;
            }
            if (IsIdentStart(c)) {
                int start = i;
                i++;
                while (i < text.Length && IsIdentPart(text[i])) i++;
                string ident = text[start..i];
                if (!expanding.Contains(ident) && macros.TryGetValue(ident, out MacroDefinition? macro)) {
                    if (macro.IsFunctionLike) {
                        int lookahead = i;
                        while (lookahead < text.Length && char.IsWhiteSpace(text[lookahead])) lookahead++;
                        if (lookahead < text.Length && text[lookahead] == '(') {
                            if (TryReadMacroArguments(text, lookahead, out List<string> rawArgs, out int afterArgs)) {
                                if (expanding.Add(ident)) {
                                    try {
                                        string replaced = ApplyFunctionMacro(macro, rawArgs, expanding);
                                        result.Append(ExpandMacrosInternal(replaced, expanding));
                                    } finally {
                                        expanding.Remove(ident);
                                    }
                                    i = afterArgs;
                                    continue;
                                }
                            }
                        }
                        result.Append(ident);
                        continue;
                    }
                    if (expanding.Add(ident)) {
                        try {
                            string joined = ApplyTokenPasting(macro.Body);
                            result.Append(ExpandMacrosInternal(joined, expanding));
                        } finally {
                            expanding.Remove(ident);
                        }
                        continue;
                    }
                }
                result.Append(ident);
                continue;
            }
            result.Append(c);
            i++;
        }
        return result.ToString();
    }

    private string ApplyFunctionMacro(MacroDefinition macro, List<string> rawArgs, HashSet<string> expanding)
    {
        IReadOnlyList<string> parameters = macro.Parameters!;
        List<string> expandedArgs = new(rawArgs.Count);
        foreach (string arg in rawArgs)
            expandedArgs.Add(ExpandMacrosInternal(arg, expanding));

        Dictionary<string, int> paramIndex = new(StringComparer.Ordinal);
        for (int idx = 0; idx < parameters.Count; idx++)
            paramIndex[parameters[idx]] = idx;

        string vaRaw = rawArgs.Count > parameters.Count ? string.Join(", ", rawArgs.Skip(parameters.Count)) : "";
        string vaExpanded = expandedArgs.Count > parameters.Count ? string.Join(", ", expandedArgs.Skip(parameters.Count)) : "";

        string body = SubstituteMacroTokens(macro.Body, parameters, paramIndex, rawArgs, expandedArgs, vaRaw, vaExpanded, macro.IsVariadic);
        return ApplyTokenPasting(body);
    }

    private static string SubstituteMacroTokens(
        string body,
        IReadOnlyList<string> parameters,
        Dictionary<string, int> paramIndex,
        List<string> rawArgs,
        List<string> expandedArgs,
        string vaRaw,
        string vaExpanded,
        bool isVariadic)
    {
        StringBuilder sb = new(body.Length);
        int i = 0;
        while (i < body.Length) {
            if (body[i] == '"' || body[i] == '\'') {
                int end = SkipStringLiteral(body, i);
                sb.Append(body, i, end - i);
                i = end;
                continue;
            }
            if (body[i] == '#' && (i + 1 >= body.Length || body[i + 1] != '#')) {
                int j = i + 1;
                while (j < body.Length && char.IsWhiteSpace(body[j])) j++;
                if (j < body.Length && IsIdentStart(body[j])) {
                    int end = j + 1;
                    while (end < body.Length && IsIdentPart(body[end])) end++;
                    string target = body[j..end];
                    if (isVariadic && target == "__VA_ARGS__") {
                        sb.Append('"').Append(vaRaw.Replace("\\", "\\\\").Replace("\"", "\\\"")).Append('"');
                        i = end;
                        continue;
                    }
                    if (paramIndex.TryGetValue(target, out int index)) {
                        string raw = index < rawArgs.Count ? rawArgs[index] : "";
                        sb.Append('"').Append(raw.Replace("\\", "\\\\").Replace("\"", "\\\"")).Append('"');
                        i = end;
                        continue;
                    }
                }
            }
            if (IsIdentStart(body[i])) {
                int start = i;
                i++;
                while (i < body.Length && IsIdentPart(body[i])) i++;
                string ident = body[start..i];
                bool pastedLeft = LookBehindPaste(body, start);
                bool pastedRight = LookAheadPaste(body, i);
                bool useRaw = pastedLeft || pastedRight;
                if (isVariadic && ident == "__VA_ARGS__") {
                    sb.Append(useRaw ? vaRaw : vaExpanded);
                    continue;
                }
                if (paramIndex.TryGetValue(ident, out int index)) {
                    List<string> pool = useRaw ? rawArgs : expandedArgs;
                    sb.Append(index < pool.Count ? pool[index] : "");
                    continue;
                }
                sb.Append(ident);
                continue;
            }
            sb.Append(body[i]);
            i++;
        }
        return sb.ToString();
    }

    private static bool LookBehindPaste(string text, int start)
    {
        int j = start - 1;
        while (j >= 0 && char.IsWhiteSpace(text[j])) j--;
        return j >= 1 && text[j] == '#' && text[j - 1] == '#';
    }

    private static bool LookAheadPaste(string text, int end)
    {
        int j = end;
        while (j < text.Length && char.IsWhiteSpace(text[j])) j++;
        return j + 1 < text.Length && text[j] == '#' && text[j + 1] == '#';
    }

    private static string ApplyTokenPasting(string text)
    {
        if (!text.Contains("##", StringComparison.Ordinal)) return text;
        StringBuilder sb = new(text.Length);
        int i = 0;
        while (i < text.Length) {
            if (text[i] == '"' || text[i] == '\'') {
                int end = SkipStringLiteral(text, i);
                sb.Append(text, i, end - i);
                i = end;
                continue;
            }
            if (i + 1 < text.Length && text[i] == '#' && text[i + 1] == '#') {
                while (sb.Length > 0 && char.IsWhiteSpace(sb[sb.Length - 1]))
                    sb.Length--;
                i += 2;
                while (i < text.Length && char.IsWhiteSpace(text[i]))
                    i++;
                continue;
            }
            sb.Append(text[i]);
            i++;
        }
        return sb.ToString();
    }

    private static bool TryReadMacroArguments(string text, int openParenIndex, out List<string> args, out int afterCloseIndex)
    {
        args = new List<string>();
        afterCloseIndex = openParenIndex;
        int depth = 1;
        int i = openParenIndex + 1;
        StringBuilder current = new();
        while (i < text.Length && depth > 0) {
            char c = text[i];
            if (c == '"' || c == '\'') {
                int end = SkipStringLiteral(text, i);
                current.Append(text, i, end - i);
                i = end;
                continue;
            }
            if (c == '(') {
                depth++;
                current.Append(c);
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    string last = current.ToString().Trim();
                    if (last.Length > 0 || args.Count > 0)
                        args.Add(last);
                    afterCloseIndex = i + 1;
                    return true;
                }
                current.Append(c);
            } else if (c == ',' && depth == 1) {
                args.Add(current.ToString().Trim());
                current.Clear();
            } else {
                current.Append(c);
            }
            i++;
        }
        return false;
    }

    private bool EvaluateCondition(string expr)
    {
        string replacedDefined = ReplaceDefinedOperators(expr);
        string expanded = ExpandMacros(replacedDefined);
        ConditionParser parser = new(expanded);
        return parser.Parse() != 0;
    }

    private string ReplaceDefinedOperators(string expr)
    {
        return Regex.Replace(expr, @"\bdefined\s*(?:\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)|([A-Za-z_][A-Za-z0-9_]*))", match => {
            string name = match.Groups[1].Success ? match.Groups[1].Value : match.Groups[2].Value;
            return macros.ContainsKey(name) ? "1" : "0";
        });
    }

    private sealed class ConditionParser
    {
        private readonly string input;
        private int pos;

        internal ConditionParser(string input) => this.input = input;

        internal long Parse()
        {
            long val = ParseTernary();
            return val;
        }

        private long ParseTernary()
        {
            long cond = ParseLogicalOr();
            SkipWs();
            if (MatchChar('?')) {
                long t = ParseTernary();
                SkipWs();
                MatchChar(':');
                long f = ParseTernary();
                return cond != 0 ? t : f;
            }
            return cond;
        }

        private long ParseLogicalOr()
        {
            long left = ParseLogicalAnd();
            while (true) {
                SkipWs();
                if (!MatchString("||")) return left;
                long right = ParseLogicalAnd();
                left = (left != 0 || right != 0) ? 1 : 0;
            }
        }

        private long ParseLogicalAnd()
        {
            long left = ParseBitOr();
            while (true) {
                SkipWs();
                if (!MatchString("&&")) return left;
                long right = ParseBitOr();
                left = (left != 0 && right != 0) ? 1 : 0;
            }
        }

        private long ParseBitOr()
        {
            long left = ParseBitXor();
            while (true) {
                SkipWs();
                if (PeekString("||") || !MatchChar('|')) return left;
                left |= ParseBitXor();
            }
        }

        private long ParseBitXor()
        {
            long left = ParseBitAnd();
            while (true) {
                SkipWs();
                if (!MatchChar('^')) return left;
                left ^= ParseBitAnd();
            }
        }

        private long ParseBitAnd()
        {
            long left = ParseEquality();
            while (true) {
                SkipWs();
                if (PeekString("&&") || !MatchChar('&')) return left;
                left &= ParseEquality();
            }
        }

        private long ParseEquality()
        {
            long left = ParseRelational();
            while (true) {
                SkipWs();
                if (MatchString("==")) left = left == ParseRelational() ? 1 : 0;
                else if (MatchString("!=")) left = left != ParseRelational() ? 1 : 0;
                else return left;
            }
        }

        private long ParseRelational()
        {
            long left = ParseShift();
            while (true) {
                SkipWs();
                if (MatchString("<=")) left = left <= ParseShift() ? 1 : 0;
                else if (MatchString(">=")) left = left >= ParseShift() ? 1 : 0;
                else if (!PeekString("<<") && MatchChar('<')) left = left < ParseShift() ? 1 : 0;
                else if (!PeekString(">>") && MatchChar('>')) left = left > ParseShift() ? 1 : 0;
                else return left;
            }
        }

        private long ParseShift()
        {
            long left = ParseAdditive();
            while (true) {
                SkipWs();
                if (MatchString("<<")) left <<= (int)ParseAdditive();
                else if (MatchString(">>")) left >>= (int)ParseAdditive();
                else return left;
            }
        }

        private long ParseAdditive()
        {
            long left = ParseMultiplicative();
            while (true) {
                SkipWs();
                if (MatchChar('+')) left += ParseMultiplicative();
                else if (MatchChar('-')) left -= ParseMultiplicative();
                else return left;
            }
        }

        private long ParseMultiplicative()
        {
            long left = ParseUnary();
            while (true) {
                SkipWs();
                if (MatchChar('*')) left *= ParseUnary();
                else if (MatchChar('/')) {
                    long divisor = ParseUnary();
                    left = divisor == 0 ? 0 : left / divisor;
                } else if (MatchChar('%')) {
                    long divisor = ParseUnary();
                    left = divisor == 0 ? 0 : left % divisor;
                } else return left;
            }
        }

        private long ParseUnary()
        {
            SkipWs();
            if (MatchChar('!')) return ParseUnary() == 0 ? 1 : 0;
            if (MatchChar('~')) return ~ParseUnary();
            if (MatchChar('+')) return ParseUnary();
            if (MatchChar('-')) return -ParseUnary();
            return ParsePrimary();
        }

        private long ParsePrimary()
        {
            SkipWs();
            if (MatchChar('(')) {
                long inner = ParseTernary();
                SkipWs();
                MatchChar(')');
                return inner;
            }
            if (pos < input.Length && char.IsDigit(input[pos])) {
                int start = pos;
                while (pos < input.Length && (char.IsLetterOrDigit(input[pos]) || input[pos] == '.')) pos++;
                string token = input[start..pos].TrimEnd('u', 'U', 'l', 'L');
                if (token.StartsWith("0x", StringComparison.OrdinalIgnoreCase))
                    return long.TryParse(token[2..], NumberStyles.HexNumber, CultureInfo.InvariantCulture, out long hex) ? hex : 0;
                if (token.StartsWith("0b", StringComparison.OrdinalIgnoreCase))
                    return Convert.ToInt64(token[2..], 2);
                return long.TryParse(token, NumberStyles.Integer, CultureInfo.InvariantCulture, out long num) ? num : 0;
            }
            if (pos < input.Length && IsIdentStart(input[pos])) {
                pos++;
                while (pos < input.Length && IsIdentPart(input[pos])) pos++;
                SkipWs();
                if (MatchChar('(')) {
                    int depth = 1;
                    while (pos < input.Length && depth > 0) {
                        if (input[pos] == '(') depth++;
                        else if (input[pos] == ')') depth--;
                        pos++;
                    }
                }
                return 0;
            }
            return 0;
        }

        private void SkipWs()
        {
            while (pos < input.Length && char.IsWhiteSpace(input[pos])) pos++;
        }

        private bool MatchChar(char ch)
        {
            if (pos < input.Length && input[pos] == ch) {
                pos++;
                return true;
            }
            return false;
        }

        private bool PeekString(string s)
            => pos + s.Length <= input.Length && input.AsSpan(pos, s.Length).SequenceEqual(s.AsSpan());

        private bool MatchString(string s)
        {
            if (!PeekString(s)) return false;
            pos += s.Length;
            return true;
        }
    }

    private static string StripCommentsPreservingStrings(string text)
    {
        StringBuilder sb = new(text.Length);
        int i = 0;
        while (i < text.Length) {
            char c = text[i];
            if (c == '"' || c == '\'') {
                int end = SkipStringLiteral(text, i);
                sb.Append(text, i, end - i);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < text.Length) {
                if (text[i + 1] == '/') {
                    i += 2;
                    while (i < text.Length && text[i] != '\n') i++;
                    continue;
                }
                if (text[i + 1] == '*') {
                    i += 2;
                    while (i + 1 < text.Length && !(text[i] == '*' && text[i + 1] == '/')) {
                        if (text[i] == '\n') sb.Append('\n');
                        i++;
                    }
                    if (i + 1 < text.Length) i += 2;
                    else i = text.Length;
                    sb.Append(' ');
                    continue;
                }
            }
            sb.Append(c);
            i++;
        }
        return sb.ToString();
    }

    private static List<string> SplitPhysicalLinesJoined(string text)
    {
        string normalized = text.Replace("\r\n", "\n").Replace('\r', '\n');
        string[] raw = normalized.Split('\n');
        List<string> joined = new(raw.Length);
        StringBuilder current = new();
        for (int i = 0; i < raw.Length; i++) {
            string line = raw[i];
            if (line.EndsWith('\\')) {
                current.Append(line, 0, line.Length - 1);
                continue;
            }
            current.Append(line);
            joined.Add(current.ToString());
            current.Clear();
        }
        if (current.Length > 0) joined.Add(current.ToString());
        return joined;
    }

    private static int SkipStringLiteral(string text, int start)
    {
        char quote = text[start];
        int i = start + 1;
        while (i < text.Length) {
            if (text[i] == '\\' && i + 1 < text.Length) {
                i += 2;
                continue;
            }
            if (text[i] == quote)
                return i + 1;
            i++;
        }
        return text.Length;
    }

    private static bool TryReadLeadingIdentifier(string text, out string ident)
    {
        if (text.Length > 0 && IsIdentStart(text[0])) {
            int i = 1;
            while (i < text.Length && IsIdentPart(text[i])) i++;
            ident = text[..i];
            return true;
        }
        ident = "";
        return false;
    }

    private static bool IsIdentStart(char c) => (c is >= 'a' and <= 'z') || (c is >= 'A' and <= 'Z') || c == '_';
    private static bool IsIdentPart(char c) => IsIdentStart(c) || (c is >= '0' and <= '9');

    private static string? FindCompatDirectory(string? root)
    {
        foreach (string? candidateBase in new[] { AppContext.BaseDirectory, Directory.GetCurrentDirectory() }) {
            if (string.IsNullOrWhiteSpace(candidateBase)) continue;
            DirectoryInfo? dir = new(candidateBase);
            while (dir is not null) {
                string compat = Path.Combine(dir.FullName, "tools", "shaders", "shared", "compat");
                if (Directory.Exists(compat) && File.Exists(Path.Combine(compat, "Common.ush")))
                    return Path.GetFullPath(compat);
                dir = dir.Parent;
            }
        }
        _ = root;
        return null;
    }

    private static Dictionary<string, string> DiscoverPluginShaderDirectories(string pluginsRoot)
    {
        Dictionary<string, string> result = new(StringComparer.OrdinalIgnoreCase);
        string fullPluginsRoot = Path.GetFullPath(pluginsRoot);
        foreach (string shaderDir in Directory.EnumerateDirectories(fullPluginsRoot, "Shaders", SearchOption.AllDirectories)) {
            DirectoryInfo? parent = Directory.GetParent(shaderDir);
            if (parent is not null) {
                string fullShaderDir = Path.GetFullPath(shaderDir);
                string relativePlugin = Path.GetRelativePath(fullPluginsRoot, parent.FullName).Replace('\\', '/');
                result[relativePlugin] = fullShaderDir;
                result.TryAdd(parent.Name, fullShaderDir);
            }
        }
        return result;
    }
}
