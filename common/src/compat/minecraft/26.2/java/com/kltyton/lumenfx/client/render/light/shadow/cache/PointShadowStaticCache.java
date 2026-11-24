package com.kltyton.lumenfx.client.render.light.shadow.cache;

import com.kltyton.lumenfx.client.render.light.shadow.PointShadowResolution;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.TerrainShadowScene;
import com.kltyton.lumenfx.client.render.texture.ProjectedShadowDepthTarget;
import com.kltyton.lumenfx.client.render.texture.ShadowDepthTarget;
import com.kltyton.lumenfx.client.render.texture.cube.CubeDepthTarget;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.kltyton.lumenfx.effect.render.light.scene.SpotLightCone;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.jspecify.annotations.Nullable;

/** Stable opaque depths retain native budget, resolution hysteresis and two-second cache lifetime. */
public final class PointShadowStaticCache implements AutoCloseable {
    public record Controls(boolean enabled, int memoryMegabytes, int maximumResolutionUpdates, boolean movingSubjects) {
        public static final Controls DEFAULT = new Controls(true, 150, -1, true);
    }
    public enum Mode { UNCACHED, STATIC_AND_MOVING, MOVING_ONLY }

    private static final class Initializer {
        final Vec3 position;
        final float radius;
        final Matrix4d rotation;
        final @Nullable SpotLightCone cone;
        final float spotBias, spotSlopeBias, nearClipCm;
        Initializer(ScenePointLight light) {
            position = light.data().shader().worldPosition(); radius = light.data().radius();
            rotation = light.data().worldToLight(new Matrix4d()).m30(0).m31(0).m32(0);
            cone = light.data().shader().cone();
            spotBias = cone == null ? 0f : light.data().shadowSettings().bias();
            spotSlopeBias = cone == null ? 0f : light.data().shadowSettings().slopeBias();
            nearClipCm = light.data().shadowSettings().nearClipCm();
        }
        boolean same(Initializer other) {
            if (position.x != other.position.x || position.y != other.position.y || position.z != other.position.z || radius != other.radius) return false;
            if (!Objects.equals(cone, other.cone)) return false;
            if (spotBias != other.spotBias || spotSlopeBias != other.spotSlopeBias || nearClipCm != other.nearClipCm) return false;
            for (int column = 0; column < 4; column++) for (int row = 0; row < 4; row++)
                if (rotation.get(column, row) != other.rotation.get(column, row)) return false;
            return true;
        }
    }
    private static final class Entry {
        Initializer initializer;
        TerrainShadowScene.Frame geometry;
        @Nullable ShadowDepthTarget depth;
        float lastUsed;
        boolean filled;
        Entry(Initializer initializer, TerrainShadowScene.Frame geometry, float time) {
            this.initializer = initializer; this.geometry = geometry; lastUsed = time;
        }
        long bytes() { return depth == null ? 0 : (1L + 4L * depth.viewCount()) * depth.size() * depth.size(); }
        void invalidate() { if (depth != null) depth.close(); depth = null; filled = false; geometry = frame(List.of()); }
    }
    public static final class Selection {
        private final Mode mode;
        private final PointShadowResolution.Plan plan;
        private final TerrainShadowScene.Frame staticCasters, changingCoverage;
        private final boolean movingSubjects;
        private final @Nullable Entry entry;
        private Selection(Mode mode, PointShadowResolution.Plan plan, TerrainShadowScene.Frame staticCasters,
                          TerrainShadowScene.Frame changingCoverage, boolean movingSubjects, @Nullable Entry entry) {
            this.mode = mode; this.plan = plan; this.staticCasters = staticCasters;
            this.changingCoverage = changingCoverage; this.movingSubjects = movingSubjects; this.entry = entry;
        }
        public Mode mode() { return mode; }
        public PointShadowResolution.Plan plan() { return plan; }
        public TerrainShadowScene.Frame staticCasters() { return staticCasters; }
        public TerrainShadowScene.Frame changingCoverage() { return changingCoverage; }
        public boolean movingSubjects() { return movingSubjects; }
        public @Nullable ShadowDepthTarget depth() { return entry == null ? null : entry.depth; }
        public void completeStaticPass() { if (entry != null) entry.filled = true; }
    }

    private final Controls controls;
    private final Map<Long, Entry> entries = new HashMap<>();
    private long frameStartBytes, updates;
    private float realTime;

    public PointShadowStaticCache(Controls controls) { this.controls = controls; }

    public void beginFrame(double time) {
        expire(time); realTime = (float)time; updates = 0; frameStartBytes = 0;
        for (var entry : entries.values()) frameStartBytes += entry.bytes();
    }

    public Selection prepare(ScenePointLight light, PointShadowResolution.Plan plan, TerrainShadowScene.Frame terrain,
                              int maximumResolution) {
        if (!plan.projected()) throw new IllegalArgumentException("Static cache preparation requires a projected shadow");
        if (!controls.enabled()) { remove(light.identity()); return uncached(plan, terrain); }
        var stable = new ArrayList<TerrainShadowScene.Entry>();
        var changing = new ArrayList<TerrainShadowScene.Entry>();
        for (var draw : terrain.entries()) {
            boolean constantCoverage = draw.layer() == ChunkSectionLayer.SOLID && draw.geometryIdentity() != null
                    && !draw.layer().pipeline().getShaderDefines().values().containsKey("ALPHA_CUTOUT");
            (constantCoverage ? stable : changing).add(draw);
        }
        TerrainShadowScene.Frame stableFrame = frame(stable), changingFrame = frame(changing);
        Initializer initializer = new Initializer(light);
        Entry entry = entries.get(light.identity());
        if (entry != null) {
            entry.lastUsed = realTime;
            if (!initializer.same(entry.initializer)) {
                entry.invalidate(); entry.initializer = initializer; entry.geometry = stableFrame;
                return uncached(plan, terrain);
            }
            if (!sameGeometry(stableFrame, entry.geometry)) {
                frameStartBytes -= entry.bytes(); entry.invalidate(); entry.geometry = stableFrame;
            }
            if (entry.depth != null && entry.filled && entry.depth.size() == plan.resolution())
                return cached(Mode.MOVING_ONLY, plan, stableFrame, changingFrame, light, entry);
        }
        if (!(frameStartBytes < (long)controls.memoryMegabytes() * 1024 * 1024)) {
            if (entry != null) entry.invalidate();
            return uncached(plan, terrain);
        }
        updates++;
        if (entry != null && entry.depth != null && entry.filled && entry.depth.size() <= maximumResolution) {
            int existing = entry.depth.size();
            boolean overBudget = updates > Integer.toUnsignedLong(controls.maximumResolutionUpdates());
            boolean rejectShrink = false;
            if (!overBudget && plan.resolution() < existing) {
                float drop = (float)((existing - (double)plan.desiredResolution()) / (existing - (double)plan.resolution()));
                rejectShrink = drop > 0f && drop < .5f;
            }
            if (overBudget || rejectShrink) {
                updates--;
                return cached(Mode.MOVING_ONLY, plan.withResolution(existing), stableFrame, changingFrame, light, entry);
            }
        }
        if (entry == null) {
            entry = new Entry(initializer, stableFrame, realTime);
            entries.put(light.identity(), entry);
        } else entry.invalidate();
        entry.initializer = initializer; entry.geometry = stableFrame;
        if (!stableFrame.entries().isEmpty()) {
            String label = "LumenFX cached local-light static depth " + light.identity();
            entry.depth = initializer.cone == null ? new CubeDepthTarget(label, plan.resolution())
                    : new ProjectedShadowDepthTarget(label, plan.resolution());
        }
        return cached(Mode.STATIC_AND_MOVING, plan, stableFrame, changingFrame, light, entry);
    }

    private Selection cached(Mode mode, PointShadowResolution.Plan plan, TerrainShadowScene.Frame stable,
                             TerrainShadowScene.Frame changing, ScenePointLight light, Entry entry) {
        return new Selection(mode, plan, stable, changing, controls.movingSubjects() || light.data().shadowSettings().forceCachedMoving(), entry);
    }
    private static Selection uncached(PointShadowResolution.Plan plan, TerrainShadowScene.Frame terrain) {
        return new Selection(Mode.UNCACHED, plan, terrain, frame(List.of()), true, null);
    }
    private static TerrainShadowScene.Frame frame(List<TerrainShadowScene.Entry> entries) {
        int maximum = 0;
        for (var draw : entries) if (draw.indices() == null) maximum = Math.max(maximum, draw.indexCount());
        return new TerrainShadowScene.Frame(entries, maximum);
    }
    private static boolean sameGeometry(TerrainShadowScene.Frame current, TerrainShadowScene.Frame previous) {
        if (current.entries().size() != previous.entries().size()) return false;
        for (int i = 0; i < current.entries().size(); i++) {
            var a = current.entries().get(i); var b = previous.entries().get(i);
            if (a.geometryIdentity() != b.geometryIdentity() || a.vertices() != b.vertices() || a.indices() != b.indices()
                    || a.layer() != b.layer() || a.indexType() != b.indexType() || a.firstIndex() != b.firstIndex()
                    || a.indexCount() != b.indexCount() || a.baseVertex() != b.baseVertex()
                    || a.sourceOffset().x != b.sourceOffset().x || a.sourceOffset().y != b.sourceOffset().y || a.sourceOffset().z != b.sourceOffset().z) return false;
        }
        return true;
    }

    public void expire(double time) {
        float now = (float)time;
        for (var entry : entries.values()) if (entry.depth != null && now - entry.lastUsed > 2f) entry.invalidate();
    }
    public void remove(long identity) { Entry entry = entries.remove(identity); if (entry != null) entry.invalidate(); }
    @Override public void close() { entries.values().forEach(Entry::invalidate); entries.clear(); frameStartBytes = updates = 0; }
}
