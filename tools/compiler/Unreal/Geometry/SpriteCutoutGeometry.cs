using LumenFx.Compiler.Unreal.TextureSource;
using static LumenFx.Compiler.Unreal.Geometry.SpriteCutoutPolygon;

namespace LumenFx.Compiler.Unreal.Geometry;

internal static class SpriteCutoutGeometry
{
    private static readonly (int X, int Y)[] Neighbors = [(-1, -1), (0, -1), (1, -1), (1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0)];

    internal static Point[][] Build(TextureSourceReader.Image image, int columns, int rows, int vertices,
                                    float threshold, string opacityMode)
    {
        if (columns <= 0 || rows <= 0 || vertices is not (4 or 8))
            throw new ConversionException("Invalid sprite cutout atlas or polygon size");
        var pixels = new SpriteCutoutPixels(image, threshold, opacityMode);
        int frames = checked(columns * rows);
        Point[][] result = new Point[frames][];
        int width = pixels.Width / columns, height = pixels.Height / rows;
        float stepX = pixels.Width / (float)columns;
        int stepY = (int)(pixels.Height / (float)rows);
        for (int frame = 0; frame < frames; frame++)
        {
            if (!pixels.Supported) { result[frame] = Rectangle(vertices); continue; }
            int x = frame % columns, y = frame / columns, next = (frame + 1) % frames;
            int baseX = Round(x * stepX), baseY = Round((float)y * stepY);
            int nextX = Round((next % columns) * stepX), nextY = Round((float)(next / columns) * stepY);
            List<Point> points = [];
            for (int localY = 0; localY < height; localY++)
                for (int localX = 0; localX < width; localX++)
                {
                    bool current = pixels.Opaque(baseX + localX, baseY + localY);
                    bool following = pixels.Opaque(nextX + localX, nextY + localY);
                    if (!current && !following) continue;
                    if (current && NeighborCount(pixels, localX, localY, baseX, baseY, width, height) < 5
                            || following && NeighborCount(pixels, localX, localY, nextX, nextY, width, height) < 5)
                        points.Add(new Point(localX / (float)width, localY / (float)height));
                }
            if (points.Count == 0) { result[frame] = new Point[vertices]; continue; }
            List<Point> hull = Hull(points);
            if (hull.Count < 3) { result[frame] = Rectangle(vertices); continue; }
            List<Point> polygon = Fit(hull, vertices);
            if (polygon.Count == 0) { result[frame] = Rectangle(vertices); continue; }
            while (polygon.Count < vertices) polygon.Add(polygon[^1]);
            result[frame] = polygon.ToArray();
        }
        return result;
    }

    private static int NeighborCount(SpriteCutoutPixels pixels, int x, int y, int baseX, int baseY, int width, int height)
    {
        int count = 0;
        foreach (var offset in Neighbors)
            if (x + offset.X >= 0 && x + offset.X < width && y + offset.Y >= 0 && y + offset.Y < height
                    && pixels.Opaque(baseX + x + offset.X, baseY + y + offset.Y)) count++;
        return count;
    }

    private static int Round(float value) => checked((int)MathF.Floor(value + .5f));

    private static Point[] Rectangle(int vertices)
    {
        Point[] result = new Point[vertices];
        result[1] = new Point(0, 1); result[2] = new Point(1, 1);
        for (int i = 3; i < vertices; i++) result[i] = new Point(1, 0);
        return result;
    }
}
