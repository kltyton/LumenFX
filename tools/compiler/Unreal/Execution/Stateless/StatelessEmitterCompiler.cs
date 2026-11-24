using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution.Stateless;

/** Stateless definitions carry their own lifecycle and source modules, without manufacturing VM scripts. */
internal static class StatelessEmitterCompiler
{
    internal static JObject Compile(SourceAsset asset, SourceObject system, JObject handle, EmitterHandleSource.Resolved emitter, AssetLibrary? library, bool preciseMotionVectors)
    {
        JObject source = emitter.Settings;
        StatelessTemplateSource template = StatelessTemplateSource.Resolve(asset, source, library);
        uint allowedFeatures = source.Value<uint?>("AllowedFeatureMask") ?? 3u;
        if (!template.DefaultShader) allowedFeatures &= 2u;
        foreach (var module in template.Modules)
            if (module.Enabled && !StatelessVariables.GpuOutputs(module.ClassName)) allowedFeatures &= 2u;
        StatelessDatasetCompiler.Result dataset = StatelessDatasetCompiler.Compile(asset, source, template,
            allowedFeatures, emitter.Namespace, preciseMotionVectors);
        StatelessDataBuilder data = new();
        StatelessParameterCompiler parameters = new(asset, system, source, library);
        StatelessDistributionCompiler distributions = new(data, parameters);
        JArray attributes = dataset.Attributes;
        StatelessModuleCompiler moduleCompiler = new(attributes, distributions, parameters, NiagaraImporter.NiagaraVersion(asset), data);
        JArray modules = new();
        StatelessTemplateSource.Module? initializer = null;
        foreach (StatelessTemplateSource.Module module in template.Modules) {
            JObject properties = module.Properties;
            uint moduleSeed = data.BeginModule();
            JObject? built = moduleCompiler.Compile(module.ClassName, properties);
            modules.Add(new JObject { ["class"] = module.ClassName, ["seed"] = moduleSeed,
                ["enabled"] = module.Enabled, ["class_default"] = module.ClassDefault,
                ["properties"] = properties.DeepClone(), ["compiled"] = built });
            if (module.ClassName == "NiagaraStatelessModule_InitializeParticle") initializer ??= module;
        }
        JObject lifetime = distributions.Range(initializer?.Properties["LifetimeDistribution"], new[] { initializer is null ? 0f : 1f }, new[] { 0f });
        JObject state = source["EmitterState"] as JObject ?? new();
        JObject duration = distributions.Scalar(state["LoopDuration"], 1, clampDuration: true);
        JObject delay = distributions.Scalar(state["LoopDelay"], 0);
        float authoredDurationMaximum = (float)SourceAsset.Number(state["LoopDuration"]?["Max"], 1);
        JArray spawns = new();
        foreach (JObject spawn in (source["SpawnInfos"] as JArray ?? new JArray()).OfType<JObject>()) {
            string type = StatelessDistributionCompiler.EnumValue(spawn["Type"], "Burst");
            if (type is not ("Burst" or "Rate" or "0" or "1")) throw new ConversionException($"Unknown stateless spawn type {type}");
            bool rate = type is "Rate" or "1";
            // CacheFromCompiledData validates raw serialized endpoints, even for a bound distribution.
            double Raw(JToken? value, string name, double fallback) => SourceAsset.Number(value?[name], fallback);
            float spawnTime = (float)SourceAsset.Number(spawn["SpawnTime"]);
            bool valid = rate
                ? (float)Raw(spawn["Rate"], "Min", 60) + (float)Raw(spawn["Rate"], "Max", 60) > 0f
                : unchecked((int)Raw(spawn["Amount"], "Min", 1) + (int)Raw(spawn["Amount"], "Max", 1)) > 0
                    && spawnTime >= 0f && spawnTime < authoredDurationMaximum;
            if (spawn.Value<bool?>("bEnabled") == false || !valid) continue;
            spawns.Add(new JObject { ["type"] = rate ? "Rate" : "Burst", ["spawn_time"] = spawnTime,
                ["amount"] = distributions.Scalar(rate ? null : spawn["Amount"], 1, true),
                ["rate"] = distributions.Scalar(rate ? spawn["Rate"] : null, 60),
                ["probability_enabled"] = spawn.Value<bool?>("bSpawnProbabilityEnabled") ?? false,
                ["probability"] = distributions.Scalar(spawn["SpawnProbability"], 1),
                ["loop_limit_enabled"] = spawn.Value<bool?>("bLoopCountLimitEnabled") ?? false,
                ["loop_limit"] = distributions.Scalar(spawn["LoopCountLimit"], 1, true) });
        }
        JObject metadata = new() {
            ["sim_target"] = emitter.Target, ["allowed_features"] = allowedFeatures,
            ["template"] = template.Path,
            ["default_shader"] = template.DefaultShader, ["has_initializer"] = initializer is not null,
            ["scalability"] = NiagaraScalabilitySource.Emitter(asset, system, source, library),
            ["renderer_count"] = (source["RendererProperties"] as JArray)?.Count ?? 0,
            ["shader_outputs"] = dataset.ShaderOutputs, ["shader_output_offsets"] = dataset.ShaderOffsets,
            ["state"] = new JObject {
                ["loop_behavior"] = StatelessDistributionCompiler.EnumValue(state["LoopBehavior"], "Infinite"),
                ["duration_mode"] = StatelessDistributionCompiler.EnumValue(state["LoopDurationMode"], "Fixed"),
                ["loop_count"] = state.Value<int?>("LoopCount") ?? 1, ["duration"] = duration, ["delay"] = delay,
                ["delay_enabled"] = state.Value<bool?>("bLoopDelayEnabled") ?? false,
                ["recalculate_duration"] = state.Value<bool?>("bRecalculateDurationEachLoop") ?? false,
                ["first_delay_only"] = state.Value<bool?>("bDelayFirstLoopOnly") ?? false,
                ["recalculate_delay"] = state.Value<bool?>("bRecalculateDelayEachLoop") ?? false,
                ["inactive_response"] = StatelessDistributionCompiler.EnumValue(state["InactiveResponse"], "Complete"),
                ["reset_age_on_awaken"] = state.Value<bool?>("bResetAgeOnAwaken") ?? false,
                ["visibility_culling"] = state.Value<bool?>("bEnableVisibilityCulling") ?? false,
                ["visibility_delay"] = SourceAsset.Number(state["VisibilityCullDelay"], 1),
                ["visibility_reaction"] = StatelessDistributionCompiler.EnumValue(state["VisibilityCullReaction"], "SleepAndLetParticlesFinish"),
                ["distance_culling"] = state.Value<bool?>("bEnableDistanceCulling") == true
                    && (state.Value<bool?>("bMinDistanceEnabled") == true || state.Value<bool?>("bMaxDistanceEnabled") == true),
                ["minimum_distance"] = SourceAsset.Number(state["MinDistance"], 0), ["maximum_distance"] = SourceAsset.Number(state["MaxDistance"], 5000),
                ["minimum_reaction"] = StatelessDistributionCompiler.EnumValue(state["MinDistanceReaction"], "Awaken"),
                ["maximum_reaction"] = StatelessDistributionCompiler.EnumValue(state["MaxDistanceReaction"], "SleepAndLetParticlesFinish") },
            ["lifetime"] = lifetime, ["spawns"] = spawns, ["modules"] = modules,
            ["parameters"] = parameters.Definition(), ["static_words"] = new JArray(data.StaticWords())
        };
        return new JObject {
            ["name"] = handle.Value<string>("Name")!.ToLowerInvariant(), ["source_name"] = emitter.Namespace,
            ["handle_id"] = handle.Value<string>("Id"), ["local_space"] = true, ["max_particles"] = 1000000,
            ["bounds_mode"] = "Fixed", ["fixed_bounds"] = VectorProgramCompiler.Bounds(source["FixedBounds"]),
            ["random_seed"] = (source.Value<int?>("RandomSeed") ?? 0) ^ 0xdefa081,
            ["deterministic"] = source.Value<bool?>("bDeterministic") ?? false,
            ["attributes"] = attributes,
            ["spawn_info"] = new JArray(), ["stateless"] = metadata
        };
    }
}
