package com.kltyton.lumenfx.client.render.motion;

import com.kltyton.lumenfx.client.render.motion.mixin.StagedVertexBufferDrawAccessor;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * CPU motion-owner / animated-vertex history.
 *
 * <p>Owners are recorded on render states; submissions receive their stable key at creation; each
 * native per-submit renderer method activates the exact submit scope, and the real
 * {@link StagedVertexBuffer.Draw} consumer records ordered runs.  This module never edits MeshData,
 * render formats or GPU pipelines.</p>
 */
public final class MotionHistory {
    public static final MotionHistory INSTANCE = new MotionHistory();
    public static final ContextKey<OwnerKey> OWNER_KEY =
            new ContextKey<>(Identifier.fromNamespaceAndPath("lumenfx", "motion_owner"));

    private static final int INITIAL_CAPACITY_FLOATS = 96;
    private static final int MAX_POOLED_BUFFERS = 192;
    private static final int MAX_POOLED_ARRAY_FLOATS = 1 << 16;

    private final ThreadLocal<ArrayList<OwnerKey>> ownerScopes = ThreadLocal.withInitial(ArrayList::new);
    private final ArrayDeque<SubmissionHistory> submitScopes = new ArrayDeque<>();
    private final IdentityHashMap<Object, SubmitKey> submitKeys = new IdentityHashMap<>();
    private final IdentityHashMap<EntityRenderState, OwnerKey> entityStateOwners = new IdentityHashMap<>();
    private final IdentityHashMap<BlockEntityRenderState, OwnerKey> blockStateOwners = new IdentityHashMap<>();
    private final Map<OwnerKey, Map<String, Integer>> occurrences = new HashMap<>();
    private final IdentityHashMap<RenderType, String> baseSignatures = new IdentityHashMap<>();
    private final Map<SubmitKey, SubmissionHistory> histories = new LinkedHashMap<>();
    private final Set<SubmitKey> visited = new HashSet<>();
    private final Map<StagedVertexBuffer.Draw, DrawCapture> drawCaptures = new LinkedHashMap<>();
    private final IdentityHashMap<StagedVertexBuffer.ExecuteInfo, StagedVertexBuffer.Draw> executionDraws = new IdentityHashMap<>();
    private final Map<StagedVertexBuffer.Draw, PreparedIdentity> preparedByDraw = new LinkedHashMap<>();
    private final Set<StagedVertexBuffer.Draw> preparedMismatch = new HashSet<>();
    private final ArrayDeque<FloatHistoryBuffer> bufferPool = new ArrayDeque<>();

    private volatile boolean enabled;
    private volatile boolean frameOpen;
    private volatile long frameId;
    private long committedFrameId = -1;
    private volatile long publishedFrameId = -1L;
    private volatile @Nullable Object worldIdentity;
    private volatile @Nullable Vec3 cameraWorld;
    private volatile @Nullable Vec3 lastCameraWorld;
    private volatile MotionSnapshot published = MotionSnapshot.empty();

    private boolean levelSubmissionScope;
    private boolean levelPrepareScope;
    private @Nullable Object levelStorage;

    private MotionHistory() {
    }

    public void beginFrame(boolean requestMotion, @Nullable Object worldIdentity) {
        boolean worldChanged = this.worldIdentity != worldIdentity;
        this.frameId++;
        this.publishedFrameId = -1;
        this.published = MotionSnapshot.empty();
        if (worldChanged) {
            clearHistories();
            committedFrameId = -1;
            SourceVertexIdentity.clear();
            this.bufferPool.clear();
            this.lastCameraWorld = null;
            this.publishedFrameId = this.frameId;
            this.published = MotionSnapshot.empty();
        }

        this.worldIdentity = worldIdentity;
        this.enabled = requestMotion;
        this.frameOpen = true;
        this.cameraWorld = null;
        this.levelSubmissionScope = false;
        this.levelPrepareScope = false;
        this.levelStorage = null;
        this.sessionsClear();
        for (SubmissionHistory history : this.histories.values()) {
            history.captureStarted = false;
            history.currentRuns.clear();
        }

        if (!requestMotion) {
            clearHistories();
            committedFrameId = -1;
            this.lastCameraWorld = null;
            this.publishedFrameId = this.frameId;
            this.published = MotionSnapshot.empty();
        }
    }

    public void cameraPosition(@Nullable Vec3 actualCurrentCameraWorld) {
        this.cameraWorld = actualCurrentCameraWorld;
    }

    public void endFrame() {
        if (!this.frameOpen) {
            return;
        }
        try {
            if (!this.enabled) {
                this.lastCameraWorld = null;
                this.publishedFrameId = this.frameId;
                this.published = MotionSnapshot.empty();
                return;
            }

            Map<OwnerKey, List<SubmissionMotion>> grouped = new LinkedHashMap<>();
            Set<SubmitKey> kept = new HashSet<>();
            for (Map.Entry<SubmitKey, SubmissionHistory> entry : this.histories.entrySet()) {
                SubmissionHistory history = entry.getValue();
                if (!this.visited.contains(entry.getKey())) {
                    continue;
                }
                FloatHistoryBuffer current = history.writeSlot();
                if (current.count == 0) {
                    continue;
                }
                FloatHistoryBuffer previous = history.previousSlot();
                history.currentRuns.sort(Comparator.comparingInt(run -> run.logicalOffset));
                boolean previousPresent = previous.frame == this.committedFrameId && previous.count > 0;
                SubmissionMotion.Status status = this.status(history, current, previous, previousPresent);
                current.frame = this.frameId;

                List<RunMotion> runs = freeze(history.currentRuns);
                List<RunMotion> previousRuns = freeze(history.previousRuns);
                grouped.computeIfAbsent(entry.getKey().owner(), key -> new ArrayList<>())
                        .add(new SubmissionMotion(
                                entry.getKey(),
                                current.data,
                                current.count,
                                previous.data,
                                previous.count,
                                previousPresent,
                                status == SubmissionMotion.Status.VALID,
                                status,
                                runs,
                                previousRuns));
                history.rotate();
                kept.add(entry.getKey());
            }

            List<OwnerMotion> owners = new ArrayList<>(grouped.size());
            for (Map.Entry<OwnerKey, List<SubmissionMotion>> entry : grouped.entrySet()) {
                owners.add(new OwnerMotion(entry.getKey(), List.copyOf(entry.getValue())));
            }

            Vec3 currentCamera = this.cameraWorld;
            Vec3 previousCamera = this.lastCameraWorld;
            List<DrawMotion> draws = this.freezeDraws();
            this.publishedFrameId = this.frameId;
            this.published = new MotionSnapshot(
                    this.frameId, this.worldIdentity, currentCamera, previousCamera, List.copyOf(owners), draws);
            this.lastCameraWorld = currentCamera;
            this.committedFrameId = this.frameId;

            for (SubmitKey key : kept) {
                SubmissionHistory history = this.histories.get(key);
                if (history != null) {
                    history.previousRuns = List.copyOf(history.currentRuns);
                    history.currentRuns.clear();
                    history.captureStarted = false;
                }
            }

            Iterator<Map.Entry<SubmitKey, SubmissionHistory>> iterator = this.histories.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<SubmitKey, SubmissionHistory> entry = iterator.next();
                if (!kept.contains(entry.getKey())) {
                    releaseHistory(entry.getValue());
                    iterator.remove();
                }
            }
        } finally {
            this.frameOpen = false;
            this.levelSubmissionScope = false;
            this.levelPrepareScope = false;
            this.levelStorage = null;
            this.sessionsClear();
        }
    }

    public void holdFrame() {
        if (!this.frameOpen) return;
        for (SubmissionHistory history : this.histories.values()) {
            history.currentRuns.clear();
            history.captureStarted = false;
            history.writeSlot().reset(INITIAL_CAPACITY_FLOATS);
        }
        this.frameOpen = false;
        this.levelSubmissionScope = false;
        this.levelPrepareScope = false;
        this.levelStorage = null;
        this.sessionsClear();
    }

    public void reset() {
        this.enabled = false;
        this.frameOpen = false;
        this.worldIdentity = null;
        this.cameraWorld = null;
        this.lastCameraWorld = null;
        this.levelSubmissionScope = false;
        this.levelPrepareScope = false;
        this.levelStorage = null;
        this.frameId++;
        this.committedFrameId = -1;
        this.publishedFrameId = this.frameId;
        this.published = MotionSnapshot.empty();
        this.sessionsClear();
        clearHistories();
        SourceVertexIdentity.clear();
        this.bufferPool.clear();
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public boolean isCapturingVertices() {
        return RenderSystem.isOnRenderThread() && this.enabled && this.frameOpen
                && this.levelPrepareScope && !this.submitScopes.isEmpty();
    }

    public long frameId() {
        return this.frameId;
    }

    public @Nullable Vec3 cameraWorld() {
        return this.cameraWorld;
    }

    public MotionSnapshot snapshot() {
        return this.published;
    }

    boolean isPublished(MotionSnapshot snapshot) {
        MotionSnapshot current = this.published;
        return current == snapshot && snapshot.frameId() >= 0L && current.frameId() == this.publishedFrameId;
    }

    // ---------------------------------------------------------------------------------------------
    // Owner scopes
    // ---------------------------------------------------------------------------------------------

    public void tagRenderState(@Nullable Entity entity, @Nullable EntityRenderState state) {
        if (!this.enabled || entity == null || state == null) {
            return;
        }
        OwnerKey owner = this.ownerOf(entity);
        if (owner == null) {
            return;
        }
        com.kltyton.lumenfx.client.platform.LumenClientPlatform.setMotionOwner(state, owner);
        this.entityStateOwners.put(state, owner);
    }

    public @Nullable OwnerKey ownerForState(@Nullable EntityRenderState state) {
        if (state == null) {
            return null;
        }
        OwnerKey owner = com.kltyton.lumenfx.client.platform.LumenClientPlatform.getMotionOwner(state);
        return owner != null ? owner : this.entityStateOwners.get(state);
    }

    public void tagBlockEntityState(@Nullable BlockEntity blockEntity, @Nullable BlockEntityRenderState state) {
        if (!this.enabled || blockEntity == null || state == null || state.blockEntityType == null || state.blockPos == null) {
            return;
        }
        Object world = this.worldIdentity;
        if (world == null && blockEntity != null) {
            world = blockEntity.getLevel();
        }
        if (world == null) {
            return;
        }
        this.blockStateOwners.put(state, OwnerKey.block(world, state.blockEntityType, state.blockPos, blockEntity));
    }

    public @Nullable OwnerKey ownerForBlockState(@Nullable BlockEntityRenderState state) {
        if (state == null || state.blockEntityType == null || state.blockPos == null) {
            return null;
        }
        return this.blockStateOwners.get(state);
    }

    public void pushOwner(@Nullable OwnerKey owner) {
        this.ownerScopes.get().add(owner);
    }

    public void popOwner() {
        ArrayList<OwnerKey> stack = this.ownerScopes.get();
        if (!stack.isEmpty()) {
            stack.remove(stack.size() - 1);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Level / hand-screen separation
    // ---------------------------------------------------------------------------------------------

    public void beginLevelSubmission(Object storage) {
        this.levelStorage = storage;
        this.levelSubmissionScope = true;
    }

    public void endLevelSubmission() {
        this.levelSubmissionScope = false;
    }

    public boolean isLevelStorage(Object storage) {
        return this.levelStorage != null && this.levelStorage == storage;
    }

    public void beginPrepare(boolean level) {
        this.levelPrepareScope = level;
        if (!level) {
            this.submitScopes.clear();
        }
    }

    public void endPrepare() {
        this.levelPrepareScope = false;
        this.submitScopes.clear();
    }

    // ---------------------------------------------------------------------------------------------
    // Submission creation metadata
    // ---------------------------------------------------------------------------------------------

    public void attachModelSubmit(
            Object submit, @Nullable RenderType renderType, @Nullable String textureKey, String modelClass) {
        if (renderType != null) {
            this.attach(submit, SubmitKind.MODEL, this.renderSignature(renderType, textureKey) + '|' + modelClass);
        }
    }

    public void attachCustomSubmit(Object submit, @Nullable RenderType renderType) {
        if (renderType != null) {
            this.attach(submit, SubmitKind.CUSTOM, this.renderSignature(renderType, null));
        }
    }

    public void attachBlockModelSubmit(Object submit, @Nullable RenderType renderType, int partCount) {
        if (renderType != null) {
            this.attach(submit, SubmitKind.BLOCK_MODEL, this.renderSignature(renderType, null) + "|" + partCount);
        }
    }

    public void attachItemSubmit(
            Object submit,
            ItemDisplayContext context,
            List<BakedQuad> quads,
            ItemStackRenderState.FoilType foil,
            int outlineColor) {
        StringBuilder signature = new StringBuilder("item|")
                .append(context.name())
                .append('|')
                .append(quads.size())
                .append('|')
                .append(foil.name())
                .append('|')
                .append(outlineColor);
        for (BakedQuad quad : quads) {
            RenderType type = quad.materialInfo().itemRenderType();
            signature.append('|').append(type == null ? "null" : this.renderSignature(type, null));
        }
        this.attach(submit, SubmitKind.ITEM, signature.toString());
    }

    public void attachMovingBlockSubmit(Object submit, @Nullable MovingBlockRenderState state, int outlineColor) {
        if (state == null || state.blockState == null) {
            return;
        }
        this.attach(
                submit,
                SubmitKind.MOVING_BLOCK,
                "moving_block|" + state.blockState.getBlock().getDescriptionId() + '|' + outlineColor);
    }

    // ---------------------------------------------------------------------------------------------
    // Prepare-phase submit scope and Draw capture
    // ---------------------------------------------------------------------------------------------

    public boolean beginSubmit(Object submit) {
        if (!this.enabled || !this.levelPrepareScope || submit == null) {
            return false;
        }
        SubmitKey key = this.submitKeys.get(submit);
        if (key == null) {
            return false;
        }
        SubmissionHistory history = this.histories.get(key);
        if (history == null) {
            history = new SubmissionHistory(key, acquireBuffer(), acquireBuffer(), acquireBuffer(), this.frameId);
            this.histories.put(key, history);
        }
        if (!history.captureStarted) {
            history.writeSlot().reset(INITIAL_CAPACITY_FLOATS);
            history.currentRuns.clear();
            history.logicalCount = 0;
            history.vertexOccurrences.clear();
            history.captureStarted = true;
            this.visited.add(key);
        }
        this.submitScopes.push(history);
        return true;
    }

    public void endSubmit() {
        if (!this.submitScopes.isEmpty()) {
            this.submitScopes.pop();
        }
    }

    public void bindDrawPrepared(StagedVertexBuffer.Draw draw, PreparedRenderType prepared) {
        if (!this.enabled || !this.levelPrepareScope || draw == null || prepared == null) {
            return;
        }
        PreparedIdentity identity = new PreparedIdentity(prepared);
        PreparedIdentity existing = this.preparedByDraw.putIfAbsent(draw, identity);
        if (existing != null && !existing.equals(identity)) {
            this.preparedMismatch.add(draw);
        }
    }

    public @Nullable VertexConsumer wrapDrawConsumer(StagedVertexBuffer.Draw draw, VertexConsumer original) {
        if (!this.enabled || !this.levelPrepareScope || draw == null || original == null) {
            return null;
        }
        DrawCapture capture = this.drawCaptures.computeIfAbsent(draw, DrawCapture::new);
        if (capture.prepared == null) {
            capture.prepared = this.preparedByDraw.get(draw);
        }
        capture.preparedMismatch = this.preparedMismatch.contains(draw);

        PrimitiveTopology topology = ((StagedVertexBufferDrawAccessor) (Object) draw).lumenfx$primitiveTopology();
        SubmissionHistory submit = this.submitScopes.peek();
        if (submit == null)
            return new MotionVertexConsumer(original, null, null, null, capture, topology == PrimitiveTopology.LINES);
        RunRecord run = new RunRecord(
                submit.key, draw, capture.prepared, submit.logicalCount, submit.writeSlot().count / 3,
                capture.capturedGpuCount, topology);
        submit.currentRuns.add(run);
        capture.runs.add(run);
        return new MotionVertexConsumer(
                original,
                submit.writeSlot(),
                submit,
                run,
                capture,
                topology == PrimitiveTopology.LINES);
    }

    public void validateDrawCounts() {
        if (!this.enabled || !this.levelPrepareScope) {
            return;
        }
        for (DrawCapture capture : this.drawCaptures.values()) {
            if (capture.runs.isEmpty()) {
                continue;
            }
            int actual = ((StagedVertexBufferDrawAccessor) (Object) capture.draw).lumenfx$vertexCount();
            capture.actualVertexCount = actual;
            capture.runs.sort(Comparator.comparingInt(run -> run.gpuVertexOffset));
            capture.validated = true;
            capture.preparedMismatch = capture.preparedMismatch || this.preparedMismatch.contains(capture.draw);
            capture.countValid = !capture.preparedMismatch && capture.capturedGpuCount == actual;
        }
    }

    public void recordExecuteInfo(StagedVertexBuffer.Draw draw, StagedVertexBuffer.@Nullable ExecuteInfo info) {
        if (!this.enabled || draw == null) {
            return;
        }
        DrawCapture capture = this.drawCaptures.get(draw);
        if (capture != null) {
            capture.execution = info;
            if (info != null) this.executionDraws.put(info, draw);
        }
    }

    public List<DrawMotion> preparedDraws() { return this.freezeDraws(); }

    public StagedVertexBuffer.@Nullable Draw drawFor(StagedVertexBuffer.ExecuteInfo execution) {
        return this.executionDraws.get(execution);
    }

    /** Writes the one-based clip-atlas lookup in the current Draw's unchanged vertex order. */
    public void writePreviousVertexMap(StagedVertexBuffer.Draw draw, IntBuffer destination) {
        DrawCapture capture = this.drawCaptures.get(draw);
        int count = ((StagedVertexBufferDrawAccessor)(Object)draw).lumenfx$vertexCount();
        if (destination.remaining() < count) throw new IllegalArgumentException("Insufficient motion vertex-map capacity");
        int start = destination.position();
        for (int vertex = 0; vertex < count; vertex++) destination.put(0);
        if (capture == null || !capture.validated || !capture.countValid) return;
        for (RunRecord run : capture.runs) {
            SubmissionHistory history = this.histories.get(run.key);
            history.preparePreviousClipIndex(this.frameId, this.committedFrameId);
            FloatHistoryBuffer current = history.writeSlot();
            for (int vertex = 0; vertex < run.gpuVertexCount; vertex++) {
                long identity = current.vertexIds[run.historyVertexOffset + vertex];
                if (identity != 0) destination.put(start + run.gpuVertexOffset + vertex, history.previousClipByVertex.get(identity));
            }
        }
    }

    /** Called only after the original draw's point capture has been submitted. */
    public void recordCapturedDraw(StagedVertexBuffer.Draw draw, int firstAtlasTexel) {
        DrawCapture capture = this.drawCaptures.get(draw);
        if (capture == null || !capture.validated || !capture.countValid)
            throw new IllegalStateException("Cannot publish unvalidated vertex clip capture");
        for (RunRecord run : capture.runs) {
            FloatHistoryBuffer current = this.histories.get(run.key).writeSlot();
            for (int vertex = 0; vertex < run.gpuVertexCount; vertex++)
                current.clipTexels[run.historyVertexOffset + vertex] = firstAtlasTexel + run.gpuVertexOffset + vertex + 1;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private SubmissionMotion.Status status(
            SubmissionHistory history,
            FloatHistoryBuffer current,
            FloatHistoryBuffer previous,
            boolean previousPresent) {
        if (history.firstFrame == this.frameId) {
            return SubmissionMotion.Status.NEW_SUBMISSION;
        }
        if (!previousPresent) {
            return SubmissionMotion.Status.MISSING_PREVIOUS;
        }
        if (previous.count != current.count) {
            return SubmissionMotion.Status.TOPOLOGY_COUNT_MISMATCH;
        }
        if (history.currentRuns.isEmpty()) {
            return SubmissionMotion.Status.DRAW_COUNT_UNVERIFIED;
        }

        boolean unverified = false;
        boolean drawMismatch = false;
        boolean identityMismatch = false;
        for (RunRecord run : history.currentRuns) {
            DrawCapture capture = this.drawCaptures.get(run.draw);
            if (capture == null || !capture.validated) {
                unverified = true;
                continue;
            }
            if (!capture.countValid) {
                drawMismatch = true;
            }
            if (capture.prepared == null || capture.preparedMismatch) {
                identityMismatch = true;
            }
        }
        if (identityMismatch) {
            return SubmissionMotion.Status.DRAW_IDENTITY_MISMATCH;
        }
        if (drawMismatch) {
            return SubmissionMotion.Status.DRAW_COUNT_MISMATCH;
        }
        if (unverified) {
            return SubmissionMotion.Status.DRAW_COUNT_UNVERIFIED;
        }
        if (history.currentRuns.size() != history.previousRuns.size()) {
            return SubmissionMotion.Status.RUN_STRUCTURE_MISMATCH;
        }
        if (!samePrepared(history.currentRuns, history.previousRuns)) {
            return SubmissionMotion.Status.DRAW_IDENTITY_MISMATCH;
        }
        if (!sameRunCounts(history.currentRuns, history.previousRuns)) {
            return SubmissionMotion.Status.RUN_STRUCTURE_MISMATCH;
        }
        for (int vertex = 0; vertex < current.count / 3; vertex++) {
            if (current.vertexIds[vertex] == 0 || current.vertexIds[vertex] != previous.vertexIds[vertex])
                return SubmissionMotion.Status.TOPOLOGY_UNRESOLVED;
        }
        return SubmissionMotion.Status.VALID;
    }

    private static boolean samePrepared(List<RunRecord> current, List<RunRecord> previous) {
        if (current.size() != previous.size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            RunRecord a = current.get(i);
            RunRecord b = previous.get(i);
            if (!Objects.equals(a.prepared, b.prepared) || a.primitiveTopology != b.primitiveTopology) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameRunCounts(List<RunRecord> current, List<RunRecord> previous) {
        if (current.size() != previous.size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            RunRecord a = current.get(i);
            RunRecord b = previous.get(i);
            if (a.logicalOffset != b.logicalOffset
                    || a.logicalVertexCount != b.logicalVertexCount
                    || a.gpuVertexCount != b.gpuVertexCount) {
                return false;
            }
        }
        return true;
    }

    private List<DrawMotion> freezeDraws() {
        List<DrawMotion> draws = new ArrayList<>(this.drawCaptures.size());
        for (DrawCapture capture : this.drawCaptures.values()) {
            if (capture.runs.isEmpty()) {
                continue;
            }
            StagedVertexBufferDrawAccessor accessor = (StagedVertexBufferDrawAccessor) (Object) capture.draw;
            List<RunMotion> runs = freeze(capture.runs);
            draws.add(new DrawMotion(
                    capture.draw,
                    capture.prepared == null ? "" : capture.prepared.display(),
                    accessor.lumenfx$primitiveTopology(),
                    accessor.lumenfx$format(),
                    capture.validated ? capture.actualVertexCount : accessor.lumenfx$vertexCount(),
                    capture.capturedGpuCount,
                    capture.validated,
                    capture.countValid,
                    capture.execution,
                    runs));
        }
        return List.copyOf(draws);
    }

    private static List<RunMotion> freeze(List<RunRecord> runs) {
        List<RunMotion> frozen = new ArrayList<>(runs.size());
        for (RunRecord run : runs) {
            frozen.add(run.freeze());
        }
        return List.copyOf(frozen);
    }

    private void attach(Object submit, SubmitKind kind, String renderSignature) {
        if (!this.enabled || !this.frameOpen || !this.levelSubmissionScope || submit == null) {
            return;
        }
        OwnerKey owner = this.currentOwner();
        if (owner == null) {
            return;
        }
        String bucket = kind.name() + '|' + renderSignature;
        Map<String, Integer> counts = this.occurrences.computeIfAbsent(owner, key -> new HashMap<>());
        int occurrence = counts.getOrDefault(bucket, 0);
        counts.put(bucket, occurrence + 1);
        this.submitKeys.put(submit, new SubmitKey(owner, kind, renderSignature, occurrence));
    }

    private @Nullable OwnerKey currentOwner() {
        ArrayList<OwnerKey> stack = this.ownerScopes.get();
        if (!stack.isEmpty()) {
            return stack.get(stack.size() - 1);
        }
        return null;
    }

    private @Nullable OwnerKey ownerOf(Entity entity) {
        Object world = this.worldIdentity != null ? this.worldIdentity : entity.level();
        UUID uuid = entity.getUUID();
        if (world == null || uuid == null) {
            return null;
        }
        return OwnerKey.entity(world, entity.getId(), uuid);
    }

    private String renderSignature(RenderType renderType, @Nullable String textureKey) {
        String base = this.baseSignatures.get(renderType);
        if (base == null) {
            base = renderType.pipeline().getLocation()
                    + "|" + renderType.primitiveTopology()
                    + "|" + renderType.format()
                    + "|" + renderType.isOutline()
                    + "|" + renderType.hasBlending()
                    + "|" + renderType.affectsCrumbling()
                    + "|" + renderType.sortOnUpload();
            this.baseSignatures.put(renderType, base);
        }
        return textureKey == null ? base : base + '|' + textureKey;
    }

    private void sessionsClear() {
        this.submitScopes.clear();
        this.submitKeys.clear();
        this.entityStateOwners.clear();
        this.blockStateOwners.clear();
        this.occurrences.clear();
        this.baseSignatures.clear();
        this.visited.clear();
        this.ownerScopes.get().clear();
        this.drawCaptures.clear();
        this.executionDraws.clear();
        this.preparedByDraw.clear();
        this.preparedMismatch.clear();
    }

    private FloatHistoryBuffer acquireBuffer() {
        FloatHistoryBuffer buffer = this.bufferPool.pollFirst();
        if (buffer == null) {
            return new FloatHistoryBuffer();
        }
        buffer.reset(INITIAL_CAPACITY_FLOATS);
        return buffer;
    }

    private void releaseHistory(SubmissionHistory history) {
        for (FloatHistoryBuffer buffer : history.ring) {
            boolean keep = buffer.data.length <= MAX_POOLED_ARRAY_FLOATS
                    && this.bufferPool.size() < MAX_POOLED_BUFFERS;
            buffer.reset(INITIAL_CAPACITY_FLOATS);
            if (keep) {
                this.bufferPool.addLast(buffer);
            }
        }
    }

    private void clearHistories() {
        for (SubmissionHistory history : this.histories.values()) {
            releaseHistory(history);
        }
        this.histories.clear();
    }
}
