using System.Text.RegularExpressions;

namespace LumenFx.Compiler.Unreal.Execution;

/** Keeps a NiagaraSpawnInfo constant-buffer value at the same four source words on both graphics backends. */
internal static class GpuSpawnInfoUniforms
{
    private static readonly Regex ExternalBuffer = new(
        @"\bcbuffer\s+(?:PREV_)?FNiagaraExternalParameters\s*\{(?<body>[^}]*)\}[ \t]*;?");
    private static readonly Regex SpawnInfoField = new(
        @"\bNiagaraSpawnInfo\s+(?<name>[a-zA-Z_][a-zA-Z_0-9]*)\s*;");
    private static readonly Regex SpawnInfoLayout = new(
        @"\bstruct\s+NiagaraSpawnInfo\s*\{\s*int\s+Count\s*;\s*float\s+InterpStartDt\s*;\s*float\s+IntervalDt\s*;\s*int\s+SpawnGroup\s*;\s*\}");

    internal static string Pack(string hlsl)
    {
        return ExternalBuffer.Replace(hlsl, buffer => {
            Group body = buffer.Groups["body"];
            var fields = SpawnInfoField.Matches(body.Value);
            if (fields.Count == 0) return buffer.Value;
            if (!SpawnInfoLayout.IsMatch(hlsl))
                throw new ConversionException("Unexpected NiagaraSpawnInfo layout in an external GPU parameter block");

            // DXC prunes unused members of nested uniform structs. A vector preserves all four
            // offsets when the generated code reads only the two middle fields.
            string packed = SpawnInfoField.Replace(body.Value, field => "uint4 " + field.Groups["name"].Value + ";");
            string definitions = string.Join("\n", fields.Select(field => {
                string symbol = field.Groups["name"].Value;
                return $"#define {symbol} LfxDecodeNiagaraSpawnInfo({symbol})";
            }));
            int start = body.Index - buffer.Index;
            return buffer.Value[..start] + packed + buffer.Value[(start + body.Length)..] + "\n"
                + """
                    #ifndef LFX_NIAGARA_SPAWN_INFO_UNIFORM
                    #define LFX_NIAGARA_SPAWN_INFO_UNIFORM
                    NiagaraSpawnInfo LfxDecodeNiagaraSpawnInfo(uint4 words)
                    {
                        NiagaraSpawnInfo value;
                        value.Count = asint(words.x);
                        value.InterpStartDt = asfloat(words.y);
                        value.IntervalDt = asfloat(words.z);
                        value.SpawnGroup = asint(words.w);
                        return value;
                    }
                    #endif
                    """ + "\n" + definitions + "\n";
        });
    }
}
