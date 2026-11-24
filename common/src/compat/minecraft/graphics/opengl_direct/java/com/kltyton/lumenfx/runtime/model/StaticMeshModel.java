package com.kltyton.lumenfx.runtime.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Decodes real 3D mesh assets identically to LumenFX 26.2 {@code StaticMeshAssetLoader} /
 * {@code StaticMeshGeometryLoader} ({@code .lfmesh} v1-3 binary magic {@code 0x484d464c} and
 * {@code .json} mesh manifests) as well as Minecraft JSON block/item models for Photon {@code Model} objects.
 */
public final class StaticMeshModel {
    public record Section(int polygonGroup, String materialSlot, int firstIndex, int indexCount) { }

    private final String id;
    private final float[] positions;  // 3 * vertexCount
    private final float[] normals;    // 3 * vertexCount
    private final float[] uvs;        // 2 * vertexCount
    private final float[] colors;     // 4 * vertexCount
    private final int[] indices;      // 3 * triangleCount
    private final List<Section> sections;
    private final float unitScale;    // 0.01f for UE centimetres (.lfmesh), 1.0f for MC block units

    public StaticMeshModel(String id, float[] positions, float[] normals, float[] uvs,
                           float[] colors, int[] indices, List<Section> sections, float unitScale) {
        this.id = id;
        this.positions = positions;
        this.normals = normals;
        this.uvs = uvs;
        this.colors = colors;
        this.indices = indices;
        this.sections = List.copyOf(sections);
        this.unitScale = unitScale;
    }

    public String id() { return id; }
    public int vertexCount() { return positions.length / 3; }
    public int indexCount() { return indices.length; }
    public float[] positions() { return positions; }
    public float[] normals() { return normals; }
    public float[] uvs() { return uvs; }
    public float[] colors() { return colors; }
    public int[] indices() { return indices; }
    public List<Section> sections() { return sections; }
    public float unitScale() { return unitScale; }

    /** Loads a LumenFX static mesh asset ({@code ns:name} -> {@code ns:lumenfx/meshes/name.json} -> {@code .lfmesh}). */
    public static @Nullable StaticMeshModel loadLumenMesh(String assetId, @Nullable McResourceBridge bridge) {
        if (assetId == null || assetId.isBlank() || bridge == null) return null;
        String jsonPath = toMeshJsonPath(assetId);
        byte[] jsonBytes = bridge.readResource(jsonPath);
        if (jsonBytes != null) {
            try {
                JsonObject root = JsonParser.parseString(new String(jsonBytes, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("lods") && root.get("lods").isJsonArray()) {
                    JsonArray lods = root.getAsJsonArray("lods");
                    if (lods.size() > 0 && lods.get(0).isJsonObject()) {
                        JsonObject lod0 = lods.get(0).getAsJsonObject();
                        if (lod0.has("geometry") && lod0.get("geometry").isJsonPrimitive()) {
                            String geomPath = lod0.get("geometry").getAsString();
                            byte[] lfBytes = bridge.readResource(geomPath);
                            if (lfBytes != null) return decodeLfMesh(assetId, lfBytes);
                        }
                    }
                }
            } catch (RuntimeException | IOException ignored) { }
        }
        String directLf = assetId.endsWith(".lfmesh") ? assetId : toLfMeshPath(assetId);
        byte[] lfBytes = bridge.readResource(directLf);
        if (lfBytes != null) {
            try { return decodeLfMesh(assetId, lfBytes); } catch (IOException ignored) { }
        }
        return null;
    }

    /** Exact decoder for LumenFX binary {@code .lfmesh} (magic {@code 0x484d464c}, versions 1-3). */
    public static StaticMeshModel decodeLfMesh(String id, byte[] raw) throws IOException {
        ByteBuffer data = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (data.remaining() < 24 || data.getInt() != 0x484d464c) {
            throw new IOException("Unsupported .lfmesh header: " + id);
        }
        int version = data.getInt();
        if (version < 1 || version > 3) throw new IOException("Unsupported .lfmesh version " + version + ": " + id);
        if (version >= 2) {
            if (data.remaining() < 20) throw new IOException("Truncated .lfmesh v2+ header: " + id);
            data.getInt(); // vertexIdKind
        }
        int vertices = data.getInt();
        int indexCount = data.getInt();
        int uvCount = data.getInt();
        int sectionCount = data.getInt();
        if (vertices < 0 || indexCount < 0 || indexCount % 3 != 0 || uvCount < 0 || uvCount > 128 || sectionCount < 0) {
            throw new IOException("Invalid .lfmesh counts: " + id);
        }
        int[] uvWidths = new int[uvCount];
        if (version >= 3) {
            for (int c = 0; c < uvCount; c++) uvWidths[c] = data.getInt();
        } else {
            Arrays.fill(uvWidths, 2);
        }
        readInts(data, vertices); // instanceIds
        float[] positions = readFloats(data, vertices * 3);
        float[] normals = readFloats(data, vertices * 3);
        readFloats(data, vertices * 3); // tangents
        readFloats(data, vertices);     // binormalSigns
        float[] colors = readFloats(data, vertices * 4);
        float[] uv0 = new float[vertices * 2];
        for (int c = 0; c < uvCount; c++) {
            float[] ch = readFloats(data, vertices * uvWidths[c]);
            if (c == 0) {
                int w = uvWidths[c];
                for (int v = 0; v < vertices; v++) {
                    uv0[v * 2] = ch[v * w];
                    uv0[v * 2 + 1] = ch[v * w + 1];
                }
            }
        }
        int[] indices = readInts(data, indexCount);
        List<Section> sections = new ArrayList<>(sectionCount);
        for (int s = 0; s < sectionCount; s++) {
            int group = data.getInt();
            int nameBytes = data.getInt();
            byte[] nb = new byte[Math.max(0, nameBytes)];
            data.get(nb);
            String slot = new String(nb, StandardCharsets.UTF_8);
            int first = data.getInt();
            int count = data.getInt();
            sections.add(new Section(group, slot, first, count));
        }
        // UE static meshes are authored in centimetres (100 cm = 1 block); normalize to blocks if large
        float maxCoord = 0f;
        for (float v : positions) maxCoord = Math.max(maxCoord, Math.abs(v));
        float unitScale = maxCoord > 10.0f ? 0.01f : 1.0f;
        return new StaticMeshModel(id, positions, normals, uv0, colors, indices, sections, unitScale);
    }

    /**
     * Loads a Minecraft JSON block/item model (e.g. {@code minecraft:block/sea_lantern}) into a real 3D triangle mesh
     * by resolving {@code elements[]} or standard cube parents ({@code block/cube_all}, {@code block/cube}).
     */
    public static StaticMeshModel loadMinecraftJsonModel(String modelLocation, @Nullable McResourceBridge bridge) {
        String id = modelLocation.contains(":") ? modelLocation : ("minecraft:" + modelLocation);
        if (bridge != null) {
            int colon = id.indexOf(':');
            String resPath = id.substring(0, colon) + ":models/" + id.substring(colon + 1) + ".json";
            byte[] bytes = bridge.readResource(resPath);
            if (bytes != null) {
                try {
                    JsonObject obj = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (obj.has("elements") && obj.get("elements").isJsonArray()) {
                        StaticMeshModel fromElements = buildFromMinecraftElements(id, obj.getAsJsonArray("elements"));
                        if (fromElements != null && fromElements.indexCount() > 0) return fromElements;
                    }
                } catch (RuntimeException ignored) { }
            }
        }
        return unitTexturedCube(id);
    }

    private static @Nullable StaticMeshModel buildFromMinecraftElements(String id, JsonArray elements) {
        List<Float> pos = new ArrayList<>();
        List<Float> norm = new ArrayList<>();
        List<Float> uv = new ArrayList<>();
        List<Float> col = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        for (JsonElement el : elements) {
            if (!el.isJsonObject()) continue;
            JsonObject box = el.getAsJsonObject();
            if (!box.has("from") || !box.has("to")) continue;
            JsonArray f = box.getAsJsonArray("from");
            JsonArray t = box.getAsJsonArray("to");
            float x0 = f.get(0).getAsFloat() / 16f - 0.5f;
            float y0 = f.get(1).getAsFloat() / 16f - 0.5f;
            float z0 = f.get(2).getAsFloat() / 16f - 0.5f;
            float x1 = t.get(0).getAsFloat() / 16f - 0.5f;
            float y1 = t.get(1).getAsFloat() / 16f - 0.5f;
            float z1 = t.get(2).getAsFloat() / 16f - 0.5f;
            appendBoxFaces(pos, norm, uv, col, idx, x0, y0, z0, x1, y1, z1);
        }
        if (idx.isEmpty()) return null;
        return new StaticMeshModel(id, toFloatArray(pos), toFloatArray(norm), toFloatArray(uv),
                toFloatArray(col), toIntArray(idx), List.of(new Section(0, "default", 0, idx.size())), 1.0f);
    }

    /** Standard 24-vertex, 36-index UV-mapped unit cube (matching {@code minecraft:block/cube_all}). */
    public static StaticMeshModel unitTexturedCube(String id) {
        List<Float> pos = new ArrayList<>(72);
        List<Float> norm = new ArrayList<>(72);
        List<Float> uv = new ArrayList<>(48);
        List<Float> col = new ArrayList<>(96);
        List<Integer> idx = new ArrayList<>(36);
        appendBoxFaces(pos, norm, uv, col, idx, -0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f);
        return new StaticMeshModel(id, toFloatArray(pos), toFloatArray(norm), toFloatArray(uv),
                toFloatArray(col), toIntArray(idx), List.of(new Section(0, "default", 0, idx.size())), 1.0f);
    }

    private static void appendBoxFaces(List<Float> pos, List<Float> norm, List<Float> uv, List<Float> col,
                                       List<Integer> idx, float x0, float y0, float z0, float x1, float y1, float z1) {
        // +Z (South)
        appendQuad(pos, norm, uv, col, idx, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1);
        // -Z (North)
        appendQuad(pos, norm, uv, col, idx, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1);
        // +X (East)
        appendQuad(pos, norm, uv, col, idx, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0);
        // -X (West)
        appendQuad(pos, norm, uv, col, idx, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0);
        // +Y (Up)
        appendQuad(pos, norm, uv, col, idx, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0);
        // -Y (Down)
        appendQuad(pos, norm, uv, col, idx, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0);
    }

    private static void appendQuad(List<Float> pos, List<Float> norm, List<Float> uv, List<Float> col, List<Integer> idx,
                                   float x0, float y0, float z0, float x1, float y1, float z1,
                                   float x2, float y2, float z2, float x3, float y3, float z3,
                                   float nx, float ny, float nz) {
        int base = pos.size() / 3;
        float[][] pts = {{x0, y0, z0, 0, 1}, {x1, y1, z1, 1, 1}, {x2, y2, z2, 1, 0}, {x3, y3, z3, 0, 0}};
        for (float[] p : pts) {
            pos.add(p[0]); pos.add(p[1]); pos.add(p[2]);
            norm.add(nx); norm.add(ny); norm.add(nz);
            uv.add(p[3]); uv.add(p[4]);
            col.add(1f); col.add(1f); col.add(1f); col.add(1f);
        }
        idx.add(base); idx.add(base + 1); idx.add(base + 2);
        idx.add(base); idx.add(base + 2); idx.add(base + 3);
    }

    private static String toMeshJsonPath(String assetId) {
        String id = assetId.contains(":") ? assetId : ("lumenfx:" + assetId);
        int c = id.indexOf(':');
        String ns = id.substring(0, c);
        String path = id.substring(c + 1);
        if (!path.startsWith("lumenfx/meshes/")) path = "lumenfx/meshes/" + path;
        if (!path.endsWith(".json")) path = path + ".json";
        return ns + ":" + path;
    }

    private static String toLfMeshPath(String assetId) {
        String id = assetId.contains(":") ? assetId : ("lumenfx:" + assetId);
        int c = id.indexOf(':');
        String ns = id.substring(0, c);
        String path = id.substring(c + 1);
        if (!path.startsWith("lumenfx/meshes/")) path = "lumenfx/meshes/" + path;
        if (!path.endsWith(".lfmesh")) path = path + ".lfmesh";
        return ns + ":" + path;
    }

    private static float[] readFloats(ByteBuffer b, int count) {
        float[] out = new float[count];
        b.asFloatBuffer().get(out);
        b.position(b.position() + count * Float.BYTES);
        return out;
    }

    private static int[] readInts(ByteBuffer b, int count) {
        int[] out = new int[count];
        b.asIntBuffer().get(out);
        b.position(b.position() + count * Integer.BYTES);
        return out;
    }

    private static float[] toFloatArray(List<Float> l) {
        float[] a = new float[l.size()];
        for (int i = 0; i < l.size(); i++) a[i] = l.get(i);
        return a;
    }

    private static int[] toIntArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < l.size(); i++) a[i] = l.get(i);
        return a;
    }
}
