using System.Text;
using LumenFx.Compiler.Unreal.Serialization;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Scene;

/** Reads the fixed UE5.8.4 FSpline/TangentSpline binary struct when UAssetAPI cannot emit tagged fields. */
internal static class NativeSplineComponentSource
{
    private const string FortniteMainBranchVersion = "601D1886-AC644F84-AA16D3DE-0DEAC7D6";
    private const int AnchorTripleVersion = 243;
    private const int LoopKnotDeltaVersion = 247;
    private const int LargeWorldCoordinatesVersion = 1004;
    private const float SmallTime = .0001f;
    private const int MaxArrayCount = 1_000_000;
    private static readonly string[] ValueTypes = [
        "Float", "Double", "Int32", "Bool", "Vector", "Vector2D", "Vector3f", "Vector4", "Quat", "Rotator", "LinearColor", "Color"
    ];

    internal static JObject Compile(SourceAsset asset, JToken? savedSpline, string authority)
    {
        JObject unavailable = new() {
            ["available"] = false, ["authority"] = authority, ["implementation"] = "Unavailable",
            ["positions"] = new JArray(), ["rotations"] = new JArray(), ["scales"] = new JArray(),
            ["distance"] = new JArray(), ["loop"] = 0, ["reparam_steps"] = 10
        };
        if (savedSpline is not { Type: JTokenType.String }) return unavailable;

        byte[] bytes;
        try { bytes = Convert.FromBase64String(savedSpline.Value<string>() ?? ""); }
        catch (FormatException) { throw new ConversionException($"Invalid raw native spline data in {asset.Name}"); }
        if (bytes.Length == 0) return unavailable;

        using BinaryReader reader = new(new MemoryStream(bytes, writable: false), Encoding.UTF8);
        var archive = new UnrealPackageBinary(reader, asset);
        int customVersion = archive.CustomVersion(FortniteMainBranchVersion);
        byte implementation = reader.ReadByte(); // FSpline::SerializeLoad stores ESplineType before the interface.
        if (implementation != 2)
        {
            unavailable["implementation"] = implementation switch {
                0 => "Unimplemented", 1 => "LegacyTangent", _ => $"Unknown_{implementation}"
            };
            return unavailable;
        }
        if (customVersion < 0) return unavailable;

        try
        {
            NativeData data = ReadTangentSpline(archive, customVersion, UsesLargeWorldCoordinates(asset));
            if (reader.BaseStream.Position != reader.BaseStream.Length)
                throw new ConversionException($"Unconsumed native spline data in {asset.Name}: {reader.BaseStream.Length - reader.BaseStream.Position} bytes");
            if (data.Position.Values.Length % 3 != 0)
                throw new ConversionException($"Invalid native spline anchor layout in {asset.Name}");

            int count = data.Position.Values.Length / 3;
            if (data.Rotation is not null && data.Rotation.Values.Length / 3 != count
                    || data.Scale is not null && data.Scale.Values.Length / 3 != count)
                throw new ConversionException($"Native spline attribute counts disagree in {asset.Name}");

            float nativeLoop = LegacyLoopOffset(data.Position, count);
            float projectedLoop = nativeLoop > 0 ? (count - 1f + 1f) - (count - 1f) : 0;
            JArray positions = PositionKeys(data.Position, nativeLoop, projectedLoop);
            JArray rotations = AttributeKeys(data.Rotation, count, "Quat", [0, 0, 0, 1], projectedLoop);
            JArray scales = AttributeKeys(data.Scale, count, "Vector", [1, 1, 1], projectedLoop);
            JArray distance = new();
            return new JObject {
                ["available"] = true, ["authority"] = authority, ["implementation"] = "Tangent",
                ["positions"] = positions, ["rotations"] = rotations, ["scales"] = scales,
                ["distance"] = distance, ["loop"] = projectedLoop,
                ["reparam_steps"] = 10
            };
        }
        catch (UnsupportedSplineException)
        {
            return unavailable;
        }
    }

    private static bool UsesLargeWorldCoordinates(SourceAsset asset)
    {
        var version = asset.Raw["ObjectVersionUE5"];
        if (version?.Type == JTokenType.Integer) return version.Value<int>() >= LargeWorldCoordinatesVersion;
        string? name = version?.Value<string>()?.Split("::").Last();
        return name is not ("INITIAL_VERSION" or "NAMES_REFERENCED_FROM_EXPORT_DATA" or "PAYLOAD_TOC" or "OPTIONAL_RESOURCES");
    }

    private static float LegacyLoopOffset(BezierData spline, int count)
    {
        if (!spline.Closed || count < 2) return 0;
        if (spline.Knots.Length != 0)
            return Math.Max(0f, (spline.Knots[^1] + spline.LoopDelta) - (count - 1f));

        // The native PolyBezier loader repairs empty PairKnots with centripetal reparameterization.
        var occupied = new HashSet<float>();
        float accumulated = 0, finalKnot = 0, closingLength = 0;
        for (int i = 0; i < count; i++) {
            float knot = accumulated;
            int budget = 4096;
            while (occupied.Contains(knot) && budget-- > 0) {
                float next = NextDistinct(knot);
                if (next == knot) break;
                knot = next;
            }
            if (!occupied.Add(knot)) {
                knot = NextDistinct(accumulated);
                occupied.Add(knot);
            }
            finalKnot = Math.Max(finalKnot, knot);
            var start = spline.Values[3 * i + 1];
            var end = spline.Values[3 * ((i + 1) % count) + 1];
            double x = end[0] - start[0], y = end[1] - start[1], z = end[2] - start[2];
            float segment = (float)Math.Sqrt(Math.Sqrt(x * x + y * y + z * z));
            if (i == count - 1) closingLength = segment;
            float sum = accumulated + segment;
            accumulated = segment > 0 && sum == accumulated ? NextDistinct(accumulated) : sum;
        }
        return Math.Max(0f, (finalKnot + closingLength) - (count - 1f));
    }

    private static float NextDistinct(float value)
    {
        if (!float.IsFinite(value)) return value;
        float minimumNormal = BitConverter.Int32BitsToSingle(0x00800000);
        if (value == 0) return minimumNormal;
        return Math.Clamp(MathF.BitIncrement(value), minimumNormal, float.MaxValue);
    }

    private static NativeData ReadTangentSpline(UnrealPackageBinary archive, int customVersion, bool largeWorldCoordinates)
    {
        ReadInterfaceHeader(archive, TypeId("MultiSpline.TangentBezier", "Vector"));
        byte rootPreInfinity = archive.Reader.ReadByte(), rootPostInfinity = archive.Reader.ReadByte();
        if (rootPreInfinity > 2 || rootPostInfinity > 2) throw new ConversionException("Invalid native spline infinity mode");
        BezierData position = ReadTangentBezier(archive, "Vector", customVersion, largeWorldCoordinates);
        int childCount = archive.Count(MaxArrayCount);
        BezierData? rotation = null, scale = null;
        for (int i = 0; i < childCount; i++)
        {
            string name = archive.Name();
            byte mappingSpace = archive.Reader.ReadByte();
            if (mappingSpace > 1) throw new UnsupportedSplineException();
            ReadFiniteSingle(archive.Reader); // MappingRange.Min
            ReadFiniteSingle(archive.Reader); // MappingRange.Max

            int version = archive.Reader.ReadInt32();
            uint typeId = archive.Reader.ReadUInt32();
            if (version != 1) throw new UnsupportedSplineException();
            string? valueType = ValueTypes.FirstOrDefault(candidate => TypeId("TangentBezier", candidate) == typeId);
            if (valueType is null) throw new UnsupportedSplineException();
            byte preInfinity = archive.Reader.ReadByte(), postInfinity = archive.Reader.ReadByte();
            if (preInfinity > 2 || postInfinity > 2) throw new ConversionException("Invalid native spline infinity mode");
            BezierData child = ReadTangentBezierBody(archive, valueType, customVersion, largeWorldCoordinates);

            if (string.Equals(name, "Rotation", StringComparison.OrdinalIgnoreCase))
            {
                if (valueType != "Quat") throw new ConversionException("Native spline Rotation channel is not quaternion-valued");
                rotation = child;
            }
            else if (string.Equals(name, "Scale", StringComparison.OrdinalIgnoreCase))
            {
                if (valueType != "Vector") throw new ConversionException("Native spline Scale channel is not vector-valued");
                scale = child;
            }
        }
        int savedReparamSteps = archive.Reader.ReadInt32(); // FTangentSpline::Serialize follows TMultiSpline with this field.
        if (savedReparamSteps <= 0) throw new ConversionException("Invalid native spline reparameterization steps");
        return new NativeData(position, rotation, scale);
    }

    private static BezierData ReadTangentBezier(UnrealPackageBinary archive, string valueType, int customVersion, bool largeWorldCoordinates)
    {
        ReadInterfaceHeader(archive, TypeId("TangentBezier", valueType));
        byte preInfinity = archive.Reader.ReadByte(), postInfinity = archive.Reader.ReadByte();
        if (preInfinity > 2 || postInfinity > 2) throw new ConversionException("Invalid native spline infinity mode");
        return ReadTangentBezierBody(archive, valueType, customVersion, largeWorldCoordinates);
    }

    private static BezierData ReadTangentBezierBody(UnrealPackageBinary archive, string valueType, int customVersion, bool largeWorldCoordinates)
    {
        ReadInterfaceHeader(archive, TypeId("PolyBezier", valueType));
        byte preInfinity = archive.Reader.ReadByte(), postInfinity = archive.Reader.ReadByte();
        if (preInfinity > 2 || postInfinity > 2) throw new ConversionException("Invalid native spline infinity mode");

        double[][] values = ReadValues(archive, valueType, largeWorldCoordinates);
        int knotCount = archive.Count(MaxArrayCount);
        float[] knots = new float[knotCount];
        for (int i = 0; i < knotCount; i++)
        {
            knots[i] = ReadFiniteSingle(archive.Reader);
            uint multiplicity = archive.Reader.ReadUInt32();
            if (multiplicity == 0) throw new ConversionException("Invalid native spline knot multiplicity");
        }
        bool closed = archive.Boolean();
        archive.Boolean(); // bClampEnds
        float loopKnotDelta = customVersion >= LoopKnotDeltaVersion ? ReadFiniteSingle(archive.Reader) : 1;

        if (customVersion < AnchorTripleVersion)
            (values, knots, loopKnotDelta) = MigrateLegacyLayout(values, knots, closed, loopKnotDelta, valueType);
        _ = ReadFiniteSingle(archive.Reader); // Tension: the saved anchors already contain their handles.
        int modeCount = archive.Count(MaxArrayCount);
        byte[] tangentModes = archive.Reader.ReadBytes(modeCount);
        if (tangentModes.Length != modeCount) throw new EndOfStreamException();
        foreach (byte mode in tangentModes) if (mode > 9) throw new ConversionException($"Invalid native spline tangent mode {mode}");
        archive.Boolean(); // bStationaryEndpoint; FSplineCurves::FromSplineInterface updates with false.

        int anchors = values.Length / 3;
        if (values.Length % 3 != 0) throw new ConversionException($"Invalid {valueType} PolyBezier anchor data");
        Array.Resize(ref tangentModes, anchors);
        for (int i = modeCount; i < tangentModes.Length; i++) tangentModes[i] = 6; // ETangentMode::Auto load fixup.
        return new BezierData(valueType, values, tangentModes, knots, closed, loopKnotDelta);
    }

    private static double[][] ReadValues(UnrealPackageBinary archive, string valueType, bool largeWorldCoordinates)
    {
        var reader = archive.Reader;
        double Coordinate() => largeWorldCoordinates ? ReadFiniteDouble(reader) : ReadFiniteSingle(reader);
        int count = ReadCount(reader);
        double[][] values = new double[count][];
        for (int i = 0; i < count; i++)
        {
            values[i] = valueType switch {
                "Float" => [ReadFiniteSingle(reader)],
                "Double" => [ReadFiniteDouble(reader)],
                "Int32" => [reader.ReadInt32()],
                "Bool" => [archive.Boolean() ? 1 : 0],
                "Vector" or "Rotator" => [Coordinate(), Coordinate(), Coordinate()],
                "Vector2D" => [Coordinate(), Coordinate()],
                "Vector3f" => [ReadFiniteSingle(reader), ReadFiniteSingle(reader), ReadFiniteSingle(reader)],
                "Vector4" or "Quat" => [Coordinate(), Coordinate(), Coordinate(), Coordinate()],
                "LinearColor" => [ReadFiniteSingle(reader), ReadFiniteSingle(reader), ReadFiniteSingle(reader), ReadFiniteSingle(reader)],
                "Color" => [reader.ReadByte(), reader.ReadByte(), reader.ReadByte(), reader.ReadByte()],
                _ => throw new UnsupportedSplineException()
            };
        }
        return values;
    }

    private static (double[][] Values, float[] Knots, float LoopDelta) MigrateLegacyLayout(
        double[][] legacy, float[] knots, bool closed, float loopDelta, string valueType)
    {
        if (legacy.Length == 0) return (legacy, knots, loopDelta);
        if (legacy.Length <= 3)
        {
            double[] anchor = legacy[0];
            double[][] migrated = [Zero(valueType), anchor, Zero(valueType)];
            return (migrated, [0], loopDelta);
        }
        if (legacy.Length % 4 != 0) throw new ConversionException($"Invalid legacy PolyBezier value count {legacy.Length}");
        int segments = legacy.Length / 4;
        int anchors = closed ? segments : segments + 1;
        double[][] values = new double[anchors * 3][];
        int IndexP(int j) => j == 0 ? 0 : 3 + 4 * (j - 1);
        int IndexC0(int segment) => 1 + 4 * segment;
        int IndexC1(int segment) => 2 + 4 * segment;
        for (int anchorIndex = 0; anchorIndex < anchors; anchorIndex++)
        {
            double[] anchor = legacy[IndexP(anchorIndex)];
            int previousSegment = anchorIndex == 0 ? segments - 1 : anchorIndex - 1;
            double[] incoming = closed || anchorIndex > 0 ? legacy[IndexC1(previousSegment)] : anchor;
            double[] outgoing = closed || anchorIndex < segments ? legacy[IndexC0(anchorIndex)] : anchor;
            int destination = anchorIndex * 3;
            values[destination] = incoming;
            values[destination + 1] = anchor;
            values[destination + 2] = outgoing;
        }
        if (closed && knots.Length > 2)
        {
            loopDelta = knots[^1] - knots[^2];
            Array.Resize(ref knots, knots.Length - 1);
        }
        return (values, knots, loopDelta);
    }

    private static JArray PositionKeys(BezierData spline, float nativeLoop, float projectedLoop)
    {
        int count = spline.Values.Length / 3;
        List<Point> points = new(count);
        for (int i = 0; i < count; i++)
        {
            double[] incoming = spline.Values[i * 3], value = spline.Values[i * 3 + 1], outgoing = spline.Values[i * 3 + 2];
            byte mode = spline.TangentModes[i];
            string interpolation = mode switch {
                0 or 1 or 2 or 3 or 6 or 7 or 9 => "CUBIC", 4 or 8 => "LINEAR", 5 => "CONSTANT",
                _ => throw new UnsupportedSplineException()
            };
            double[] arrive = Scale(Subtract(value, incoming), 3);
            double[] leave = Scale(Subtract(outgoing, value), 3);
            points.Add(new Point(i, value, arrive, leave, interpolation, mode));
        }
        ResolveVectorTangents(points, nativeLoop);
        ResolveVectorTangents(points, projectedLoop);
        return SerializePoints(points);
    }

    private static JArray AttributeKeys(BezierData? spline, int count, string valueType, double[] fallback, float loop)
    {
        List<Point> points = new(count);
        for (int i = 0; i < count; i++)
        {
            double[] value = spline is null ? fallback : spline.Values[i * 3 + 1];
            points.Add(new Point(i, value, Zero(valueType), Zero(valueType), "CUBIC", 6));
        }
        if (valueType == "Vector") ResolveVectorTangents(points, loop);
        else ResolveQuaternionTangents(points, loop);
        return SerializePoints(points);
    }

    private static void ResolveVectorTangents(List<Point> points, float loop)
    {
        int count = points.Count;
        if (count == 0) return;
        bool closed = loop > 0;
        for (int i = 0; i < count; i++)
        {
            Point current = points[i];
            int previousIndex = i == 0 ? (closed ? count - 1 : 0) : i - 1;
            int nextIndex = i == count - 1 ? (closed ? 0 : count - 1) : i + 1;
            Point previous = points[previousIndex], next = points[nextIndex];
            switch (current.TangentMode)
            {
                case 0: case 1: case 6: case 7:
                    if (previous.TangentMode is 0 or 1 or 2 or 3 or 6 or 7)
                    {
                        float previousTime = i == 0 && closed ? current.Time - loop : previous.Time;
                        float nextTime = i == count - 1 && closed ? current.Time + loop : next.Time;
                        current.Arrive = current.Leave = current.TangentMode is 1 or 7
                            ? ClampVectorTangent(previous, previousTime, current, next, nextTime)
                            : Scale(Add(Subtract(current.Value, previous.Value), Subtract(next.Value, current.Value)),
                                1d / Math.Max(SmallTime, nextTime - previousTime));
                    }
                    else {
                        current.Arrive = previous.Arrive;
                        current.Leave = previous.Leave;
                    }
                    break;
                case 4: case 8:
                    current.Leave = Subtract(next.Value, current.Value);
                    current.Arrive = previous.TangentMode is 0 or 1 or 2 or 3 or 6 or 7
                        ? current.Leave : Subtract(current.Value, previous.Value);
                    break;
                case 5:
                    current.Arrive = current.Leave = Zero("Vector");
                    break;
            }
        }
    }

    // InterpCurvePoint.h clamps the FVector overload as six native 32-bit float words.
    private static double[] ClampVectorTangent(Point previous, float previousTime, Point current, Point next, float nextTime)
    {
        var result = new double[3];
        for (int axis = 0; axis < result.Length; axis++) {
            long before = BitConverter.DoubleToInt64Bits(previous.Value[axis]);
            long middle = BitConverter.DoubleToInt64Bits(current.Value[axis]);
            long after = BitConverter.DoubleToInt64Bits(next.Value[axis]);
            int Half(int shift) => BitConverter.SingleToInt32Bits(ClampFloatTangent(
                BitConverter.Int32BitsToSingle((int)(before >> shift)), previousTime,
                BitConverter.Int32BitsToSingle((int)(middle >> shift)), current.Time,
                BitConverter.Int32BitsToSingle((int)(after >> shift)), nextTime));
            result[axis] = BitConverter.Int64BitsToDouble(((long)Half(32) << 32) | (uint)Half(0));
        }
        return result;
    }

    private static float ClampFloatTangent(float previous, float previousTime, float current, float currentTime, float next, float nextTime)
    {
        float lower = current - previous, upper = next - current;
        if (lower >= 0 && upper <= 0 || lower <= 0 && upper >= 0) return 0;
        const float threshold = .333f;
        float height = next - previous;
        float whole = height / Math.Max(SmallTime, nextTime - previousTime);
        float alpha = lower / height;
        float clamped = whole;
        if (alpha < threshold) {
            float edge = lower / Math.Max(SmallTime, currentTime - previousTime);
            float limit = whole + (edge - whole) * (1f - alpha / threshold);
            clamped = height > 0 ? Math.Min(clamped, limit) : Math.Max(clamped, limit);
        }
        if (alpha > 1f - threshold) {
            float edge = upper / Math.Max(SmallTime, nextTime - currentTime);
            float limit = whole + (edge - whole) * ((alpha - (1f - threshold)) / threshold);
            clamped = height > 0 ? Math.Min(clamped, limit) : Math.Max(clamped, limit);
        }
        return clamped;
    }

    private static void ResolveQuaternionTangents(List<Point> points, float loop)
    {
        int count = points.Count;
        if (count == 0) return;
        bool closed = loop > 0;
        for (int i = 0; i < count; i++)
        {
            int previousIndex = i == 0 ? (closed ? count - 1 : 0) : i - 1;
            int nextIndex = i == count - 1 ? (closed ? 0 : count - 1) : i + 1;
            double[] tangent = QuaternionTangent(points[previousIndex].Value, points[i].Value, points[nextIndex].Value);
            float previousTime = i == 0 && closed ? points[i].Time - loop : points[previousIndex].Time;
            float nextTime = i == count - 1 && closed ? points[i].Time + loop : points[nextIndex].Time;
            points[i].Arrive = points[i].Leave = Scale(tangent, 1d / Math.Max(SmallTime, nextTime - previousTime));
        }
    }

    private static double[] QuaternionTangent(double[] previous, double[] current, double[] next)
    {
        double[] inverse = Inverse(current);
        double[] part1 = QuaternionLog(Multiply(inverse, previous));
        double[] part2 = QuaternionLog(Multiply(inverse, next));
        double[] preExp = Scale(Add(part1, part2), -0.5);
        return Multiply(current, QuaternionExp(preExp));
    }

    private static double[] QuaternionLog(double[] q)
    {
        double angle = Math.Abs(q[3]) < 1 ? Math.Acos(q[3]) : 0;
        double sine = Math.Sin(angle);
        if (Math.Abs(q[3]) < 1 && Math.Abs(sine) >= 1e-8) {
            double scale = angle / sine;
            return [q[0] * scale, q[1] * scale, q[2] * scale, 0];
        }
        return [q[0], q[1], q[2], 0];
    }

    private static double[] QuaternionExp(double[] q)
    {
        double angle = Math.Sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2]);
        double sine = Math.Sin(angle);
        if (Math.Abs(sine) >= 1e-8) {
            double scale = sine / angle;
            return [q[0] * scale, q[1] * scale, q[2] * scale, Math.Cos(angle)];
        }
        return [q[0], q[1], q[2], Math.Cos(angle)];
    }

    private static double[] Inverse(double[] q)
    {
        double norm = q.Sum(value => value * value);
        if (norm < 1e-16) return [0, 0, 0, 1];
        return [-q[0] / norm, -q[1] / norm, -q[2] / norm, q[3] / norm];
    }

    private static double[] Multiply(double[] left, double[] right) => [
        left[3] * right[0] + left[0] * right[3] + left[1] * right[2] - left[2] * right[1],
        left[3] * right[1] - left[0] * right[2] + left[1] * right[3] + left[2] * right[0],
        left[3] * right[2] + left[0] * right[1] - left[1] * right[0] + left[2] * right[3],
        left[3] * right[3] - left[0] * right[0] - left[1] * right[1] - left[2] * right[2]
    ];

    private static JArray SerializePoints(IEnumerable<Point> points)
    {
        JArray result = new();
        foreach (Point point in points)
            result.Add(new JObject {
                ["time"] = point.Time, ["value"] = new JArray(point.Value),
                ["arrive"] = new JArray(point.Arrive), ["leave"] = new JArray(point.Leave), ["mode"] = point.Interpolation
            });
        return result;
    }

    private static void ReadInterfaceHeader(UnrealPackageBinary archive, uint expectedType)
    {
        int version = archive.Reader.ReadInt32();
        uint type = archive.Reader.ReadUInt32();
        if (version != 1 || type != expectedType) throw new UnsupportedSplineException();
    }

    private static uint TypeId(string implementation, string valueType)
    {
        static uint Hash(string value)
        {
            uint result = 2166136261;
            foreach (char character in value) result = unchecked((result ^ (uint)character) * 16777619);
            return result;
        }
        return ((Hash(implementation) & 0xffff) << 16) | (Hash(valueType) & 0xffff);
    }

    private static int ReadCount(BinaryReader reader)
    {
        int count = reader.ReadInt32();
        return count is >= 0 and <= MaxArrayCount ? count : throw new ConversionException($"Invalid native spline array count {count}");
    }

    private static float ReadFiniteSingle(BinaryReader reader)
    {
        float value = reader.ReadSingle();
        return float.IsFinite(value) ? value : throw new ConversionException("Non-finite native spline value");
    }

    private static double ReadFiniteDouble(BinaryReader reader)
    {
        double value = reader.ReadDouble();
        return double.IsFinite(value) ? value : throw new ConversionException("Non-finite native spline value");
    }

    private static double[] Zero(string valueType) => valueType switch {
        "Quat" => [0, 0, 0, 1], "Vector" => [0, 0, 0], _ => []
    };
    private static double[] Add(double[] left, double[] right) => left.Select((value, index) => value + right[index]).ToArray();
    private static double[] Subtract(double[] left, double[] right) => left.Select((value, index) => value - right[index]).ToArray();
    private static double[] Scale(double[] value, double scale) => value.Select(component => component * scale).ToArray();

    private sealed record NativeData(BezierData Position, BezierData? Rotation, BezierData? Scale);
    private sealed record BezierData(string ValueType, double[][] Values, byte[] TangentModes, float[] Knots, bool Closed, float LoopDelta);
    private sealed class Point(float time, double[] value, double[] arrive, double[] leave, string interpolation, byte tangentMode)
    {
        internal float Time { get; } = time;
        internal double[] Value { get; } = value;
        internal double[] Arrive { get; set; } = arrive;
        internal double[] Leave { get; set; } = leave;
        internal string Interpolation { get; } = interpolation;
        internal byte TangentMode { get; } = tangentMode;
    }

    private sealed class UnsupportedSplineException : Exception { }
}
