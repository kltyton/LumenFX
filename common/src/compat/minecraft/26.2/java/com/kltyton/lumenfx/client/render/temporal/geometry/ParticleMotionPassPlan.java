package com.kltyton.lumenfx.client.render.temporal.geometry;

import com.kltyton.lumenfx.client.render.EffectFrame;
import com.kltyton.lumenfx.client.render.motion.shader.MotionShaderAdapter;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.MaterialDepth;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Coordinates particle color/decal-depth submission with pre-pass depth snapshots, translucent occlusion
 * depth merging from real color-pass depth writes, translucent velocity color-depth visibility guards,
 * and opaque velocity / zero-clear replay (Issues 26–31).
 */
public final class ParticleMotionPassPlan {
    private ParticleMotionPassPlan() { }

    /**
     * Strict irreflexive opaque depth compares ({@code D op D == false}) cannot rely on post-color EQUAL matching
     * because a coplanar rejected fragment would still pass EQUAL against an earlier surface's depth.
     * Applies to ALL opaque depth writers, including non-motion opaque batches that clear stale velocity/offset.
     */
    public static boolean requiresOpaqueMotionDepthReplay(MaterialDefinition material) {
        if (!EffectFrame.writesOpaqueDepth(material)) return false;
        return switch (material.depth().compare()) {
            case LESS, GREATER, NOT_EQUAL -> true;
            default -> false;
        };
    }

    /** Maps a material's depth compare to the reverse-Z fragment color-depth visibility guard code. */
    public static int colorDepthCompareCode(MaterialDepth.Compare compare) {
        return switch (compare) {
            case LESS -> MotionShaderAdapter.COLOR_DEPTH_COMPARE_GREATER;
            case EQUAL -> MotionShaderAdapter.COLOR_DEPTH_COMPARE_EQUAL;
            case LESS_EQUAL -> MotionShaderAdapter.COLOR_DEPTH_COMPARE_GREATER_EQUAL;
            case GREATER -> MotionShaderAdapter.COLOR_DEPTH_COMPARE_LESS;
            case NOT_EQUAL -> MotionShaderAdapter.COLOR_DEPTH_COMPARE_NOT_EQUAL;
            case GREATER_EQUAL -> MotionShaderAdapter.COLOR_DEPTH_COMPARE_LESS_EQUAL;
            case DISABLED, ALWAYS, NEVER -> MotionShaderAdapter.COLOR_DEPTH_COMPARE_NONE;
        };
    }

    /** Translucent depth-only writers that do not write velocity and must merge color depth writes into ParticleMotionDepth. */
    public static boolean writesNonVelocityTranslucentDepth(MaterialDefinition material) {
        return writesTranslucentColorDepth(material) && !material.writesVelocity();
    }

    public static boolean writesTranslucentColorDepth(MaterialDefinition material) {
        return material.blend() != MaterialDefinition.Blend.OPAQUE
                && material.depth().write()
                && material.depth().compare() != MaterialDepth.Compare.DISABLED
                && material.depth().compare() != MaterialDepth.Compare.NEVER;
    }

    public static boolean allowsFartherTranslucentDepthWrite(MaterialDefinition material) {
        return switch (material.depth().compare()) {
            case GREATER, GREATER_EQUAL, NOT_EQUAL, ALWAYS -> true;
            default -> false;
        };
    }

    public static boolean isTranslucentVelocityBatch(MaterialDefinition material, boolean eligibleForMotion) {
        return eligibleForMotion && material.blend() != MaterialDefinition.Blend.OPAQUE && material.writesVelocity();
    }

    /** Translucent velocity batches that also mutate color depth must be isolated so later batches see the new pre-color depth. */
    public static boolean isolatesTranslucentVelocityDepthWriter(MaterialDefinition material, boolean writesColorDepth,
                                                               boolean eligibleForMotion) {
        return writesColorDepth && isTranslucentVelocityBatch(material, eligibleForMotion)
                && writesTranslucentColorDepth(material);
    }

    /**
     * Depth-writing translucent velocity batches (including {@code ALWAYS}) evolve color depth per-primitive
     * and can mix velocity-eligible and low-opacity fragments, so they replay through the ordered fragment log.
     */
    public static boolean usesTranslucentColorReplay(MaterialDefinition material) {
        return writesTranslucentColorDepth(material) && material.writesVelocity();
    }

    /**
     * Returns whether a translucent batch participates in the ordered per-pixel translucent motion log
     * (either any velocity-writing translucent batch, or a non-velocity depth-writing translucent batch
     * when {@code ParticleMotionDepth} is already active in this frame).
     */
    public static boolean participatesInTranslucentMotionLog(MaterialDefinition material, boolean writesColorDepth,
                                                           boolean eligibleForMotion, boolean motionDepthActive) {
        if (material.blend() == MaterialDefinition.Blend.OPAQUE) return false;
        if (isTranslucentVelocityBatch(material, eligibleForMotion)) return true;
        return motionDepthActive && isTranslucentDepthSyncBatch(material, writesColorDepth, eligibleForMotion);
    }

    public static boolean isTranslucentDepthSyncBatch(MaterialDefinition material, boolean writesColorDepth,
                                                      boolean eligibleForMotion) {
        return writesColorDepth && writesTranslucentColorDepth(material)
                && !isTranslucentVelocityBatch(material, eligibleForMotion);
    }

    public static boolean splitsTranslucentGroup(boolean groupHasTranslucentVelocity,
                                                 boolean groupHasTranslucentDepthSync,
                                                 boolean batchIsTranslucentVelocity,
                                                 boolean batchIsTranslucentDepthSync) {
        return groupHasTranslucentVelocity && batchIsTranslucentDepthSync
                || groupHasTranslucentDepthSync && batchIsTranslucentVelocity;
    }

    public record GroupDepthPrep(boolean snapshotOpaqueReplayDepth,
                                 boolean seedTranslucentMotionDepth,
                                 boolean snapshotTranslucentPreColorDepth,
                                 boolean syncTranslucentMotionDepth,
                                 boolean allowsFartherTranslucentDepthWrite) {
        public static final GroupDepthPrep NONE = new GroupDepthPrep(false, false, false, false, false);
    }

    public static <T> GroupDepthPrep inspectGroup(List<T> batches, Function<T, MaterialDefinition> materialOf,
                                                  Predicate<T> eligibleForMotion,
                                                  Predicate<T> writesColorDepth) {
        boolean opaqueReplay = false;
        boolean translucentSeed = false;
        boolean translucentPreColor = false;
        boolean translucentDepthSync = false;
        boolean allowsFarther = false;
        for (int i = 0, n = batches.size(); i < n; i++) {
            T batch = batches.get(i);
            MaterialDefinition material = materialOf.apply(batch);
            if (requiresOpaqueMotionDepthReplay(material)) opaqueReplay = true;
            boolean motionEligible = eligibleForMotion.test(batch);
            if (isTranslucentVelocityBatch(material, motionEligible)) {
                translucentSeed = true;
                translucentPreColor = true;
            }
            if (isTranslucentDepthSyncBatch(material, writesColorDepth.test(batch), motionEligible)) {
                translucentDepthSync = true;
                translucentPreColor = true;
                if (allowsFartherTranslucentDepthWrite(material)) allowsFarther = true;
            }
            if (opaqueReplay && translucentSeed && translucentPreColor && translucentDepthSync && allowsFarther) break;
        }
        return !opaqueReplay && !translucentSeed && !translucentPreColor && !translucentDepthSync
                ? GroupDepthPrep.NONE
                : new GroupDepthPrep(opaqueReplay, translucentSeed, translucentPreColor, translucentDepthSync, allowsFarther);
    }

    /**
     * Runs a raster group in strict order:
     * 1) pre-raster opaque replay snapshot, translucent motion depth seed, and translucent pre-color depth snapshot,
     * 2) raster pass,
     * 3) merge only actual color-pass depth changes into active ParticleMotionDepth (Issues 29 & 30a),
     * 4) velocity / opaque zero-clear pass (using pre-raster opaque replay snapshot or translucent color-depth guard, Issues 30b & 31).
     */
    public static <T> void runRasterGroup(List<T> batches, boolean motionActive,
                                          Function<T, MaterialDefinition> materialOf,
                                          Predicate<T> eligibleForMotion,
                                          Predicate<T> writesColorDepth,
                                          Runnable snapshotOpaqueReplayDepth,
                                          Runnable seedTranslucentMotionDepth,
                                          Runnable snapshotTranslucentPreColorDepth,
                                          Runnable drawRaster,
                                          Consumer<Boolean> mergeTranslucentMotionDepth,
                                          Consumer<Boolean> drawMotion) {
        GroupDepthPrep prep = motionActive
                ? inspectGroup(batches, materialOf, eligibleForMotion, writesColorDepth)
                : GroupDepthPrep.NONE;
        if (prep.snapshotOpaqueReplayDepth()) snapshotOpaqueReplayDepth.run();
        if (prep.seedTranslucentMotionDepth()) seedTranslucentMotionDepth.run();
        if (prep.snapshotTranslucentPreColorDepth()) snapshotTranslucentPreColorDepth.run();
        drawRaster.run();
        if (prep.syncTranslucentMotionDepth()) mergeTranslucentMotionDepth.accept(prep.allowsFartherTranslucentDepthWrite());
        if (motionActive) drawMotion.accept(prep.snapshotOpaqueReplayDepth());
    }

    /**
     * Splits a translucent batch stream into ordered groups whenever velocity-writing batches and
     * non-velocity depth-writing batches alternate, and isolates depth-writing translucent velocity
     * batches so every subsequent batch tests against the updated pre-color depth (Issues 29 & 30).
     */
    public static <T> void runTranslucentGroups(List<T> batches, List<T> scratch,
                                                Function<T, MaterialDefinition> materialOf,
                                                Predicate<T> eligibleForMotion,
                                                Predicate<T> writesColorDepth,
                                                Consumer<List<T>> groupExecutor) {
        if (batches.isEmpty()) return;
        scratch.clear();
        boolean groupHasVel = false;
        boolean groupHasDepthSync = false;
        try {
            for (int i = 0, n = batches.size(); i < n; i++) {
                T batch = batches.get(i);
                MaterialDefinition material = materialOf.apply(batch);
                boolean writesDepth = writesColorDepth.test(batch);
                boolean motionEligible = eligibleForMotion.test(batch);
                boolean batchVel = isTranslucentVelocityBatch(material, motionEligible);
                boolean batchDepthSync = isTranslucentDepthSyncBatch(material, writesDepth, motionEligible);
                boolean isolateVelDepth = isolatesTranslucentVelocityDepthWriter(material, writesDepth, motionEligible);
                if (!scratch.isEmpty() && (isolateVelDepth
                        || splitsTranslucentGroup(groupHasVel, groupHasDepthSync, batchVel, batchDepthSync))) {
                    groupExecutor.accept(scratch);
                    scratch.clear();
                    groupHasVel = false;
                    groupHasDepthSync = false;
                }
                scratch.add(batch);
                groupHasVel |= batchVel;
                groupHasDepthSync |= batchDepthSync;
                if (isolateVelDepth) {
                    groupExecutor.accept(scratch);
                    scratch.clear();
                    groupHasVel = false;
                    groupHasDepthSync = false;
                }
            }
            if (!scratch.isEmpty()) groupExecutor.accept(scratch);
        } finally {
            scratch.clear();
        }
    }

    @FunctionalInterface
    public interface DecalGroupExecutor<T> {
        void run(List<T> group, boolean snapshotPrePassDepth);
    }

    /**
     * Groups native decal receiver batches so strict-compare opaque batches (both motion and non-motion
     * zero-clear writers) snapshot decal depth strictly before drawing and replay against that snapshot (Issues 27 & 31).
     */
    public static <T> void runDecalReceivers(List<T> batches, List<T> scratch, boolean motionActive,
                                             Function<T, MaterialDefinition> materialOf,
                                             Predicate<T> hasMotion,
                                             DecalGroupExecutor<T> executor) {
        if (batches.isEmpty()) return;
        boolean hasStrict = false;
        if (motionActive) {
            for (int i = 0, n = batches.size(); i < n; i++) {
                if (requiresOpaqueMotionDepthReplay(materialOf.apply(batches.get(i)))) {
                    hasStrict = true;
                    break;
                }
            }
        }
        if (!hasStrict) {
            executor.run(batches, false);
            return;
        }
        scratch.clear();
        try {
            for (int i = 0, n = batches.size(); i < n; i++) {
                T batch = batches.get(i);
                boolean strict = requiresOpaqueMotionDepthReplay(materialOf.apply(batch));
                if (!scratch.isEmpty() && strict) {
                    executor.run(scratch, false);
                    scratch.clear();
                }
                scratch.add(batch);
                if (strict) {
                    executor.run(scratch, true);
                    scratch.clear();
                }
            }
            if (!scratch.isEmpty()) executor.run(scratch, false);
        } finally {
            scratch.clear();
        }
    }
}
