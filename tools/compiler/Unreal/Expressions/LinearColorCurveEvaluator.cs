using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Expressions;

/// <summary>
/// Evaluates UE 5.9 <c>UCurveLinearColor::GetLinearColorValue</c> and <c>FRichCurve::Eval</c>
/// for compile-time constant <c>ColorRamp</c> inputs.
/// </summary>
internal static class LinearColorCurveEvaluator
{
    private const float KindaSmallNumber = 1e-4f;
    private const float SmallNumber = 1e-8f;
    private const float MaxFloatThreshold = 3.4e38f;

    private enum InterpMode { Linear, Constant, Cubic, None }
    private enum TangentWeightMode { None, Arrive, Leave, Both }
    private enum ExtrapMode { Cycle, CycleWithOffset, Oscillate, Linear, Constant, None }

    private readonly record struct RichKey(
        float Time,
        float Value,
        float ArriveTangent,
        float ArriveTangentWeight,
        float LeaveTangent,
        float LeaveTangentWeight,
        InterpMode Interp,
        TangentWeightMode WeightMode);

    private readonly record struct RichCurve(
        RichKey[] Keys,
        float DefaultValue,
        ExtrapMode PreExtrap,
        ExtrapMode PostExtrap);

    public static double[] EvaluateLinearColor(SourceObject curveObject, float time)
    {
        JObject props = curveObject.Properties;
        JArray curves = props["FloatCurves"] as JArray ?? new JArray();

        float r = EvalRichCurve(ReadCurve(curves, 0), time, 0.0f);
        float g = EvalRichCurve(ReadCurve(curves, 1), time, 0.0f);
        float b = EvalRichCurve(ReadCurve(curves, 2), time, 0.0f);
        RichCurve alphaCurve = ReadCurve(curves, 3);
        float a = alphaCurve.Keys.Length == 0 ? 1.0f : EvalRichCurve(alphaCurve, time, 0.0f);

        float adjustBrightness = (float)SourceAsset.Number(props["AdjustBrightness"], 1.0);
        float adjustBrightnessCurve = (float)SourceAsset.Number(props["AdjustBrightnessCurve"], 1.0);
        float adjustVibrance = (float)SourceAsset.Number(props["AdjustVibrance"], 0.0);
        float adjustSaturation = (float)SourceAsset.Number(props["AdjustSaturation"], 1.0);
        float adjustHue = (float)SourceAsset.Number(props["AdjustHue"], 0.0);
        float adjustMinAlpha = (float)SourceAsset.Number(props["AdjustMinAlpha"], 0.0);
        float adjustMaxAlpha = (float)SourceAsset.Number(props["AdjustMaxAlpha"], 1.0);

        bool shouldClampValue = MathF.Max(r, MathF.Max(g, b)) < 1.0f + KindaSmallNumber;

        (float hue, float sat, float val) = LinearRgbToHsv(r, g, b);

        val *= adjustBrightness;
        if (MathF.Abs(adjustBrightnessCurve - 1.0f) > KindaSmallNumber && adjustBrightnessCurve != 0.0f)
            val = MathF.Pow(val, adjustBrightnessCurve);

        if (MathF.Abs(adjustVibrance) > KindaSmallNumber)
        {
            float invSatRaised = MathF.Pow(1.0f - sat, 5.0f);
            float clampedVibrance = Math.Clamp(adjustVibrance, 0.0f, 1.0f);
            sat += clampedVibrance * 0.5f * invSatRaised;
        }

        sat *= adjustSaturation;
        hue += adjustHue;

        if (shouldClampValue) val = Math.Clamp(val, 0.0f, 1.0f);

        (float outR, float outG, float outB) = HsvToLinearRgb(hue, sat, val);
        float outA = adjustMinAlpha + (adjustMaxAlpha - adjustMinAlpha) * a;
        return new double[] { outR, outG, outB, outA };
    }

    private static RichCurve ReadCurve(JArray curves, int index)
    {
        JObject? channel = index < curves.Count ? curves[index] as JObject : null;
        if (channel is null)
            return new RichCurve(Array.Empty<RichKey>(), float.MaxValue, ExtrapMode.Constant, ExtrapMode.Constant);

        float defaultVal = channel["DefaultValue"] is null || channel["DefaultValue"]!.Type == JTokenType.Null
            ? float.MaxValue
            : (float)SourceAsset.Number(channel["DefaultValue"], float.MaxValue);
        ExtrapMode pre = ParseExtrap(channel["PreInfinityExtrap"]);
        ExtrapMode post = ParseExtrap(channel["PostInfinityExtrap"]);

        JArray rawKeys = channel["Keys"] as JArray ?? new JArray();
        RichKey[] keys = rawKeys.OfType<JObject>()
            .Select(k => new RichKey(
                (float)SourceAsset.Number(k["Time"]),
                (float)SourceAsset.Number(k["Value"]),
                (float)SourceAsset.Number(k["ArriveTangent"]),
                (float)SourceAsset.Number(k["ArriveTangentWeight"]),
                (float)SourceAsset.Number(k["LeaveTangent"]),
                (float)SourceAsset.Number(k["LeaveTangentWeight"]),
                ParseInterp(k["InterpMode"]),
                ParseWeightMode(k["TangentWeightMode"])))
            .OrderBy(k => k.Time)
            .ToArray();
        return new RichCurve(keys, defaultVal, pre, post);
    }

    private static InterpMode ParseInterp(JToken? token)
    {
        if (token is null || token.Type == JTokenType.Null) return InterpMode.Linear;
        if (token.Type == JTokenType.Integer)
        {
            return token.Value<int>() switch
            {
                0 => InterpMode.Linear,
                1 => InterpMode.Constant,
                2 => InterpMode.Cubic,
                _ => InterpMode.None
            };
        }
        string name = (token.Value<string>() ?? "RCIM_Linear").Split("::").Last();
        return name switch
        {
            "RCIM_Constant" => InterpMode.Constant,
            "RCIM_Cubic" => InterpMode.Cubic,
            "RCIM_None" => InterpMode.None,
            _ => InterpMode.Linear
        };
    }

    private static TangentWeightMode ParseWeightMode(JToken? token)
    {
        if (token is null || token.Type == JTokenType.Null) return TangentWeightMode.None;
        if (token.Type == JTokenType.Integer)
        {
            return token.Value<int>() switch
            {
                1 => TangentWeightMode.Arrive,
                2 => TangentWeightMode.Leave,
                3 => TangentWeightMode.Both,
                _ => TangentWeightMode.None
            };
        }
        string name = (token.Value<string>() ?? "RCTWM_WeightedNone").Split("::").Last();
        return name switch
        {
            "RCTWM_WeightedArrive" => TangentWeightMode.Arrive,
            "RCTWM_WeightedLeave" => TangentWeightMode.Leave,
            "RCTWM_WeightedBoth" => TangentWeightMode.Both,
            _ => TangentWeightMode.None
        };
    }

    private static ExtrapMode ParseExtrap(JToken? token)
    {
        if (token is null || token.Type == JTokenType.Null) return ExtrapMode.Constant;
        if (token.Type == JTokenType.Integer)
        {
            return token.Value<int>() switch
            {
                0 => ExtrapMode.Cycle,
                1 => ExtrapMode.CycleWithOffset,
                2 => ExtrapMode.Oscillate,
                3 => ExtrapMode.Linear,
                4 => ExtrapMode.Constant,
                _ => ExtrapMode.None
            };
        }
        string name = (token.Value<string>() ?? "RCCE_Constant").Split("::").Last();
        return name switch
        {
            "RCCE_Cycle" => ExtrapMode.Cycle,
            "RCCE_CycleWithOffset" => ExtrapMode.CycleWithOffset,
            "RCCE_Oscillate" => ExtrapMode.Oscillate,
            "RCCE_Linear" => ExtrapMode.Linear,
            "RCCE_None" => ExtrapMode.None,
            _ => ExtrapMode.Constant
        };
    }

    private static void CycleTime(float minTime, float maxTime, ref float inTime, ref int cycleCount)
    {
        float initTime = inTime;
        float duration = maxTime - minTime;
        if (duration <= 0.0f) return;

        if (inTime > maxTime)
        {
            cycleCount = (int)MathF.Floor((maxTime - inTime) / duration);
            inTime = inTime + duration * cycleCount;
        }
        else if (inTime < minTime)
        {
            cycleCount = (int)MathF.Floor((inTime - minTime) / duration);
            inTime = inTime - duration * cycleCount;
        }

        if (inTime == maxTime && initTime < minTime) inTime = minTime;
        if (inTime == minTime && initTime > maxTime) inTime = maxTime;

        cycleCount = Math.Abs(cycleCount);
    }

    private static void RemapTimeValue(RichCurve curve, ref float inTime, ref float cycleValueOffset)
    {
        int numKeys = curve.Keys.Length;
        if (numKeys < 2) return;

        if (inTime <= curve.Keys[0].Time)
        {
            if (curve.PreExtrap is not (ExtrapMode.Linear or ExtrapMode.Constant))
            {
                float minTime = curve.Keys[0].Time;
                float maxTime = curve.Keys[numKeys - 1].Time;
                int cycleCount = 0;
                CycleTime(minTime, maxTime, ref inTime, ref cycleCount);

                if (curve.PreExtrap == ExtrapMode.CycleWithOffset)
                {
                    float dv = curve.Keys[0].Value - curve.Keys[numKeys - 1].Value;
                    cycleValueOffset = dv * cycleCount;
                }
                else if (curve.PreExtrap == ExtrapMode.Oscillate && (cycleCount % 2) == 1)
                {
                    inTime = minTime + (maxTime - inTime);
                }
            }
        }
        else if (inTime >= curve.Keys[numKeys - 1].Time)
        {
            if (curve.PostExtrap is not (ExtrapMode.Linear or ExtrapMode.Constant))
            {
                float minTime = curve.Keys[0].Time;
                float maxTime = curve.Keys[numKeys - 1].Time;
                int cycleCount = 0;
                CycleTime(minTime, maxTime, ref inTime, ref cycleCount);

                if (curve.PostExtrap == ExtrapMode.CycleWithOffset)
                {
                    float dv = curve.Keys[numKeys - 1].Value - curve.Keys[0].Value;
                    cycleValueOffset = dv * cycleCount;
                }
                else if (curve.PostExtrap == ExtrapMode.Oscillate && (cycleCount % 2) == 1)
                {
                    inTime = minTime + (maxTime - inTime);
                }
            }
        }
    }

    private static float EvalRichCurve(RichCurve curve, float inTime, float inDefaultValue)
    {
        float cycleValueOffset = 0.0f;
        RemapTimeValue(curve, ref inTime, ref cycleValueOffset);

        int numKeys = curve.Keys.Length;
        float interpVal = curve.DefaultValue >= MaxFloatThreshold ? inDefaultValue : curve.DefaultValue;

        if (numKeys == 0)
        {
            return interpVal + cycleValueOffset;
        }
        if (numKeys < 2 || inTime < curve.Keys[0].Time)
        {
            if (curve.PreExtrap == ExtrapMode.Linear && numKeys > 1)
            {
                float dt = curve.Keys[1].Time - curve.Keys[0].Time;
                if (MathF.Abs(dt) <= SmallNumber)
                    interpVal = curve.Keys[0].Value;
                else
                    interpVal = (curve.Keys[1].Value - curve.Keys[0].Value) / dt * (inTime - curve.Keys[0].Time) + curve.Keys[0].Value;
            }
            else
            {
                interpVal = curve.Keys[0].Value;
            }
        }
        else if (inTime <= curve.Keys[numKeys - 1].Time)
        {
            int first = 1;
            int count = numKeys - 1 - first;
            while (count > 0)
            {
                int step = count / 2;
                int middle = first + step;
                if (inTime >= curve.Keys[middle].Time)
                {
                    first = middle + 1;
                    count -= step + 1;
                }
                else
                {
                    count = step;
                }
            }
            interpVal = EvalForTwoKeys(curve.Keys[first - 1], curve.Keys[first], inTime);
        }
        else
        {
            if (curve.PostExtrap == ExtrapMode.Linear)
            {
                float dt = curve.Keys[numKeys - 2].Time - curve.Keys[numKeys - 1].Time;
                if (MathF.Abs(dt) <= SmallNumber)
                    interpVal = curve.Keys[numKeys - 1].Value;
                else
                    interpVal = (curve.Keys[numKeys - 2].Value - curve.Keys[numKeys - 1].Value) / dt
                        * (inTime - curve.Keys[numKeys - 1].Time) + curve.Keys[numKeys - 1].Value;
            }
            else
            {
                interpVal = curve.Keys[numKeys - 1].Value;
            }
        }

        return interpVal + cycleValueOffset;
    }

    private static float EvalForTwoKeys(RichKey key1, RichKey key2, float inTime)
    {
        float diff = key2.Time - key1.Time;
        if (diff <= 0.0f) return key1.Value;

        if (key1.Interp == InterpMode.Constant)
            return inTime < key2.Time ? key1.Value : key2.Value;

        float alpha = (inTime - key1.Time) / diff;
        float p0 = key1.Value;
        float p3 = key2.Value;

        if (key1.Interp == InterpMode.Linear)
            return p0 + (p3 - p0) * alpha;

        bool notWeighted = (key1.WeightMode is TangentWeightMode.None or TangentWeightMode.Arrive)
            && (key2.WeightMode is TangentWeightMode.None or TangentWeightMode.Leave);
        if (notWeighted)
        {
            const float oneThird = 1.0f / 3.0f;
            float p1 = p0 + key1.LeaveTangent * diff * oneThird;
            float p2 = p3 - key2.ArriveTangent * diff * oneThird;
            return BezierInterp(p0, p1, p2, p3, alpha);
        }

        return WeightedEvalForTwoKeys(key1, key2, inTime);
    }

    private static float WeightedEvalForTwoKeys(RichKey key1, RichKey key2, float inTime)
    {
        float diff = key2.Time - key1.Time;
        float alpha = (inTime - key1.Time) / diff;
        const float oneThird = 1.0f / 3.0f;
        float x = key2.Time - key1.Time;

        float angle1 = MathF.Atan(key1.LeaveTangent);
        float leaveWeight = key1.WeightMode is TangentWeightMode.None or TangentWeightMode.Arrive
            ? MathF.Sqrt(x * x + (key1.LeaveTangent * x) * (key1.LeaveTangent * x)) * oneThird
            : key1.LeaveTangentWeight;
        float key1TanX = MathF.Cos(angle1) * leaveWeight + key1.Time;
        float key1TanY = MathF.Sin(angle1) * leaveWeight + key1.Value;

        float angle2 = MathF.Atan(key2.ArriveTangent);
        float arriveWeight = key2.WeightMode is TangentWeightMode.None or TangentWeightMode.Leave
            ? MathF.Sqrt(x * x + (key2.ArriveTangent * x) * (key2.ArriveTangent * x)) * oneThird
            : key2.ArriveTangentWeight;
        float key2TanX = -MathF.Cos(angle2) * arriveWeight + key2.Time;
        float key2TanY = -MathF.Sin(angle2) * arriveWeight + key2.Value;

        double normalizedX1 = (key1TanX - key1.Time) / x;
        double normalizedX2 = (key2TanX - key1.Time) / x;

        BezierToPower(0.0, normalizedX1, normalizedX2, 1.0, out double c3, out double c2, out double c1, out double c0);
        Span<double> coeffs = stackalloc double[4] { c0 - alpha, c1, c2, c3 };
        Span<double> results = stackalloc double[3];
        int numResults = SolveCubic(coeffs, results);

        double newInterp = alpha;
        if (numResults == 1)
        {
            newInterp = results[0];
        }
        else
        {
            newInterp = double.MinValue;
            for (int i = 0; i < numResults; i++)
            {
                double r = results[i];
                if ((r > 0.0 || Math.Abs(r) <= SmallNumber) && (r < 1.0 || Math.Abs(r - 1.0) <= SmallNumber))
                {
                    if (newInterp < 0.0 || r > newInterp) newInterp = r;
                }
            }
            if (newInterp == double.MinValue) newInterp = 0.0;
        }

        return BezierInterp(key1.Value, key1TanY, key2TanY, key2.Value, (float)newInterp);
    }

    private static float BezierInterp(float p0, float p1, float p2, float p3, float alpha)
    {
        float p01 = p0 + (p1 - p0) * alpha;
        float p12 = p1 + (p2 - p1) * alpha;
        float p23 = p2 + (p3 - p2) * alpha;
        float p012 = p01 + (p12 - p01) * alpha;
        float p123 = p12 + (p23 - p12) * alpha;
        return p012 + (p123 - p012) * alpha;
    }

    private static void BezierToPower(double a1, double b1, double c1, double d1,
        out double a2, out double b2, out double c2, out double d2)
    {
        double a = b1 - a1;
        double b = c1 - b1;
        double c = d1 - c1;
        double d = b - a;
        a2 = c - b - d;
        b2 = 3.0 * d;
        c2 = 3.0 * a;
        d2 = a1;
    }

    private static int SolveCubic(ReadOnlySpan<double> coeff, Span<double> solution)
    {
        static double Cbrt(double v) => v > 0.0 ? Math.Pow(v, 1.0 / 3.0) : v < 0.0 ? -Math.Pow(-v, 1.0 / 3.0) : 0.0;

        double denom = coeff[3] != 0.0 ? coeff[3] : 1e-16;
        double a = coeff[2] / denom;
        double b = coeff[1] / denom;
        double c = coeff[0] / denom;

        double sqA = a * a;
        double p = (1.0 / 3.0) * (-(1.0 / 3.0) * sqA + b);
        double q = 0.5 * ((2.0 / 27.0) * a * sqA - (1.0 / 3.0) * a * b + c);
        double cubeP = p * p * p;
        double d = q * q + cubeP;

        int num;
        if (Math.Abs(d) <= 1e-8)
        {
            if (Math.Abs(q) <= 1e-8)
            {
                solution[0] = 0.0;
                num = 1;
            }
            else
            {
                double u = Cbrt(-q);
                solution[0] = 2.0 * u;
                solution[1] = -u;
                num = 2;
            }
        }
        else if (d < 0.0)
        {
            double phi = (1.0 / 3.0) * Math.Acos(Math.Clamp(-q / Math.Sqrt(-cubeP), -1.0, 1.0));
            double t = 2.0 * Math.Sqrt(-p);
            solution[0] = t * Math.Cos(phi);
            solution[1] = -t * Math.Cos(phi + Math.PI / 3.0);
            solution[2] = -t * Math.Cos(phi - Math.PI / 3.0);
            num = 3;
        }
        else
        {
            double sqrtD = Math.Sqrt(d);
            solution[0] = Cbrt(sqrtD - q) - Cbrt(sqrtD + q);
            num = 1;
        }

        double sub = (1.0 / 3.0) * a;
        for (int i = 0; i < num; i++) solution[i] -= sub;
        return num;
    }

    private static (float H, float S, float V) LinearRgbToHsv(float r, float g, float b)
    {
        float rgbMin = MathF.Min(r, MathF.Min(g, b));
        float rgbMax = MathF.Max(r, MathF.Max(g, b));
        float rgbRange = rgbMax - rgbMin;
        if (rgbMax <= 0.0f) return (0.0f, 0.0f, 0.0f);

        float hue = rgbMax == rgbMin ? 0.0f
            : rgbMax == r ? ((((g - b) / rgbRange) * 60.0f + 360.0f) % 360.0f)
            : rgbMax == g ? (((b - r) / rgbRange) * 60.0f + 120.0f)
            : (((r - g) / rgbRange) * 60.0f + 240.0f);
        float saturation = rgbMax == 0.0f ? 0.0f : rgbRange / rgbMax;
        return (hue, saturation, rgbMax);
    }

    private static (float R, float G, float B) HsvToLinearRgb(float h, float s, float v)
    {
        float hue = h % 360.0f;
        if (hue < 0.0f) hue += 360.0f;
        float sat = Math.Clamp(s, 0.0f, 1.0f);

        float hDiv60 = hue / 60.0f;
        int floor = (int)hDiv60;
        float frac = hDiv60 - floor;

        float v0 = v;
        float v1 = v * (1.0f - sat);
        float v2 = v * (1.0f - frac * sat);
        float v3 = v * (1.0f - (1.0f - frac) * sat);

        return (floor % 6) switch
        {
            0 => (v0, v3, v1),
            1 => (v2, v0, v1),
            2 => (v1, v0, v3),
            3 => (v1, v2, v0),
            4 => (v3, v1, v0),
            _ => (v0, v1, v2)
        };
    }
}
