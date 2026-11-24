#if UNITY_EDITOR
using System;
using System.Collections.Generic;
using UnityEditor;
using UnityEngine;

namespace LumenFx.UnityExport
{
    internal sealed class CurveKeyPlan
    {
        public double Time;
        public double Value;
        public double Arrive;
        public double Leave;
        public double? ArriveWeight;
        public double? LeaveWeight;
        public string Interpolation = "linear";

        public CurveKeyPlan Clone()
        {
            return new CurveKeyPlan { Time = Time, Value = Value, Arrive = Arrive, Leave = Leave,
                ArriveWeight = ArriveWeight, LeaveWeight = LeaveWeight, Interpolation = Interpolation };
        }
    }

    internal sealed class CurvePlan
    {
        public readonly List<CurveKeyPlan> Keys = new List<CurveKeyPlan>();
        public string PreWrap = "clamp";
        public string PostWrap = "clamp";

        public static CurvePlan Constant(double value)
        {
            CurvePlan curve = new CurvePlan();
            curve.Keys.Add(new CurveKeyPlan { Time = 0, Value = value, Arrive = 0, Leave = 0, Interpolation = "constant" });
            return curve;
        }

        public CurvePlan Clone()
        {
            CurvePlan result = new CurvePlan { PreWrap = PreWrap, PostWrap = PostWrap };
            foreach (CurveKeyPlan key in Keys) result.Keys.Add(key.Clone());
            return result;
        }

        public bool TryConstant(out double value)
        {
            value = 0;
            if (Keys.Count == 0) return false;
            value = Keys[0].Value;
            for (int i = 1; i < Keys.Count; i++)
            {
                CurveKeyPlan left = Keys[i - 1], right = Keys[i];
                if (right.Value != value) return false;
                if (left.Interpolation == "cubic" && (left.Leave != 0 || right.Arrive != 0)) return false;
            }
            return true;
        }

        public CurvePlan Offset(double delta)
        {
            CurvePlan result = Clone();
            foreach (CurveKeyPlan key in result.Keys) key.Value += delta;
            return result;
        }

        public CurvePlan Multiply(double factor)
        {
            CurvePlan result = Clone();
            foreach (CurveKeyPlan key in result.Keys)
            {
                key.Value *= factor;
                key.Arrive *= factor;
                key.Leave *= factor;
            }
            return result;
        }

        public bool ContentEquals(CurvePlan other)
        {
            if (other == null || Keys.Count != other.Keys.Count || PreWrap != other.PreWrap || PostWrap != other.PostWrap) return false;
            for (int i = 0; i < Keys.Count; i++)
            {
                CurveKeyPlan a = Keys[i], b = other.Keys[i];
                if (a.Time != b.Time || a.Value != b.Value
                    || a.Arrive != b.Arrive || a.Leave != b.Leave || a.ArriveWeight != b.ArriveWeight || a.LeaveWeight != b.LeaveWeight
                    || a.Interpolation != b.Interpolation) return false;
            }
            return true;
        }

        public object ToJson()
        {
            List<object> keys = new List<object>(Keys.Count);
            foreach (CurveKeyPlan key in Keys)
            {
                var data = new Dictionary<string, object>
                {
                    { "time", key.Time },
                    { "value", key.Value },
                    { "arrive", key.Arrive },
                    { "leave", key.Leave },
                    { "interpolation", key.Interpolation }
                };
                if (key.ArriveWeight.HasValue) data["arrive_weight"] = key.ArriveWeight.Value;
                if (key.LeaveWeight.HasValue) data["leave_weight"] = key.LeaveWeight.Value;
                keys.Add(data);
            }
            var result = new Dictionary<string, object> { { "keys", keys } };
            if (PreWrap != "clamp") result["pre_wrap"] = PreWrap;
            if (PostWrap != "clamp") result["post_wrap"] = PostWrap;
            return result;
        }

        internal static bool Nearly(double a, double b)
        {
            double scale = Math.Max(1, Math.Max(Math.Abs(a), Math.Abs(b)));
            return Math.Abs(a - b) <= 1e-6 * scale;
        }
    }

    internal sealed class ScalarPlan
    {
        public CurvePlan Min;
        public CurvePlan Max;

        public static ScalarPlan Constant(double value)
        {
            return new ScalarPlan { Min = CurvePlan.Constant(value), Max = CurvePlan.Constant(value) };
        }

        public static ScalarPlan Range(double minimum, double maximum)
        {
            return new ScalarPlan { Min = CurvePlan.Constant(minimum), Max = CurvePlan.Constant(maximum) };
        }

        public ScalarPlan Clone()
        {
            return new ScalarPlan { Min = Min.Clone(), Max = Max.Clone() };
        }

        public bool TryGetConstant(out double value)
        {
            double minimum, maximum;
            value = 0;
            if (!TryGetConstantRange(out minimum, out maximum) || minimum != maximum) return false;
            value = minimum;
            return true;
        }

        public bool TryGetConstantRange(out double minimum, out double maximum)
        {
            minimum = maximum = 0;
            return Min.TryConstant(out minimum) && Max.TryConstant(out maximum);
        }

        public bool IsZero()
        {
            double minimum, maximum;
            return TryGetConstantRange(out minimum, out maximum)
                   && CurvePlan.Nearly(minimum, 0) && CurvePlan.Nearly(maximum, 0);
        }

        public ScalarPlan Multiply(double factor)
        {
            return new ScalarPlan { Min = Min.Multiply(factor), Max = Max.Multiply(factor) };
        }

        public ScalarPlan Offset(double minimumDelta, double maximumDelta)
        {
            return new ScalarPlan { Min = Min.Offset(minimumDelta), Max = Max.Offset(maximumDelta) };
        }

        public bool ContentEquals(ScalarPlan other)
        {
            return other != null && Min.ContentEquals(other.Min) && Max.ContentEquals(other.Max);
        }

        public object ToJson()
        {
            double minimum, maximum;
            bool minConstant = Min.TryConstant(out minimum);
            bool maxConstant = Max.TryConstant(out maximum);

            if (minConstant && maxConstant && minimum == maximum) return minimum;
            if (minConstant && maxConstant)
                return new Dictionary<string, object> { { "min", minimum }, { "max", maximum } };
            if (Min.ContentEquals(Max)) return CurveJson(Min);

            return new Dictionary<string, object>
            {
                { "min", CurveJson(Min) },
                { "max", CurveJson(Max) }
            };
        }

        private static object CurveJson(CurvePlan curve)
        {
            double value;
            return curve.TryConstant(out value) ? (object)value : curve.ToJson();
        }
    }

    internal sealed class ColorPlan
    {
        public ScalarPlan Red;
        public ScalarPlan Green;
        public ScalarPlan Blue;
        public ScalarPlan Alpha;
        public bool RandomTime;
        public bool MinimumGammaOutput;
        public bool MaximumGammaOutput;

        public static ColorPlan Constant(Color color)
        {
            return new ColorPlan
            {
                Red = ScalarPlan.Constant(color.r),
                Green = ScalarPlan.Constant(color.g),
                Blue = ScalarPlan.Constant(color.b),
                Alpha = ScalarPlan.Constant(color.a)
            };
        }

        public bool TryGetConstant(out Color color)
        {
            color = Color.white;
            if (MinimumGammaOutput != MaximumGammaOutput) return false;
            if (!Red.TryGetConstant(out double red) || !Green.TryGetConstant(out double green)
                || !Blue.TryGetConstant(out double blue) || !Alpha.TryGetConstant(out double alpha)) return false;
            color = new Color((float)red, (float)green, (float)blue, (float)alpha);
            if (MinimumGammaOutput) color = color.gamma;
            return true;
        }

        public object ToJson()
        {
            object[] channels = { Red.ToJson(), Green.ToJson(), Blue.ToJson(), Alpha.ToJson() };
            if (MinimumGammaOutput || MaximumGammaOutput)
                return new Dictionary<string, object>
                {
                    { "components", channels },
                    { "minimum_gamma_output", MinimumGammaOutput },
                    { "maximum_gamma_output", MaximumGammaOutput },
                    { "random_time", RandomTime }
                };
            return RandomTime ? (object)new Dictionary<string, object> { { "random_time", channels } } : channels;
        }
    }

    internal static partial class LumenFxCurves
    {
        public static ScalarPlan FromMinMaxCurve(ParticleSystem.MinMaxCurve source, double multiplier,
            string context, List<ExportIssue> issues)
        {
            switch (source.mode)
            {
                case ParticleSystemCurveMode.Constant:
                    if (!Finite(source.constant))
                    {
                        issues.Add(new ExportIssue(context, "Curve constant is not finite"));
                        return ScalarPlan.Constant(0);
                    }
                    return ScalarPlan.Constant(source.constant * multiplier);
                case ParticleSystemCurveMode.TwoConstants:
                    if (!Finite(source.constantMin) || !Finite(source.constantMax))
                    {
                        issues.Add(new ExportIssue(context, "Curve constants are not finite"));
                        return ScalarPlan.Constant(0);
                    }
                    return ScalarPlan.Range(source.constantMin * multiplier, source.constantMax * multiplier);
                case ParticleSystemCurveMode.Curve:
                    CurvePlan curve = FromAnimationCurve(source.curve, multiplier * source.curveMultiplier, context, issues);
                    return new ScalarPlan { Min = curve, Max = curve };
                case ParticleSystemCurveMode.TwoCurves:
                    double scale = multiplier * source.curveMultiplier;
                    return new ScalarPlan
                    {
                        Min = FromAnimationCurve(source.curveMin, scale, context + " (min)", issues),
                        Max = FromAnimationCurve(source.curveMax, scale, context + " (max)", issues)
                    };
                default:
                    issues.Add(new ExportIssue(context, "Unsupported MinMaxCurve mode " + source.mode));
                    return ScalarPlan.Constant(0);
            }
        }

        private static CurvePlan FromAnimationCurve(AnimationCurve curve, double multiplier,
            string context, List<ExportIssue> issues)
        {
            Keyframe[] keys = curve == null ? null : curve.keys;
            if (keys == null || keys.Length == 0)
            {
                issues.Add(new ExportIssue(context, "A curve has no keys"));
                return CurvePlan.Constant(0);
            }
            if (!Finite(multiplier))
            {
                issues.Add(new ExportIssue(context, "Curve multiplier is not finite"));
                return CurvePlan.Constant(0);
            }

            CurvePlan result = new CurvePlan { PreWrap = CurveWrap(curve.preWrapMode), PostWrap = CurveWrap(curve.postWrapMode) };
            for (int i = 0; i < keys.Length; i++)
            {
                Keyframe key = keys[i];
                if (!Finite(key.time) || !Finite(key.value))
                {
                    issues.Add(new ExportIssue(context, "Curve key time or value is not finite"));
                    return CurvePlan.Constant(0);
                }
                result.Keys.Add(new CurveKeyPlan
                {
                    Time = key.time,
                    Value = key.value * multiplier
                });
            }

            for (int i = 0; i + 1 < keys.Length; i++)
            {
                Keyframe left = keys[i], right = keys[i + 1];
                if (right.time <= left.time)
                {
                    issues.Add(new ExportIssue(context, "Curve key times are not strictly increasing"));
                    return CurvePlan.Constant(0);
                }
                CurveKeyPlan segment = result.Keys[i];
                var leftMode = AnimationUtility.GetKeyRightTangentMode(curve, i);
                var rightMode = AnimationUtility.GetKeyLeftTangentMode(curve, i + 1);
                if (leftMode == AnimationUtility.TangentMode.Constant || rightMode == AnimationUtility.TangentMode.Constant
                    || float.IsPositiveInfinity(left.outTangent) || float.IsPositiveInfinity(right.inTangent))
                {
                    segment.Interpolation = "constant";
                    continue;
                }
                if (!Finite(left.outTangent) || !Finite(right.inTangent))
                {
                    issues.Add(new ExportIssue(context, "Curve segment has a non-finite tangent"));
                    return CurvePlan.Constant(0);
                }
                bool weightedOut = (left.weightedMode & WeightedMode.Out) != 0;
                bool weightedIn = (right.weightedMode & WeightedMode.In) != 0;
                if (leftMode == AnimationUtility.TangentMode.Linear && rightMode == AnimationUtility.TangentMode.Linear
                    && !weightedIn && !weightedOut)
                {
                    segment.Interpolation = "linear";
                    continue;
                }
                segment.Interpolation = "cubic";
                segment.Leave = left.outTangent * multiplier;
                result.Keys[i + 1].Arrive = right.inTangent * multiplier;
                if (weightedOut) segment.LeaveWeight = CurveWeight(left.outWeight, context, issues);
                if (weightedIn) result.Keys[i + 1].ArriveWeight = CurveWeight(right.inWeight, context, issues);
            }

            return result;
        }

        public static ColorPlan FromColorOverLifetime(ParticleSystem.MinMaxGradient source,
            string context, List<ExportIssue> issues)
        {
            return FromMinMaxGradient(source, context, issues);
        }

        public static ColorPlan FromStartColor(ParticleSystem.MinMaxGradient source,
            string context, List<ExportIssue> issues)
        {
            return FromMinMaxGradient(source, context, issues);
        }

        public static ColorPlan FromMinMaxGradient(ParticleSystem.MinMaxGradient source,
            string context, List<ExportIssue> issues)
        {
            switch (source.mode)
            {
                case ParticleSystemGradientMode.Color:
                    return ColorPlan.Constant(source.color);
                case ParticleSystemGradientMode.TwoColors:
                    return FromTwoColors(source.colorMin, source.colorMax);
                case ParticleSystemGradientMode.Gradient:
                    return FromGradient(source.gradient, context, issues);
                case ParticleSystemGradientMode.TwoGradients:
                    return FromTwoGradients(source.gradientMin, source.gradientMax, context, issues);
                case ParticleSystemGradientMode.RandomColor:
                    ColorPlan random = FromGradient(source.gradient, context, issues);
                    random.RandomTime = !random.TryGetConstant(out _);
                    return random;
                default:
                    issues.Add(new ExportIssue(context, "Unsupported gradient mode " + source.mode));
                    return ColorPlan.Constant(Color.white);
            }
        }

        private static ColorPlan FromTwoColors(Color minimum, Color maximum)
        {
            return new ColorPlan
            {
                Red = ScalarPlan.Range(minimum.r, maximum.r),
                Green = ScalarPlan.Range(minimum.g, maximum.g),
                Blue = ScalarPlan.Range(minimum.b, maximum.b),
                Alpha = ScalarPlan.Range(minimum.a, maximum.a)
            };
        }

        private static ColorPlan FromGradient(Gradient gradient, string context, List<ExportIssue> issues)
        {
            if (gradient == null)
            {
                issues.Add(new ExportIssue(context, "A gradient is missing"));
                return ColorPlan.Constant(Color.white);
            }
#if UNITY_2022_2_OR_NEWER
            if (gradient.mode == GradientMode.PerceptualBlend) return FromPerceptualGradient(gradient);
#endif
            if (gradient.mode != GradientMode.Blend && gradient.mode != GradientMode.Fixed)
            {
                issues.Add(new ExportIssue(context, "Unsupported Gradient interpolation mode " + gradient.mode));
                return ColorPlan.Constant(Color.white);
            }

            List<double> times = new List<double> { 0, 1 };
            foreach (GradientColorKey key in gradient.colorKeys) AddTime(times, key.time);
            foreach (GradientAlphaKey key in gradient.alphaKeys) AddTime(times, key.time);
            times.Sort();
            RemoveDuplicateTimes(times);

            var channels = new[] { new CurvePlan(), new CurvePlan(), new CurvePlan(), new CurvePlan() };
            if (gradient.mode == GradientMode.Fixed) WriteFixedGradient(gradient, times, channels);
            else foreach (double time in times) AddColorKey(channels, time, gradient.Evaluate((float)time), "linear");

            return new ColorPlan
            {
                Red = new ScalarPlan { Min = channels[0], Max = channels[0] },
                Green = new ScalarPlan { Min = channels[1], Max = channels[1] },
                Blue = new ScalarPlan { Min = channels[2], Max = channels[2] },
                Alpha = new ScalarPlan { Min = channels[3], Max = channels[3] }
            };
        }

        private static ColorPlan FromTwoGradients(Gradient minimum, Gradient maximum,
            string context, List<ExportIssue> issues)
        {
            ColorPlan low = FromGradient(minimum, context + " (min)", issues);
            ColorPlan high = FromGradient(maximum, context + " (max)", issues);
            return new ColorPlan
            {
                Red = new ScalarPlan { Min = low.Red.Min, Max = high.Red.Min },
                Green = new ScalarPlan { Min = low.Green.Min, Max = high.Green.Min },
                Blue = new ScalarPlan { Min = low.Blue.Min, Max = high.Blue.Min },
                Alpha = new ScalarPlan { Min = low.Alpha.Min, Max = high.Alpha.Min },
                MinimumGammaOutput = low.MinimumGammaOutput,
                MaximumGammaOutput = high.MinimumGammaOutput
            };
        }

        private static void WriteFixedGradient(Gradient gradient, List<double> times, CurvePlan[] channels)
        {
            // Probe both sides of each authored boundary and retain the editor's evaluated transitions.
            Color current = gradient.Evaluate(0);
            AddColorKey(channels, 0, current, "constant");
            float previous = 0;
            for (int i = 1; i < times.Count; i++)
            {
                float middle = (float)((times[i - 1] + times[i]) * 0.5);
                WriteFixedGradientSpan(gradient, channels, previous, middle, ref current);
                float end = (float)times[i];
                WriteFixedGradientSpan(gradient, channels, middle, end, ref current);
                previous = end;
            }
        }

        private static void WriteFixedGradientSpan(Gradient gradient, CurvePlan[] channels,
            float start, float end, ref Color current)
        {
            Color endpoint = gradient.Evaluate(end);
            while (!current.Equals(endpoint))
            {
                float low = start, high = end;
                while (true)
                {
                    float mid = (float)(((double)low + high) * 0.5);
                    if (mid <= low || mid >= high) break;
                    if (gradient.Evaluate(mid).Equals(current)) low = mid;
                    else high = mid;
                }
                current = gradient.Evaluate(high);
                AddColorKey(channels, high, current, "constant");
                start = high;
            }
        }

        private static void AddColorKey(CurvePlan[] channels, double time, Color color, string interpolation)
        {
            for (int channel = 0; channel < channels.Length; channel++)
                channels[channel].Keys.Add(new CurveKeyPlan
                    { Time = time, Value = color[channel], Interpolation = interpolation });
        }

        private static void AddTime(List<double> times, double time)
        {
            times.Add(Math.Max(0, Math.Min(1, time)));
        }

        private static void RemoveDuplicateTimes(List<double> times)
        {
            for (int i = times.Count - 1; i > 0; i--)
                if (times[i] == times[i - 1]) times.RemoveAt(i);
        }

        private static string CurveWrap(WrapMode mode)
        {
            if (mode == WrapMode.Loop) return "loop";
            if (mode == WrapMode.PingPong) return "ping_pong";
            return "clamp";
        }

        private static double CurveWeight(float value, string context, List<ExportIssue> issues)
        {
            if (Finite(value) && value >= 0 && value <= 1) return value;
            issues.Add(new ExportIssue(context, "AnimationCurve tangent weight is outside [0, 1]"));
            return 1.0 / 3.0;
        }

        private static bool Finite(double value)
        {
            return !double.IsNaN(value) && !double.IsInfinity(value);
        }
    }
}
#endif
