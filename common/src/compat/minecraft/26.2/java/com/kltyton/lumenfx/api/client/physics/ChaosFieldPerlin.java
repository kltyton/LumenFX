package com.kltyton.lumenfx.api.client.physics;

/** Ken Perlin's improved noise lattice and interpolation, as used by the CPU Chaos field. */
final class ChaosFieldPerlin {
    private static final int[] PERMUTATION = {
            151, 160, 137, 91, 90, 15, 131, 13, 201, 95, 96, 53, 194, 233, 7, 225,
            140, 36, 103, 30, 69, 142, 8, 99, 37, 240, 21, 10, 23, 190, 6, 148,
            247, 120, 234, 75, 0, 26, 197, 62, 94, 252, 219, 203, 117, 35, 11, 32,
            57, 177, 33, 88, 237, 149, 56, 87, 174, 20, 125, 136, 171, 168, 68, 175,
            74, 165, 71, 134, 139, 48, 27, 166, 77, 146, 158, 231, 83, 111, 229, 122,
            60, 211, 133, 230, 220, 105, 92, 41, 55, 46, 245, 40, 244, 102, 143, 54,
            65, 25, 63, 161, 1, 216, 80, 73, 209, 76, 132, 187, 208, 89, 18, 169,
            200, 196, 135, 130, 116, 188, 159, 86, 164, 100, 109, 198, 173, 186, 3, 64,
            52, 217, 226, 250, 124, 123, 5, 202, 38, 147, 118, 126, 255, 82, 85, 212,
            207, 206, 59, 227, 47, 16, 58, 17, 182, 189, 28, 42, 223, 183, 170, 213,
            119, 248, 152, 2, 44, 154, 163, 70, 221, 153, 101, 155, 167, 43, 172, 9,
            129, 22, 39, 253, 19, 98, 108, 110, 79, 113, 224, 232, 178, 185, 112, 104,
            218, 246, 97, 228, 251, 34, 242, 193, 238, 210, 144, 12, 191, 179, 162, 241,
            81, 51, 145, 235, 249, 14, 239, 107, 49, 192, 214, 31, 181, 199, 106, 157,
            184, 84, 204, 176, 115, 121, 50, 45, 127, 4, 150, 254, 138, 236, 205, 93,
            222, 114, 67, 29, 24, 72, 243, 141, 128, 195, 78, 66, 215, 61, 156, 180
    };

    private ChaosFieldPerlin() { }

    static float sample(float x, float y, float z) {
        float lowerX = (float)Math.floor(x), lowerY = (float)Math.floor(y), lowerZ = (float)Math.floor(z);
        int cellX = (int)lowerX & 255, cellY = (int)lowerY & 255, cellZ = (int)lowerZ & 255;
        x -= lowerX;
        y -= lowerY;
        z -= lowerZ;
        int a = hash(cellX) + cellY, b = hash(cellX + 1) + cellY;
        int a0 = hash(a) + cellZ, a1 = hash(a + 1) + cellZ;
        int b0 = hash(b) + cellZ, b1 = hash(b + 1) + cellZ;
        float wx = fade(x), wy = fade(y), wz = fade(z);
        float near = plane(x, y, z, wx, wy, a0, a1, b0, b1);
        float far = plane(x, y, z - 1, wx, wy, a0 + 1, a1 + 1, b0 + 1, b1 + 1);
        return mix(near, far, wz);
    }

    private static float plane(float x, float y, float z, float wx, float wy, int a0, int a1, int b0, int b1) {
        float lower = mix(gradient(a0, x, y, z), gradient(b0, x - 1, y, z), wx);
        float upper = mix(gradient(a1, x, y - 1, z), gradient(b1, x - 1, y - 1, z), wx);
        return mix(lower, upper, wy);
    }

    private static int hash(int index) { return PERMUTATION[index & 255]; }
    private static float fade(float x) { return x * x * x * (x * (x * 6 - 15) + 10); }
    private static float mix(float a, float b, float alpha) { return a + alpha * (b - a); }

    private static float gradient(int index, float x, float y, float z) {
        int direction = hash(index) & 15;
        float first = direction < 8 ? x : y;
        float second = direction < 4 ? y : direction == 12 || direction == 14 ? x : z;
        return ((direction & 1) == 0 ? first : -first) + ((direction & 2) == 0 ? second : -second);
    }
}
