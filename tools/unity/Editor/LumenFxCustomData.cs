#if UNITY_EDITOR
using System.Collections.Generic;
using UnityEngine;

namespace LumenFx.UnityExport
{
    internal static class LumenFxCustomData
    {
        internal static Dictionary<string, object> Build(ParticleSystem.CustomDataModule source,
            ParticleSystemRenderer renderer, string context, List<ExportIssue> issues)
        {
            var streams = new List<ParticleSystemVertexStream>(renderer.activeVertexStreamsCount);
            renderer.GetActiveVertexStreams(streams);
            var output = new Dictionary<string, object>();
            int width1 = StreamWidth(streams, ParticleSystemVertexStream.Custom1X);
            int width2 = StreamWidth(streams, ParticleSystemVertexStream.Custom2X);
            if (width1 != 0) WriteStream(output, source, ParticleSystemCustomData.Custom1, width1, "custom1", context, issues);
            if (width2 != 0) WriteStream(output, source, ParticleSystemCustomData.Custom2, width2, "custom2", context, issues);
            return output.Count == 0 ? null : output;
        }

        private static int StreamWidth(List<ParticleSystemVertexStream> streams, ParticleSystemVertexStream first)
        {
            int width = 0;
            foreach (var stream in streams)
            {
                int components = (int)stream - (int)first + 1;
                if (components >= 1 && components <= 4) width = Mathf.Max(width, components);
            }
            return width;
        }

        private static void WriteStream(Dictionary<string, object> output, ParticleSystem.CustomDataModule source,
            ParticleSystemCustomData stream, int width, string key, string context, List<ExportIssue> issues)
        {
            switch (source.GetMode(stream))
            {
                case ParticleSystemCustomDataMode.Disabled:
                    return;
                case ParticleSystemCustomDataMode.Color:
                    output[key] = new Dictionary<string, object>
                    {
                        { "color", LumenFxCurves.FromMinMaxGradient(source.GetColor(stream), context + " " + key, issues).ToJson() },
                        { "width", width }
                    };
                    return;
                case ParticleSystemCustomDataMode.Vector:
                    int count = source.GetVectorComponentCount(stream);
                    if (count < 1 || count > 4)
                    {
                        issues.Add(new ExportIssue(context, key + " has an invalid component count"));
                        return;
                    }
                    var components = new object[] { 0.0, 0.0, 0.0, 0.0 };
                    for (int i = 0; i < Mathf.Min(count, width); i++)
                        components[i] = LumenFxCurves.FromMinMaxCurve(source.GetVector(stream, i), 1,
                            context + " " + key + " component " + i, issues).ToJson();
                    output[key] = new Dictionary<string, object> { { "vector", components } };
                    return;
            }
        }
    }
}
#endif
