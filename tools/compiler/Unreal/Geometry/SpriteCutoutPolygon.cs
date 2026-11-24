namespace LumenFx.Compiler.Unreal.Geometry;

internal static class SpriteCutoutPolygon
{
    internal readonly record struct Point(double X, double Y)
    {
        public static Point operator -(Point a, Point b) => new(a.X - b.X, a.Y - b.Y);
        public static Point operator +(Point a, Point b) => new(a.X + b.X, a.Y + b.Y);
        public static Point operator *(Point a, float scale) => new(a.X * scale, a.Y * scale);
    }

    private readonly record struct Line(Point Position, Point Direction);

    internal static List<Point> Hull(IReadOnlyList<Point> points)
    {
        if (points.Count == 0) return [];
        int first = 0;
        for (int i = 1; i < points.Count; i++)
            if (points[i].X < points[first].X || points[i].X == points[first].X && points[i].Y < points[first].Y) first = i;
        List<Point> result = [];
        int current = first;
        do
        {
            result.Add(points[current]);
            int end = 0;
            for (int i = 1; i < points.Count; i++)
                if (end == current || Determinant(points[end], points[current], points[i]) < 0) end = i;
            current = end;
        } while (current != first);
        return result;
    }

    internal static List<Point> Fit(IReadOnlyList<Point> hull, int target)
    {
        int count = Math.Min(target, hull.Count);
        if (count is not (4 or 8)) return hull.Take(count).ToList();
        Line[] lines = Enumerable.Range(0, hull.Count)
            .Select(i => new Line(hull[i], hull[(i + 1) % hull.Count] - hull[i])).ToArray();
        Point[] best = new Point[count], candidate = new Point[count];
        float minimum = float.MaxValue;
        if (count == 4)
        {
            for (int a = 0; a < lines.Length; a++)
                for (int b = a + 1; b < lines.Length; b++)
                {
                    if (!Intersect(lines[a], lines[b], out candidate[0])) continue;
                    for (int c = b + 1; c < lines.Length; c++)
                    {
                        if (!Intersect(lines[b], lines[c], out candidate[1])) continue;
                        for (int d = c + 1; d < lines.Length; d++)
                            if (Intersect(lines[c], lines[d], out candidate[2]) && Intersect(lines[d], lines[a], out candidate[3]))
                                Consider(candidate, best, ref minimum);
                    }
                }
        }
        else
        {
            uint seed = 12345;
            for (int sample = 0; sample < 100000; sample++)
            {
                int first = (int)(Fraction(ref seed) * lines.Length), previous = first;
                bool valid = true;
                for (int vertex = 0; vertex < 7; vertex++)
                {
                    int start = previous + 1;
                    int next = Math.Min((int)(Fraction(ref seed) * (lines.Length - start)) + start, lines.Length - 1);
                    if (!Intersect(lines[previous], lines[next], out candidate[vertex])) { valid = false; break; }
                    previous = next;
                }
                if (valid && Intersect(lines[previous], lines[first], out candidate[7])) Consider(candidate, best, ref minimum);
            }
        }
        return minimum == float.MaxValue ? [] : best.ToList();
    }

    private static void Consider(Point[] candidate, Point[] best, ref float minimum)
    {
        double area = 0;
        for (int i = 1; i < candidate.Length - 1; i++)
        {
            Point a = candidate[i] - candidate[0], b = candidate[i + 1] - candidate[0];
            area += a.Y * b.X - a.X * b.Y;
        }
        float value = (float)area;
        if (value < minimum)
        {
            minimum = value;
            candidate.CopyTo(best, 0);
        }
    }

    private static bool Intersect(Line a, Line b, out Point point)
    {
        float cross = (float)(a.Direction.X * b.Direction.Y - a.Direction.Y * b.Direction.X);
        point = default;
        if (Math.Abs(cross) < 1e-8f) return false;
        Point delta = a.Position - b.Position;
        float distance = (float)(b.Direction.X * delta.Y - b.Direction.Y * delta.X) / cross;
        if (distance < .5f) return false;
        point = a.Position + a.Direction * distance;
        return point.X >= 0 && point.X <= 1 && point.Y >= 0 && point.Y <= 1;
    }

    private static double Determinant(Point a, Point b, Point c)
    {
        double u1 = b.X - a.X, v1 = b.Y - a.Y;
        double u2 = c.X - a.X, v2 = c.Y - a.Y;
        return u1 * v2 - v1 * u2;
    }

    private static float Fraction(ref uint seed)
    {
        seed = unchecked(seed * 196314165U + 907633515U);
        return BitConverter.Int32BitsToSingle((int)(0x3f800000U | (seed >> 9))) - 1f;
    }
}
