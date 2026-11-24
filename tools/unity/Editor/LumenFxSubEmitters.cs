#if UNITY_EDITOR
using System.Collections.Generic;
using UnityEngine;

namespace LumenFx.UnityExport
{
    internal static class LumenFxSubEmitters
    {
        internal static HashSet<ParticleSystem> CollectLinkedSystems(ParticleSystem[] systems)
        {
            var linked = new HashSet<ParticleSystem>();
            foreach (var source in systems)
            {
                var module = source.subEmitters;
                if (!module.enabled) continue;
                for (int i = 0; i < module.subEmittersCount; i++)
                {
                    var target = module.GetSubEmitterSystem(i);
                    if (target == null) continue;
                    linked.Add(source);
                    linked.Add(target);
                }
            }
            return linked;
        }

        internal static void WriteLinks(ParticleSystem[] systems,
            Dictionary<ParticleSystem, Dictionary<string, object>> emitters, List<ExportIssue> issues)
        {
            foreach (var source in systems)
            {
                var module = source.subEmitters;
                if (!module.enabled || module.subEmittersCount == 0) continue;
                if (!emitters.TryGetValue(source, out var entry))
                {
                    issues.Add(new ExportIssue(source.name, "Sub-emitter source is absent from the exported systems"));
                    continue;
                }

                var links = new List<object>();
                for (int i = 0; i < module.subEmittersCount; i++)
                {
                    var target = module.GetSubEmitterSystem(i);
                    if (target == null || !emitters.TryGetValue(target, out var child))
                    {
                        issues.Add(new ExportIssue(source.name, "Sub-emitter " + i + " does not reference an exported ParticleSystem"));
                        continue;
                    }
                    links.Add(new Dictionary<string, object>
                    {
                        { "target", child["name"] },
                        { "event", module.GetSubEmitterType(i).ToString().ToLowerInvariant() },
                        { "inherit", (int)module.GetSubEmitterProperties(i) },
                        { "probability", module.GetSubEmitterEmitProbability(i) }
                    });
                }
                if (links.Count > 0) ((Dictionary<string, object>)entry["unity_renderer"])["sub_emitters"] = links;
            }
        }
    }
}
#endif
