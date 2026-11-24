namespace LumenFx.Compiler.Unreal.Expressions;

internal static partial class CustomHlslTranslator
{
    private sealed partial class TranslatorInstance
    {
        private readonly HashSet<ExprType> matrixSplats = new();
        private readonly Dictionary<string, (string ReturnType, string Source)> matrixHelpers = new(StringComparer.Ordinal);

        private readonly record struct MatrixMember(int Row, int Column)
        {
            internal string Subscript => $"[{Row}][{Column}]";
        }

        private static List<Tok> NormalizeMatrixTypeTokens(List<Tok> tokens)
        {
            List<Tok> result = new(tokens.Count);
            for (int i = 0; i < tokens.Count; i++) {
                Tok token = tokens[i];
                if (token is not { Kind: TokKind.Ident, Text: "matrix" }) {
                    result.Add(token);
                    continue;
                }
                int open = NextNonNewline(tokens, i + 1);
                if (open >= tokens.Count || tokens[open].Text != "<") {
                    result.Add(new(TokKind.Ident, "float4x4"));
                    continue;
                }
                int scalar = NextNonNewline(tokens, open + 1);
                if (scalar >= tokens.Count)
                    throw new ConversionException("Unsupported Custom HLSL matrix scalar");
                string? scalarKind = tokens[scalar].Text switch {
                    "float" or "half" or "min16float" or "min10float" or "MaterialFloat" => "float",
                    "int" or "min16int" => "int",
                    "uint" or "dword" or "min16uint" => "uint",
                    "bool" => "bool",
                    _ => null
                };
                if (scalarKind is null)
                    throw new ConversionException("Unsupported Custom HLSL matrix scalar");
                int row = NextNonNewline(tokens, scalar + 1), column = row;
                int close = row;
                string rows = "4", columns = "4";
                if (row < tokens.Count && tokens[row].Text == ",") {
                    row = NextNonNewline(tokens, row + 1);
                    if (row >= tokens.Count || tokens[row].Text is not ("1" or "2" or "3" or "4"))
                        throw new ConversionException("Unsupported Custom HLSL matrix row count");
                    rows = tokens[row].Text;
                    close = NextNonNewline(tokens, row + 1);
                    if (close < tokens.Count && tokens[close].Text == ",") {
                        column = NextNonNewline(tokens, close + 1);
                        if (column >= tokens.Count || tokens[column].Text is not ("1" or "2" or "3" or "4"))
                            throw new ConversionException("Unsupported Custom HLSL matrix column count");
                        columns = tokens[column].Text;
                        close = NextNonNewline(tokens, column + 1);
                    }
                }
                if (close >= tokens.Count || tokens[close].Text != ">") throw new ConversionException("Unclosed Custom HLSL matrix type");
                if (scalarKind != "float" && rows != "1" && columns != "1")
                    throw new ConversionException($"Unsupported Custom HLSL {scalarKind}{rows}x{columns} matrix type");
                result.Add(new(TokKind.Ident, $"{scalarKind}{rows}x{columns}"));
                i = close;
            }
            return result;
        }

        private static bool TryParseDegenerateMatrixType(string hlslType, out int rows, out int cols)
        {
            rows = 0;
            cols = 0;
            int x = hlslType.LastIndexOf('x');
            if (x < 2 || x + 1 != hlslType.Length - 1) return false;
            char r = hlslType[x - 1], c = hlslType[x + 1];
            if (r is < '1' or > '4' || c is < '1' or > '4') return false;
            if (r != '1' && c != '1') return false;
            string prefixName = hlslType[..(x - 1)];
            if (prefixName is not ("float" or "half" or "min16float" or "MaterialFloat" or "int" or "uint" or "bool"))
                return false;
            rows = r - '0';
            cols = c - '0';
            return true;
        }

        private bool TryInferDegenerateMatrixShape(List<Tok> rawTokens,
                                                   Dictionary<string, (int Rows, int Cols)> shapes,
                                                   Dictionary<string, string> localVarTypes,
                                                   out int rows, out int cols)
        {
            rows = 0;
            cols = 0;
            var tokens = rawTokens.Where(t => t.Kind != TokKind.Newline).ToList();
            while (tokens.Count > 2 && tokens[0].Text == "(" && FindMatching(tokens, 0, "(", ")") == tokens.Count - 1)
                tokens = tokens.GetRange(1, tokens.Count - 2);
            if (tokens.Count == 0) return false;
            if (tokens.Count == 1 && tokens[0].Kind == TokKind.Ident) {
                string ident = tokens[0].Text;
                if (shapes.TryGetValue(ident, out var shape)) {
                    rows = shape.Rows;
                    cols = shape.Cols;
                    return true;
                }
                var global = allGlobals.FirstOrDefault(g => g.Name == ident || prefix + g.Name == ident);
                if (global is not null && string.IsNullOrEmpty(global.ArraySuffix)
                    && TryParseDegenerateMatrixType(global.Type, out rows, out cols))
                    return true;
                return false;
            }
            // C-style cast `(float1x3)expr` or `(float3x1)expr`
            if (tokens.Count >= 4 && tokens[0].Text == "(" && tokens[1].Kind == TokKind.Ident
                && tokens[2].Text == ")" && TryParseDegenerateMatrixType(tokens[1].Text, out rows, out cols))
                return true;
            if (tokens.Count >= 3 && tokens[0].Kind == TokKind.Ident && tokens[1].Text == "("
                && FindMatching(tokens, 1, "(", ")") == tokens.Count - 1) {
                string callee = tokens[0].Text;
                if (TryParseDegenerateMatrixType(callee, out rows, out cols)) return true;
                var args = SplitByTopLevelComma(tokens.GetRange(2, tokens.Count - 3));
                if (callee == "transpose" && args.Count == 1) {
                    if (TryInferDegenerateMatrixShape(args[0], shapes, localVarTypes, out int r, out int c)) {
                        rows = c;
                        cols = r;
                        return true;
                    }
                    return false;
                }
                if (callee == "mul" && args.Count == 2) {
                    bool hasA = TryInferDegenerateMatrixShape(args[0], shapes, localVarTypes, out int ra, out int ca);
                    bool hasB = TryInferDegenerateMatrixShape(args[1], shapes, localVarTypes, out int rb, out int cb);
                    if (hasA && hasB && ca == rb && (ra == 1 || cb == 1)) {
                        rows = ra;
                        cols = cb;
                        return true;
                    }
                    if (hasA && ra == 1) {
                        var tb = InferGlslExprType(args[1], localVarTypes);
                        if (tb.IsMatrix && ca == tb.MatrixRows) {
                            rows = 1;
                            cols = tb.MatrixColumns;
                            return true;
                        }
                    }
                    if (hasB && cb == 1) {
                        var ta = InferGlslExprType(args[0], localVarTypes);
                        if (ta.IsMatrix && ta.MatrixColumns == rb) {
                            rows = ta.MatrixRows;
                            cols = 1;
                            return true;
                        }
                    }
                    return false;
                }
                var userFn = allFunctions.FirstOrDefault(f => f.Name == callee || prefix + f.Name == callee);
                if (userFn is not null && TryParseDegenerateMatrixType(userFn.ReturnType, out rows, out cols))
                    return true;
            }
            return false;
        }

        private static bool IsMatrixSelector(string selector)
            => selector.Length >= 3 && selector[0] == '_' && (selector[1] == 'm' || char.IsAsciiDigit(selector[1]));

        private static MatrixMember[] ReadMatrixMembers(ExprType type, string selector)
        {
            var members = new List<MatrixMember>(4);
            bool zeroBased = selector.StartsWith("_m", StringComparison.Ordinal);
            int width = zeroBased ? 4 : 3;
            for (int offset = 0; offset < selector.Length; offset += width) {
                int digits = offset + width - 2;
                if (offset + width > selector.Length || selector[offset] != '_' || (zeroBased && selector[offset + 1] != 'm')
                        || !char.IsAsciiDigit(selector[digits]) || !char.IsAsciiDigit(selector[digits + 1]))
                    throw new ConversionException($"Invalid Custom HLSL matrix selector {selector}");
                char origin = zeroBased ? '0' : '1';
                int row = selector[digits] - origin, col = selector[digits + 1] - origin;
                if (row < 0 || row >= type.MatrixRows || col < 0 || col >= type.MatrixColumns)
                    throw new ConversionException($"Custom HLSL matrix selector {selector} exceeds {type.MatrixRows}x{type.MatrixColumns}");
                members.Add(new(row, col));
            }
            if (members.Count is < 1 or > 4) throw new ConversionException($"Invalid Custom HLSL matrix selector width {selector}");
            return members.ToArray();
        }

        private string ReadMatrixSelection(ExprType matrix, MatrixMember[] members, string receiver)
        {
            if (members.Length == 1) return $"({receiver}){members[0].Subscript}";
            string shape = FormatExprGlslType(matrix), result = MatrixSelectionType(members.Length);
            string signature = string.Concat(members.Select(member => $"{member.Row}{member.Column}"));
            string name = $"{prefix}matrix_select_{shape}_{signature}";
            string values = string.Join(", ", members.Select(member => "source" + member.Subscript));
            matrixHelpers.TryAdd(name, (result, $"{result} {name}({shape} source) {{ return {result}({values}); }}"));
            return $"{name}({receiver})";
        }

        private string AssignMatrixSelection(ExprType matrix, MatrixMember[] members, string receiver, string op, string expression,
                                             bool postfix = false)
        {
            if (members.Distinct().Count() != members.Length)
                throw new ConversionException("Custom HLSL matrix assignment repeats a destination component");
            string shape = FormatExprGlslType(matrix), result = MatrixSelectionType(members.Length);
            string signature = string.Concat(members.Select(member => $"{member.Row}{member.Column}"));
            string suffix = op switch {
                "=" => "set", "+=" => "add", "-=" => "sub", "*=" => "mul", "/=" => "div", "%=" => "mod",
                _ => throw new ConversionException($"Unknown Custom HLSL matrix assignment operator {op}")
            };
            string name = $"{prefix}matrix_{(postfix ? "post_" : "")}{suffix}_{shape}_{signature}";
            string before = members.Length == 1 ? "target" + members[0].Subscript :
                $"{result}({string.Join(", ", members.Select(member => "target" + member.Subscript))})";
            string value = op switch {
                "=" => "source",
                "%=" => "left - source * trunc(left / source)",
                _ => $"left {op[0]} source"
            };
            string update = string.Concat(members.Select((member, index) =>
                $" target{member.Subscript} = value{(members.Length == 1 ? "" : "." + "xyzw"[index])};"));
            string read = op == "=" ? "" : $" {result} left = {before};";
            matrixHelpers.TryAdd(name, (result,
                $"{result} {name}(inout {shape} target, {result} source) {{{read} {result} value = {value};{update} return {(postfix ? "left" : "value")}; }}"));
            return $"{name}({receiver}, {result}({expression}))";
        }

        private static bool TryReadLinearMatrixSelector(ExprType type, string selector, out string swizzle)
        {
            swizzle = "";
            if (!type.IsScalar && !type.IsVector) return false;
            var members = new List<MatrixMember>(4);
            bool zeroBased = selector.StartsWith("_m", StringComparison.Ordinal);
            int width = zeroBased ? 4 : 3;
            for (int offset = 0; offset < selector.Length; offset += width) {
                int digits = offset + width - 2;
                if (offset + width > selector.Length || selector[offset] != '_' || (zeroBased && selector[offset + 1] != 'm')
                        || !char.IsAsciiDigit(selector[digits]) || !char.IsAsciiDigit(selector[digits + 1]))
                    throw new ConversionException($"Invalid Custom HLSL matrix selector {selector}");
                char origin = zeroBased ? '0' : '1';
                int row = selector[digits] - origin, col = selector[digits + 1] - origin;
                members.Add(new(row, col));
            }
            if (members.Count is < 1 or > 4) throw new ConversionException($"Invalid Custom HLSL matrix selector width {selector}");
            if (type.IsScalar) {
                if (members.Any(m => m.Row != 0 || m.Column != 0))
                    throw new ConversionException($"Custom HLSL matrix selector {selector} exceeds 1x1");
                swizzle = new string('x', members.Count);
                return true;
            }
            bool rowVector = members.All(m => m.Row == 0 && m.Column >= 0 && m.Column < type.Width);
            bool colVector = members.All(m => m.Column == 0 && m.Row >= 0 && m.Row < type.Width);
            if (!rowVector && !colVector)
                throw new ConversionException($"Custom HLSL matrix selector {selector} exceeds 1x{type.Width}/{type.Width}x1");
            swizzle = new string(members.Select(m => "xyzw"[rowVector ? m.Column : m.Row]).ToArray());
            return true;
        }

        private string MultiplyAssignMatrix(ExprType matrix, string target, string argument)
        {
            string shape = FormatExprGlslType(matrix);
            string name = $"{prefix}matrix_mul_assign_{shape}";
            matrixHelpers.TryAdd(name, (shape,
                $"{shape} {name}(inout {shape} target, {shape} source) {{ target = matrixCompMult(target, source); return target; }}"));
            return $"{name}({target}, {argument})";
        }

        private string BinaryMatrixOp(ExprType matrix, string op, string left, string right, bool leftIsMatrix, bool rightIsMatrix)
        {
            string shape = FormatExprGlslType(matrix);
            string suffix = op switch { "/" => "div", "%" => "mod", "+" => "add", "-" => "sub",
                _ => throw new ConversionException($"Unsupported Custom HLSL matrix binary operator {op}") };
            string leftArg = leftIsMatrix ? left : SplatMatrix(matrix, left);
            string rightArg = rightIsMatrix ? right : SplatMatrix(matrix, right);
            if (op is "+" or "-") return $"(({leftArg}) {op} ({rightArg}))";
            string name = $"{prefix}matrix_{suffix}_{shape}";
            string columns = string.Join(", ", Enumerable.Range(0, matrix.MatrixRows).Select(row => op == "/"
                ? $"a[{row}] / b[{row}]"
                : $"a[{row}] - b[{row}] * trunc(a[{row}] / b[{row}])"));
            matrixHelpers.TryAdd(name, (shape,
                $"{shape} {name}({shape} a, {shape} b) {{ return {shape}({columns}); }}"));
            return $"{name}({leftArg}, {rightArg})";
        }

        private string CompoundAssignMatrix(ExprType matrix, string op, string target, string argument, bool sourceIsMatrix)
        {
            string shape = FormatExprGlslType(matrix);
            if (op == "*=") return sourceIsMatrix ? MultiplyAssignMatrix(matrix, target, argument) : $"({target} *= {argument})";
            if (op is "+=" or "-=" && sourceIsMatrix) return $"({target} {op} {argument})";
            if (op == "/=" && !sourceIsMatrix) return $"({target} /= {argument})";
            string suffix = op switch { "+=" => "add", "-=" => "sub", "/=" => "div", "%=" => "mod",
                _ => throw new ConversionException($"Unsupported Custom HLSL matrix compound assignment {op}") };
            string rhs = sourceIsMatrix ? argument : SplatMatrix(matrix, argument);
            string name = $"{prefix}matrix_{suffix}_assign_{shape}";
            string expr = op switch {
                "+=" => "target + source",
                "-=" => "target - source",
                "/=" => $"{shape}({string.Join(", ", Enumerable.Range(0, matrix.MatrixRows).Select(row => $"target[{row}] / source[{row}]"))})",
                _ => $"{shape}({string.Join(", ", Enumerable.Range(0, matrix.MatrixRows).Select(row => $"target[{row}] - source[{row}] * trunc(target[{row}] / source[{row}])"))})"
            };
            matrixHelpers.TryAdd(name, (shape,
                $"{shape} {name}(inout {shape} target, {shape} source) {{ target = {expr}; return target; }}"));
            return $"{name}({target}, {rhs})";
        }

        private static string MatrixSelectionType(int components) => components == 1 ? "float" : $"vec{components}";

        private static int MatrixAssignmentEnd(List<Tok> tokens, int start)
        {
            int depth = 0, conditionals = 0;
            for (int i = start; i < tokens.Count; i++) {
                string text = tokens[i].Text;
                if (text is "(" or "[" or "{") depth++;
                else if (text is ")" or "]" or "}") {
                    if (depth == 0) return i;
                    depth--;
                } else if (depth == 0) {
                    if (text is ";" or ",") return i;
                    if (text == "?") conditionals++;
                    else if (text == ":") {
                        if (conditionals == 0) return i;
                        conditionals--;
                    }
                }
            }
            return tokens.Count;
        }

        private string MatrixSplatName(ExprType type) => $"{prefix}splat_{FormatExprGlslType(type)}";

        private string SplatMatrix(ExprType type, string expression)
        {
            string matrixType = FormatExprGlslType(type);
            if (IsZeroLiteral(expression)) return $"{matrixType}(0.0)";
            if (IsScalarNumericLiteral(expression)) return $"{matrixType}({SplatColumns(type, $"float({expression})")})";
            matrixSplats.Add(type);
            return $"{MatrixSplatName(type)}(float({expression}))";
        }

        private static string SplatColumns(ExprType type, string value)
            => string.Join(", ", Enumerable.Repeat($"vec{type.MatrixColumns}({value})", type.MatrixRows));

        private void EmitMatrixSplats(List<string> declarations)
        {
            foreach (var type in matrixSplats.OrderBy(type => type.MatrixRows).ThenBy(type => type.MatrixColumns)) {
                string glslType = FormatExprGlslType(type);
                declarations.Add($"{glslType} {MatrixSplatName(type)}(float v) {{ return {glslType}({SplatColumns(type, "v")}); }}");
            }
            foreach (var helper in matrixHelpers.OrderBy(entry => entry.Key, StringComparer.Ordinal))
                declarations.Add(helper.Value.Source);
        }

        private string ConvertPrimitiveCast(ExprType target, ExprType source, string expression)
        {
            string targetName = FormatExprGlslType(target);
            if (source.IsMatrix) {
                if (target.IsScalar) return $"{targetName}(({expression})[0][0])";
                if (target.IsVector) {
                    if (target.Width != source.MatrixRows * source.MatrixColumns)
                        throw new ConversionException($"Invalid Custom HLSL matrix-to-vector cast to {targetName}");
                    string sourceName = FormatExprGlslType(source);
                    string helper = $"{prefix}matrix_cast_{sourceName}_to_{targetName}";
                    // The two dimensions of a float2x2 occupy its four elements in source row order.
                    matrixHelpers.TryAdd(helper, (targetName,
                        $"{targetName} {helper}({sourceName} value) {{ return {targetName}(value[0], value[1]); }}"));
                    return $"{helper}({expression})";
                }
                if (target.MatrixRows > source.MatrixRows || target.MatrixColumns > source.MatrixColumns)
                    throw new ConversionException($"Invalid Custom HLSL matrix shape cast to {targetName}");
            } else if (target.IsMatrix) {
                if (source.IsScalar) return SplatMatrix(target, expression);
                if (source.IsVector && source.Width != target.MatrixRows * target.MatrixColumns)
                    throw new ConversionException($"Invalid Custom HLSL vector-to-matrix cast to {targetName}");
            }
            return $"{targetName}({expression})";
        }

        private static bool TryReadPrimitiveCast(List<Tok> tokens, int open, out ExprType type, out int operand)
        {
            type = ExprType.Unknown;
            operand = -1;
            int typeIndex = NextNonNewline(tokens, open + 1);
            if (typeIndex >= tokens.Count || tokens[typeIndex].Kind != TokKind.Ident) return false;
            type = FromGlslTypeName(MapTypeToken(tokens[typeIndex].Text));
            if (type.Kind == ExprKind.Unknown) return false;
            int close = NextNonNewline(tokens, typeIndex + 1);
            if (close >= tokens.Count || tokens[close].Text != ")") return false;
            operand = NextNonNewline(tokens, close + 1);
            return operand < tokens.Count && IsCastOperandStart(tokens[operand]);
        }

        private static IEnumerable<string> FlattenMatrixInitializer(string initializer)
        {
            foreach (string entry in SplitTopLevelCommasString(initializer[1..^1])) {
                if (entry.StartsWith('{') && entry.EndsWith('}')) {
                    foreach (string component in FlattenMatrixInitializer(entry)) yield return component;
                } else if (entry.Length != 0) {
                    yield return entry;
                }
            }
        }

        // Keep all previous operators at the same precedence. GLSL evaluates them left to right.
        private static int FindMultiplicativeOperandStart(List<Tok> tokens, bool postfix = false)
        {
            int depth = 0;
            for (int i = tokens.Count - 1; i >= 0; i--) {
                string text = tokens[i].Text;
                if (text is ")" or "]") {
                    if (depth == 0 && text == ")" && IsControlHeaderClose(tokens, i)) return i + 1;
                    depth++;
                    continue;
                }
                if (text is "(" or "[") {
                    if (depth == 0) return i + 1;
                    depth--;
                    continue;
                }
                if (depth > 0) continue;
                if (postfix && text is "*" or "/" or "%" or "+" or "-" or "!" or "~" or "++" or "--") return i + 1;
                if (text is "+" or "-") {
                    int previous = i - 1;
                    while (previous >= 0 && tokens[previous].Kind == TokKind.Newline) previous--;
                    if (previous >= 0 && (tokens[previous].Kind is TokKind.Ident or TokKind.Number ||
                            tokens[previous].Text is ")" or "]") && tokens[previous].Text is not "return" and not "else")
                        return i + 1;
                    continue;
                }
                if (text is ";" or "{" or "}" or "," or "=" or "+=" or "-=" or "*=" or "/=" or "%=" or "&=" or "|="
                        or "^=" or "<<=" or ">>=" or "==" or "!=" or "<" or ">" or "<=" or ">=" or "<<" or ">>" or "&&"
                        or "||" or "&" or "|" or "^" or "?" or ":" or "return" or "else")
                    return i + 1;
            }
            return 0;
        }
    }
}
