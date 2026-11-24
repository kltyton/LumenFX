#if UNITY_EDITOR && UNITY_2022_2_OR_NEWER
using System;
using System.Collections.Generic;
using UnityEngine;

namespace LumenFx.UnityExport
{
    internal static partial class LumenFxCurves
    {
        private static ColorPlan FromPerceptualGradient(Gradient gradient)
        {
            bool gamma = gradient.colorSpace != ColorSpace.Linear;
            var keys = new SortedDictionary<float, Color>();
            foreach (GradientColorKey key in gradient.colorKeys)
                keys[key.time] = keys.ContainsKey(key.time) ? gradient.Evaluate(key.time) : key.color;

            var colorCurves = new[] { new CurvePlan(), new CurvePlan(), new CurvePlan() };
            double[] previousRoots = null;
            double previousTime = 0;
            foreach (var key in keys)
            {
                Color linear = gamma ? key.Value.linear : key.Value;
                double[] roots = LinearRgbToOklabRoots(linear);
                double[] values = LmsToLinearRgb(Cube(roots[0]), Cube(roots[1]), Cube(roots[2]));
                double[] arrive = null;
                if (previousRoots != null)
                {
                    double duration = key.Key - previousTime;
                    double lRate = 3 * (roots[0] - previousRoots[0]) / duration;
                    double mRate = 3 * (roots[1] - previousRoots[1]) / duration;
                    double sRate = 3 * (roots[2] - previousRoots[2]) / duration;
                    double[] leave = LmsToLinearRgb(
                        lRate * Square(previousRoots[0]), mRate * Square(previousRoots[1]), sRate * Square(previousRoots[2]));
                    arrive = LmsToLinearRgb(lRate * Square(roots[0]), mRate * Square(roots[1]), sRate * Square(roots[2]));
                    for (int channel = 0; channel < colorCurves.Length; ++channel)
                    {
                        List<CurveKeyPlan> colorKeys = colorCurves[channel].Keys;
                        colorKeys[colorKeys.Count - 1].Leave = leave[channel];
                    }
                }
                for (int channel = 0; channel < colorCurves.Length; ++channel)
                    colorCurves[channel].Keys.Add(new CurveKeyPlan
                    {
                        Time = key.Key,
                        Value = values[channel],
                        Arrive = arrive == null ? 0 : arrive[channel],
                        Interpolation = "cubic"
                    });
                previousRoots = roots;
                previousTime = key.Key;
            }

            var alphaCurve = new CurvePlan();
            var alphaTimes = new SortedSet<float>();
            foreach (GradientAlphaKey key in gradient.alphaKeys) alphaTimes.Add(key.time);
            foreach (float time in alphaTimes)
                alphaCurve.Keys.Add(new CurveKeyPlan { Time = time, Value = gradient.Evaluate(time).a });

            return new ColorPlan
            {
                Red = new ScalarPlan { Min = colorCurves[0], Max = colorCurves[0] },
                Green = new ScalarPlan { Min = colorCurves[1], Max = colorCurves[1] },
                Blue = new ScalarPlan { Min = colorCurves[2], Max = colorCurves[2] },
                Alpha = new ScalarPlan { Min = alphaCurve, Max = alphaCurve },
                MinimumGammaOutput = gamma,
                MaximumGammaOutput = gamma
            };
        }

        // The inverse OKLab transform cubes linear interpolants. Endpoint derivatives therefore
        // preserve each interval as an ordinary cubic curve, with independent color and alpha times.
        private static double[] LinearRgbToOklabRoots(Color color)
        {
            double l = CubeRoot(0.4122214708 * color.r + 0.5363325363 * color.g + 0.0514459929 * color.b);
            double m = CubeRoot(0.2119034982 * color.r + 0.6806995451 * color.g + 0.1073969566 * color.b);
            double s = CubeRoot(0.0883024619 * color.r + 0.2817188376 * color.g + 0.6299787005 * color.b);
            double lightness = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s;
            double a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
            double b = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
            return new[]
            {
                lightness + 0.3963377774 * a + 0.2158037573 * b,
                lightness - 0.1055613458 * a - 0.0638541728 * b,
                lightness - 0.0894841775 * a - 1.2914855480 * b
            };
        }

        private static double[] LmsToLinearRgb(double l, double m, double s) => new[]
        {
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        };

        private static double Square(double value) => value * value;
        private static double Cube(double value) => value * value * value;
        private static double CubeRoot(double value) => Math.Sign(value) * Math.Pow(Math.Abs(value), 1.0 / 3.0);
    }
}
#endif
