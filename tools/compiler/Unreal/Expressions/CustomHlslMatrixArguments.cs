namespace LumenFx.Compiler.Unreal.Expressions;

internal static partial class CustomHlslTranslator
{
    private sealed partial class TranslatorInstance
    {
        private sealed record MatrixArgument(List<Tok> Receiver, MatrixMember[] Members);
        private sealed record MatrixArgumentWrapper(string ReturnType, string Prototype, string Definition);
        private readonly Dictionary<string, MatrixArgumentWrapper> matrixArgumentWrappers = new(StringComparer.Ordinal);
        private readonly Dictionary<string, string> matrixArgumentWrapperNames = new(StringComparer.Ordinal);
        private List<string>? matrixArgumentLocals;
        private int matrixArgumentIndex;

        private bool IsMatrixArgumentCallee(string name) => name is "sincos" or "modf" or "frexp"
            || allFunctions.Any(function => function.Name == name && function.Params.Any(WritesArgument));

        private string? LowerMatrixArguments(string name, List<List<Tok>> args, Dictionary<string, string> types,
                                             HashSet<string> renamed, string paramsStruct,
                                             Func<List<Tok>, string> translate)
        {
            var selections = args.Select(arg => FindMatrixArgument(arg, types)).ToArray();
            if (!selections.Any(selection => selection is not null)) return null;
            var actualTypes = args.Select(arg => InferGlslExprType(arg, types)).ToArray();
            var candidates = allFunctions.Where(function => function.Name == name && function.Params.Count == args.Count).ToList();
            string callee = RenameSymbol(name, renamed);
            if (candidates.Count == 0 && name is ("sincos" or "modf" or "frexp")) {
                if ((name == "sincos" && args.Count != 3) || ((name is "modf" or "frexp") && args.Count != 2)) return null;
                ExprType operand = actualTypes[0];
                if (!operand.IsScalar && !operand.IsVector) return null;
                string type = MatrixSelectionType(operand.Width);
                var intrinsic = new DeclFunction { ReturnType = name == "sincos" ? "void" : type };
                intrinsic.Params.Add(new DeclParam { Type = type });
                for (int index = 1; index < args.Count; index++)
                    intrinsic.Params.Add(new DeclParam { Type = type, Qualifiers = "out" });
                candidates.Add(intrinsic);
                if (name == "sincos") {
                    usedSupportHelpers.Add("sincos");
                    callee = "lfx_ch_sincos";
                } else if (name == "frexp") {
                    usedSupportHelpers.Add("frexp");
                    callee = "lfx_ch_frexp";
                }
            }
            var ranked = candidates.Select(function => (Function: function, Score: MatrixArgumentScore(function, actualTypes)))
                .OrderByDescending(candidate => candidate.Score).ToArray();
            if (ranked.Length == 0 || ranked[0].Score < 0) return null;
            var signature = ranked[0].Function;
            bool[] lowered = selections.Select((selection, i) => selection is not null && WritesArgument(signature.Params[i])).ToArray();
            if (!lowered.Contains(true)) return null;
            if (ranked.Length > 1 && ranked[1].Score == ranked[0].Score)
                throw new ConversionException($"Ambiguous Custom HLSL matrix output overload for {name}");

            List<string> actual = new();
            List<string> formals = new();
            List<string> forwarded = new();
            List<string> beforeCall = new();
            List<string> afterCall = new();
            List<string> pattern = new();
            for (int index = 0; index < args.Count; index++) {
                var parameter = signature.Params[index];
                if (parameter.IsSamplerState) continue;
                string type = parameter.Type is "FMaterialPixelParameters" or "FMaterialVertexParameters"
                    ? paramsStruct : RenameType(parameter.Type, renamed);
                var formalType = FromGlslTypeName(type);
                string variable = "parameter" + index;
                forwarded.Add(variable);
                if (!lowered[index]) {
                    string qualifier = parameter.Qualifiers.Length == 0 ? "" : parameter.Qualifiers + " ";
                    formals.Add($"{qualifier}{type} {variable}{parameter.ArraySuffix}");
                    string argument = translate(args[index]);
                    if (!WritesArgument(parameter) && parameter.ArraySuffix.Length == 0
                        && CanConvertCallValue(actualTypes[index], formalType))
                        argument = ConvertCallValue(argument, actualTypes[index], formalType, type);
                    actual.Add(argument);
                    pattern.Add($"{qualifier}{type}{parameter.ArraySuffix}");
                    continue;
                }

                var selection = selections[index]!;
                int width = selection.Members.Length;
                bool inputOutput = parameter.Qualifiers == "inout";
                string selectionTypeName = MatrixSelectionType(width);
                var selectionType = FromGlslTypeName(selectionTypeName);
                if (parameter.ArraySuffix.Length != 0 || !CanConvertCallValue(formalType, selectionType)
                    || (inputOutput && !CanConvertCallValue(selectionType, formalType)))
                    throw new ConversionException($"Custom HLSL matrix output cannot be bound to parameter {index + 1} of {name}");
                if (selection.Members.Distinct().Count() != width)
                    throw new ConversionException($"Custom HLSL matrix output repeats a component in parameter {index + 1} of {name}");
                string direction = inputOutput ? "inout" : "out";
                pattern.Add($"{direction}{type}:matrix{width}");
                List<string> scalars = new(width);
                string writeback = "writeback" + index;
                afterCall.Add($"{selectionTypeName} {writeback} = {ConvertCallValue(variable, formalType, selectionType, selectionTypeName)};");
                var (first, subsequent) = CaptureMatrixArgumentReceiver(selection, translate);
                for (int member = 0; member < width; member++) {
                    string scalar = $"component{index}_{member}";
                    scalars.Add(scalar);
                    formals.Add($"{direction} float {scalar}");
                    string receiver = member == 0 ? first : subsequent;
                    actual.Add($"({receiver}){selection.Members[member].Subscript}");
                    string source = width == 1 ? writeback : writeback + "." + "xyzw"[member];
                    afterCall.Add($"{scalar} = {source};");
                }
                if (inputOutput) {
                    string selectionValue = width == 1 ? scalars[0] : $"{selectionTypeName}({string.Join(", ", scalars)})";
                    beforeCall.Add($"{type} {variable} = {ConvertCallValue(selectionValue, selectionType, formalType, type)};");
                } else {
                    beforeCall.Add($"{type} {variable};");
                }
            }

            string key = $"{callee}({string.Join(",", pattern)})";
            if (!matrixArgumentWrapperNames.TryGetValue(key, out string? wrapper)) {
                wrapper = $"{prefix}matrix_arguments_{matrixArgumentWrappers.Count}";
                string returnType = RenameType(signature.ReturnType, renamed);
                string declaration = $"{returnType} {wrapper}({string.Join(", ", formals)})";
                string invocation = $"{callee}({string.Join(", ", forwarded)})";
                string statement = returnType == "void" ? $"{invocation};" : $"{returnType} result = {invocation};";
                string returnValue = returnType == "void" ? "" : " return result;";
                matrixArgumentWrapperNames.Add(key, wrapper);
                matrixArgumentWrappers.Add(wrapper, new(returnType, declaration + ";",
                    $"{declaration} {{ {string.Join(" ", beforeCall)} {statement} {string.Join(" ", afterCall)}{returnValue} }}"));
            }
            return $"{wrapper}({string.Join(", ", actual)})";
        }

        private MatrixArgument? FindMatrixArgument(List<Tok> argument, Dictionary<string, string> types)
        {
            var tokens = argument.Where(token => token.Kind != TokKind.Newline).ToList();
            while (tokens.Count > 2 && tokens[0].Text == "(" && FindMatching(tokens, 0, "(", ")") == tokens.Count - 1)
                tokens = tokens.GetRange(1, tokens.Count - 2);
            if (tokens.Count < 3 || tokens[^2].Text != "." || !IsMatrixSelector(tokens[^1].Text)) return null;
            var receiver = tokens.GetRange(0, tokens.Count - 2);
            var type = InferGlslExprType(receiver, types);
            return type.IsMatrix ? new MatrixArgument(receiver, ReadMatrixMembers(type, tokens[^1].Text)) : null;
        }

        private (string First, string Subsequent) CaptureMatrixArgumentReceiver(MatrixArgument argument, Func<List<Tok>, string> translate)
        {
            var receiver = argument.Receiver;
            if (argument.Members.Length == 1 || !receiver.Any(token => token.Text == "[")) {
                string value = translate(receiver);
                return (value, value);
            }
            var locals = matrixArgumentLocals ?? throw new ConversionException("Indexed Custom HLSL matrix output requires a function scope");
            List<Tok> first = new(), subsequent = new();
            for (int index = 0; index < receiver.Count; index++) {
                var token = receiver[index];
                first.Add(token);
                subsequent.Add(token);
                if (token.Text != "[") continue;
                int close = FindMatching(receiver, index, "[", "]");
                if (close <= index) throw new ConversionException("Unclosed Custom HLSL matrix output index");
                string indexExpression = translate(receiver.GetRange(index + 1, close - index - 1));
                string temporary = $"{prefix}matrix_index_{matrixArgumentIndex++}";
                locals.Add($"int {temporary};");
                // GLSL evaluates call arguments left to right. Each index is assigned when the first selected lvalue is evaluated.
                first.Add(new Tok(TokKind.Ident, $"({temporary} = int({indexExpression}))"));
                subsequent.Add(new Tok(TokKind.Ident, temporary));
                first.Add(receiver[close]);
                subsequent.Add(receiver[close]);
                index = close;
            }
            return (translate(first), translate(subsequent));
        }

        private static bool WritesArgument(DeclParam parameter) => parameter.Qualifiers is "out" or "inout";

        private static int MatrixArgumentScore(DeclFunction signature, ExprType[] actualTypes)
        {
            int score = 0;
            for (int index = 0; index < actualTypes.Length; index++) {
                var parameter = signature.Params[index];
                var formal = FromGlslTypeName(parameter.Type);
                var actual = actualTypes[index];
                if (formal == ExprType.Unknown || actual == ExprType.Unknown || parameter.ArraySuffix.Length != 0) continue;
                if (formal == actual) score += 3;
                else {
                    if (parameter.Qualifiers != "out" && !CanConvertCallValue(actual, formal)) return -1;
                    if (WritesArgument(parameter) && !CanConvertCallValue(formal, actual)) return -1;
                    score += formal.Width == actual.Width ? 2 : 1;
                }
            }
            return score;
        }

        private static bool CanConvertCallValue(ExprType source, ExprType destination) =>
            (source.IsScalar || source.IsVector) && (destination.IsScalar || destination.IsVector)
            && (source.IsScalar || source.Width >= destination.Width);

        private static string ConvertCallValue(string expression, ExprType source, ExprType destination, string type)
        {
            if (source == destination) return expression;
            if (source.Width > destination.Width) expression = $"({expression}).{"xyzw"[..destination.Width]}";
            return $"{type}({expression})";
        }
    }
}
