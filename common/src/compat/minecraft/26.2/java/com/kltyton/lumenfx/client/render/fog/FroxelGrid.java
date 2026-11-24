package com.kltyton.lumenfx.client.render.fog;

/** Source logarithmic Z slices and a lossless two-dimensional layout of their texels. */
public record FroxelGrid(int x, int y, int z, int pixelSize, float depthScale, float depthOffset,
                         float depthDistribution, float maximumU, float maximumV, Atlas atlas) {
    public record Atlas(int columns, int rows, int width, int height) { }

    public static FroxelGrid forView(int width, int height, int requestedPixelSize, int slices,
                                     int maximumVolumeDimension, int maximumAtlasDimension,
                                     float nearCm, float startCm, float farCm, float distribution) {
        if (width < 1 || height < 1 || requestedPixelSize < 1 || slices < 1 || maximumVolumeDimension < 1
                || maximumAtlasDimension < 1 || !Float.isFinite(nearCm) || !Float.isFinite(startCm)
                || !Float.isFinite(farCm) || !Float.isFinite(distribution) || distribution <= 0)
            throw new IllegalArgumentException("Invalid fog grid inputs");
        int pixel = requestedPixelSize;
        int x = ceilDivide(width, pixel), y = ceilDivide(height, pixel);
        if (x > maximumVolumeDimension || y > maximumVolumeDimension) {
            pixel = Math.max(ceilDivide(width, maximumVolumeDimension), ceilDivide(height, maximumVolumeDimension));
            x = ceilDivide(width, pixel);
            y = ceilDivide(height, pixel);
        }
        double near = Math.max(nearCm, startCm) + 9.5;
        double offset = (farCm - near * Math.pow(2.0, slices / (double)distribution)) / (farCm - near);
        double scale = (1.0 - offset) / near;
        int maximumColumns = maximumAtlasDimension / x;
        int maximumRows = maximumAtlasDimension / y;
        if (maximumColumns < 1 || maximumRows < 1 || (long)maximumColumns * maximumRows < slices)
            throw new IllegalArgumentException("Fog volume does not fit the device's atlas extent");
        int minimumColumns = ceilDivide(slices, maximumRows);
        int balancedColumns = (int)Math.ceil(Math.sqrt(slices * (double)y / x));
        int columns = Math.clamp(balancedColumns, minimumColumns, Math.min(maximumColumns, slices));
        int rows = ceilDivide(slices, columns);
        float physicalWidth = x * pixel;
        float physicalHeight = y * pixel;
        int edge = pixel / 2 + 1;
        return new FroxelGrid(x, y, slices, pixel, (float)scale, (float)offset, distribution,
                (physicalWidth - edge) / physicalWidth, (physicalHeight - edge) / physicalHeight,
                new Atlas(columns, rows, Math.multiplyExact(columns, x), Math.multiplyExact(rows, y)));
    }

    private static int ceilDivide(int value, int divisor) {
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }
}
