package com.kltyton.lumenfx.client.keyframe;

import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Pure-math 3D world-space pose sampler for Minecraft player models driven by Player Animation Library (PAL),
 * {@code playerAnimator} (1.20.1), {@code bendable-cuboids} / {@code bendy-lib}, and
 * {@code PlayerAnimationLibraryMoreRotation} (PALMore 3-axis bend + bend.position + bend.scale).
 *
 * <p>Has zero compile-time dependencies on any animation mod; callers supply plain numeric bone transforms.</p>
 */
public final class PlayerBonePoseSampler {
    private static final float PIXEL_TO_BLOCK = 1.0F / 16.0F;
    private static final float EPSILON = 1.0E-5F;

    /** Standard PAL bone pivot positions in model pixels (Y-up from feet, matching {@code HumanoidAnimationController.BONE_POSITIONS}). */
    private static final Map<String, float[]> STANDARD_PIVOTS_PX = Map.ofEntries(
            Map.entry("body", new float[] { 0.0F, 12.0F, 0.0F }),
            Map.entry("head", new float[] { 0.0F, 24.0F, 0.0F }),
            Map.entry("torso", new float[] { 0.0F, 24.0F, 0.0F }),
            Map.entry("right_arm", new float[] { 5.0F, 22.0F, 0.0F }),
            Map.entry("left_arm", new float[] { -5.0F, 22.0F, 0.0F }),
            Map.entry("right_leg", new float[] { 2.0F, 12.0F, 0.0F }),
            Map.entry("left_leg", new float[] { -2.0F, 12.0F, 0.0F }),
            Map.entry("right_item", new float[] { 6.0F, 12.0F, -2.0F }),
            Map.entry("left_item", new float[] { -6.0F, 12.0F, -2.0F }),
            Map.entry("cape", new float[] { 0.0F, 24.0F, 2.0F }),
            Map.entry("elytra", new float[] { 0.0F, 24.0F, 2.0F })
    );

    /**
     * Evaluated per-bone transform in PAL/playerAnimator conventions (position in pixels, rotation & bend in radians).
     * Retains native bend ({@code nativeBendAxis}, {@code nativeBend}) separately from PALMore extended sidecar bend
     * ({@code bendX/Y/Z}, {@code bendPos*}, {@code bendScale*}, {@code hasExtendedBend}) so held-item pose sampling
     * mirrors both the native bend layer and PALMore's extended layer accurately across 1.20.1, 1.21.1, and 26.x.
     */
    public record BonePose(
            float posX, float posY, float posZ,
            float rotX, float rotY, float rotZ,
            float scaleX, float scaleY, float scaleZ,
            float bendX, float bendY, float bendZ,
            float bendPosX, float bendPosY, float bendPosZ,
            float bendScaleX, float bendScaleY, float bendScaleZ,
            boolean hasBendTransform,
            float nativeBendAxis,
            float nativeBend,
            boolean hasExtendedBend
    ) {
        public static final BonePose IDENTITY = new BonePose(
                0.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 0.0F,
                1.0F, 1.0F, 1.0F,
                0.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 0.0F,
                1.0F, 1.0F, 1.0F,
                false,
                0.0F, 0.0F, false);

        /**
         * Backward-compatible 19-argument constructor. When only {@code bendX} is non-zero (no Y/Z bend and no
         * bend-local position/scale override), treats it as a native single-axis bend ({@code nativeBend = bendX});
         * when extended Y/Z/transform channels are active, marks {@code hasExtendedBend = true} with
         * {@code nativeBend = 0} (matching PALMore {@code bendPartTracks} sidecar bones).
         */
        public BonePose(
                float posX, float posY, float posZ,
                float rotX, float rotY, float rotZ,
                float scaleX, float scaleY, float scaleZ,
                float bendX, float bendY, float bendZ,
                float bendPosX, float bendPosY, float bendPosZ,
                float bendScaleX, float bendScaleY, float bendScaleZ,
                boolean hasBendTransform
        ) {
            this(
                    posX, posY, posZ,
                    rotX, rotY, rotZ,
                    scaleX, scaleY, scaleZ,
                    bendX, bendY, bendZ,
                    bendPosX, bendPosY, bendPosZ,
                    bendScaleX, bendScaleY, bendScaleZ,
                    hasBendTransform,
                    0.0F,
                    isExtendedChannelActive(bendY, bendZ, bendPosX, bendPosY, bendPosZ,
                            bendScaleX, bendScaleY, bendScaleZ, hasBendTransform) ? 0.0F : bendX,
                    isExtendedChannelActive(bendY, bendZ, bendPosX, bendPosY, bendPosZ,
                            bendScaleX, bendScaleY, bendScaleZ, hasBendTransform)
            );
        }

        private static boolean isExtendedChannelActive(
                float bendY, float bendZ,
                float bendPosX, float bendPosY, float bendPosZ,
                float bendScaleX, float bendScaleY, float bendScaleZ,
                boolean hasBendTransform
        ) {
            return Math.abs(bendY) > EPSILON
                    || Math.abs(bendZ) > EPSILON
                    || (hasBendTransform
                    && (Math.abs(bendPosX) > EPSILON
                    || Math.abs(bendPosY) > EPSILON
                    || Math.abs(bendPosZ) > EPSILON
                    || Math.abs(bendScaleX - 1.0F) > EPSILON
                    || Math.abs(bendScaleY - 1.0F) > EPSILON
                    || Math.abs(bendScaleZ - 1.0F) > EPSILON));
        }

        public boolean hasAnyBend() {
            return Math.abs(nativeBend) > EPSILON
                    || Math.abs(bendX) > EPSILON
                    || Math.abs(bendY) > EPSILON
                    || Math.abs(bendZ) > EPSILON
                    || hasNonTrivialBendTransform();
        }

        public boolean hasNonTrivialBendTransform() {
            return hasBendTransform
                    && (Math.abs(bendPosX) > EPSILON
                    || Math.abs(bendPosY) > EPSILON
                    || Math.abs(bendPosZ) > EPSILON
                    || Math.abs(bendScaleX - 1.0F) > EPSILON
                    || Math.abs(bendScaleY - 1.0F) > EPSILON
                    || Math.abs(bendScaleZ - 1.0F) > EPSILON);
        }
    }

    /** Sampled world-space 3D position (in blocks) and normalized orientation quaternion. */
    public record WorldPose(double x, double y, double z, float qx, float qy, float qz, float qw) { }

    /** Lookup callback supplying evaluated {@link BonePose} and optional custom pivot (in pixels) for canonical bone names. */
    public interface BoneLookup {
        @Nullable BonePose bone(String canonicalBoneName);

        default @Nullable float[] customPivotPx(String locatorName) {
            return null;
        }
    }

    private PlayerBonePoseSampler() { }

    /**
     * Normalizes PAL, legacy {@code playerAnimator}, PAL Bend Player Tools, and DragonCore locator/bone aliases
     * into a canonical locator key.
     */
    public static String canonicalizeLocator(@Nullable String rawName) {
        if (rawName == null) return "";
        String trimmed = rawName.trim();
        if (trimmed.isEmpty()) return "";
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "body", "root" -> "body";
            case "head" -> "head";
            case "torso", "chest", "body_lower" -> "torso";
            case "torso_bend", "torso.bend", "body_upper", "upper_body" -> "torso_bend";
            case "right_arm", "rightarm" -> "right_arm";
            case "left_arm", "leftarm" -> "left_arm";
            case "right_arm_bend", "rightarmbend", "right_arm.bend", "right_arm_lower", "right_forearm" -> "right_arm_bend";
            case "left_arm_bend", "leftarmbend", "left_arm.bend", "left_arm_lower", "left_forearm" -> "left_arm_bend";
            case "right_leg", "rightleg" -> "right_leg";
            case "left_leg", "leftleg" -> "left_leg";
            case "right_leg_bend", "rightlegbend", "right_leg.bend", "right_leg_lower", "right_shin" -> "right_leg_bend";
            case "left_leg_bend", "leftlegbend", "left_leg.bend", "left_leg_lower", "left_shin" -> "left_leg_bend";
            case "right_item", "rightitem", "right_hand", "righthand" -> "right_item";
            case "left_item", "leftitem", "left_hand", "lefthand" -> "left_item";
            case "cape" -> "cape";
            case "elytra" -> "elytra";
            default -> trimmed;
        };
    }

    /** Maps canonical snake_case bone names to 1.20.1 {@code playerAnimator} camelCase names. */
    public static String toLegacyBoneName(String canonical) {
        return switch (canonical) {
            case "right_arm" -> "rightArm";
            case "left_arm" -> "leftArm";
            case "right_leg" -> "rightLeg";
            case "left_leg" -> "leftLeg";
            case "right_item" -> "rightItem";
            case "left_item" -> "leftItem";
            default -> canonical;
        };
    }

    /**
     * Exact whole-body ({@code body}) scale/translation ordering matching each PAL / playerAnimator renderer generation:
     * <ul>
     *   <li>{@link #PAL_1_2}: {@code translate -> rotate -> scale -> unpivot} (PAL 1.2.x {@code LivingEntityRendererMixin}).</li>
     *   <li>{@link #PAL_1_1}: {@code scale -> translate -> rotate -> unpivot} (PAL 1.1.x {@code PlayerRendererMixin}).</li>
     *   <li>{@link #LEGACY_PALMORE_1_20_1}: {@code translate -> rotate -> unpivot -> scale} (1.20.1 {@code playerAnimator} + {@code PlayerRendererScaleMixin}).</li>
     * </ul>
     */
    public enum BodyTransformOrder {
        PAL_1_2,
        PAL_1_1,
        LEGACY_PALMORE_1_20_1
    }

    /**
     * Computes the world-space position and orientation quaternion for {@code rawLocatorName} on a player entity
     * using the default PAL 1.2.x body transform order.
     */
    public static @Nullable WorldPose sample(double entityX, double entityY, double entityZ,
                                             float bodyYawDeg, float entityScale,
                                             String rawLocator, BoneLookup lookup) {
        return sample(entityX, entityY, entityZ, bodyYawDeg, entityScale, rawLocator, lookup, BodyTransformOrder.PAL_1_2);
    }

    /**
     * Computes the world-space position and orientation quaternion for {@code rawLocatorName} on a player entity
     * by mirroring Minecraft's {@code LivingEntityRenderer} + PAL / PALMore {@code PoseStack} transform pipeline:
     * upright {@code RotateY(180 - bodyYaw) -> entityScale -> body}, then entering {@code ModelPart} space via
     * {@code scale(-1, -1, 1) -> translate(0, -1.5, 0)} for all child bones, bends, and held items.
     *
     * @param entityX      interpolated player world X (blocks)
     * @param entityY      interpolated player world Y at feet (blocks)
     * @param entityZ      interpolated player world Z (blocks)
     * @param bodyYawDeg   interpolated player body yaw in degrees ({@code yBodyRot})
     * @param entityScale  entity scale attribute (1.0 for default player)
     * @param rawLocator   locator or bone name from the particle keyframe
     * @param lookup       source for evaluated bone poses and custom pivots
     * @param bodyOrder    renderer-specific {@code body} scale placement
     */
    public static @Nullable WorldPose sample(double entityX, double entityY, double entityZ,
                                             float bodyYawDeg, float entityScale,
                                             String rawLocator, BoneLookup lookup,
                                             BodyTransformOrder bodyOrder) {
        String canonical = canonicalizeLocator(rawLocator);
        if (canonical.isEmpty()) {
            return null;
        }
        BodyTransformOrder resolvedOrder = bodyOrder != null ? bodyOrder : BodyTransformOrder.PAL_1_2;

        float scale = Float.isFinite(entityScale) && entityScale > 0.0F ? entityScale : 1.0F;
        Matrix4f mat = new Matrix4f()
                .rotateY((float) Math.toRadians(180.0F - bodyYawDeg))
                .scale(scale);

        // Apply whole-body ("body") animation transform in upright space around waist pivot (0, 12px, 0) = (0, 0.75, 0),
        // matching PAL PlayerRendererMixin (1.1.x) / LivingEntityRendererMixin (1.2.x) exactly.
        BonePose body = nonNull(lookup.bone("body"));
        applyBodyTransform(mat, body, resolvedOrder);

        if ("body".equals(canonical)) {
            mat.translate(0.0F, 12.0F * PIXEL_TO_BLOCK, 0.0F);
        } else {
            // Enter Minecraft ModelPart space: scale(-1, -1, 1) -> translate(0, -24px, 0)
            mat.scale(-1.0F, -1.0F, 1.0F);
            mat.translate(0.0F, -24.0F * PIXEL_TO_BLOCK, 0.0F);

            switch (canonical) {
                case "torso_bend" -> {
                    applyUpperTorsoBendPose(mat, nonNull(lookup.bone("torso")), resolvedOrder);
                }
                case "right_arm_bend" -> {
                    applyLimbBendSegmentPose(mat, "right_arm", nonNull(lookup.bone("right_arm")), 4.0F, resolvedOrder);
                }
                case "left_arm_bend" -> {
                    applyLimbBendSegmentPose(mat, "left_arm", nonNull(lookup.bone("left_arm")), 4.0F, resolvedOrder);
                }
                case "right_leg_bend" -> {
                    applyLimbBendSegmentPose(mat, "right_leg", nonNull(lookup.bone("right_leg")), 6.0F, resolvedOrder);
                }
                case "left_leg_bend" -> {
                    applyLimbBendSegmentPose(mat, "left_leg", nonNull(lookup.bone("left_leg")), 6.0F, resolvedOrder);
                }
                case "right_item" -> {
                    applyHeldItemPose(mat, "right_arm", nonNull(lookup.bone("right_arm")),
                            nonNull(lookup.bone("right_item")), false, resolvedOrder);
                }
                case "left_item" -> {
                    applyHeldItemPose(mat, "left_arm", nonNull(lookup.bone("left_arm")),
                            nonNull(lookup.bone("left_item")), true, resolvedOrder);
                }
                default -> {
                    float[] pivot = STANDARD_PIVOTS_PX.get(canonical);
                    BonePose bone = lookup.bone(canonical);
                    if (pivot == null) {
                        pivot = lookup.customPivotPx(canonical);
                        if (pivot == null && !canonical.equals(rawLocator)) {
                            pivot = lookup.customPivotPx(rawLocator);
                        }
                        if (bone == null && !canonical.equals(rawLocator)) {
                            bone = lookup.bone(rawLocator);
                        }
                        if (pivot == null && bone == null) {
                            return null;
                        }
                    }
                    applyStandardBonePose(mat, pivot != null ? pivot : new float[] { 0.0F, 0.0F, 0.0F }, nonNull(bone));
                }
            }

            // Convert orientation back from ModelPart Y-down (+X left) to world Y-up right-handed frame
            // without altering the already-computed translation vector.
            mat.scale(-1.0F, -1.0F, 1.0F);
        }

        Vector3f localPos = mat.getTranslation(new Vector3f());
        Quaternionf worldRot = extractNormalizedRotation(mat);
        return new WorldPose(
                entityX + localPos.x,
                entityY + localPos.y,
                entityZ + localPos.z,
                worldRot.x, worldRot.y, worldRot.z, worldRot.w);
    }

    private static void applyBodyTransform(Matrix4f mat, BonePose body, BodyTransformOrder order) {
        if (body == BonePose.IDENTITY) {
            return;
        }
        float sx = safeScale(body.scaleX());
        float sy = safeScale(body.scaleY());
        float sz = safeScale(body.scaleZ());
        if (order == BodyTransformOrder.PAL_1_1) {
            mat.scale(sx, sy, sz);
        }
        // In upright space (before ModelPart's scale(-1,-1,1)), PAL negates body.posX, body.rotX, and body.rotY
        // so that +posX matches ModelPart +X (world +X at bodyYaw = 0).
        mat.translate(
                -body.posX() * PIXEL_TO_BLOCK,
                body.posY() * PIXEL_TO_BLOCK + 12.0F * PIXEL_TO_BLOCK,
                body.posZ() * PIXEL_TO_BLOCK);
        applyBoneRotation(mat, -body.rotX(), -body.rotY(), body.rotZ());
        if (order == BodyTransformOrder.PAL_1_2) {
            mat.scale(sx, sy, sz);
        }
        mat.translate(0.0F, -12.0F * PIXEL_TO_BLOCK, 0.0F);
        if (order == BodyTransformOrder.LEGACY_PALMORE_1_20_1) {
            mat.scale(sx, sy, sz);
        }
    }

    /**
     * Applies PAL {@code RenderUtil.translatePartToBone} + Minecraft {@code ModelPart.translateAndRotate} in
     * {@code ModelPart} space: {@code part.x = -pivot.x + bone.posX}, {@code part.y = (24 - pivot.y) - bone.posY},
     * {@code part.z = pivot.z + bone.posZ}, followed by {@code rotateZYX(rotZ, rotY, rotX)} and {@code scale}.
     */
    private static void applyStandardBonePose(Matrix4f mat, float[] pivotPx, BonePose bone) {
        mat.translate(
                (-pivotPx[0] + bone.posX()) * PIXEL_TO_BLOCK,
                (24.0F - pivotPx[1] - bone.posY()) * PIXEL_TO_BLOCK,
                (pivotPx[2] + bone.posZ()) * PIXEL_TO_BLOCK);
        applyBoneRotation(mat, bone.rotX(), bone.rotY(), bone.rotZ());
        mat.scale(safeScale(bone.scaleX()), safeScale(bone.scaleY()), safeScale(bone.scaleZ()));
    }

    /**
     * Upper torso bend segment ({@code Direction.DOWN} in {@code bendable-cuboids}): hinge sits at ModelPart
     * {@code Y = +6px} (6px below neck), and upper torso segment center sits 3px above the hinge ({@code -3px} in ModelPart Y).
     */
    private static void applyUpperTorsoBendPose(Matrix4f mat, BonePose torso, BodyTransformOrder order) {
        applyStandardBonePose(mat, STANDARD_PIVOTS_PX.get("torso"), torso);
        applySegmentBendTransform(mat, torso, 6.0F, order);
        mat.translate(0.0F, -3.0F * PIXEL_TO_BLOCK, 0.0F);
    }

    /**
     * Lower limb bend segment ({@code Direction.UP} in {@code bendable-cuboids}): hinge sits {@code +hingeDropPx}
     * below shoulder/hip in ModelPart Y.
     */
    private static void applyLimbBendSegmentPose(Matrix4f mat, String parentBone, BonePose parent,
                                                 float hingeDropPx, BodyTransformOrder order) {
        applyStandardBonePose(mat, STANDARD_PIVOTS_PX.get(parentBone), parent);
        applySegmentBendTransform(mat, parent, hingeDropPx, order);
    }

    /**
     * Held item / hand pose mirroring Minecraft {@code ItemInHandLayer}, PAL {@code ItemInHandLayerMixin},
     * {@code bendable-cuboids} / 1.20.1 {@code playerAnimator HeldItemMixin}, and PALMore
     * {@code PalMoreBendableCuboids.applyArmItemBend} / {@code PalMoreBendyCuboids.applyArmItemBend}.
     */
    private static void applyHeldItemPose(Matrix4f mat, String armBoneName, BonePose arm,
                                          BonePose itemBone, boolean isLeft, BodyTransformOrder order) {
        // 1. ParentModel.translateToHand(arm) + PALMore parent arm scale
        applyStandardBonePose(mat, STANDARD_PIVOTS_PX.get(armBoneName), arm);

        // 2. Two-stage arm elbow bend around ModelPart Y = +4px (+0.25F)
        applyHeldItemArmBend(mat, arm, order);

        // 3. PAL ItemInHandLayerMixin.changeItemLocation: translate(+posX/16, -posY/16, +posZ/16) in arm ModelPart space
        mat.translate(
                itemBone.posX() * PIXEL_TO_BLOCK,
                -itemBone.posY() * PIXEL_TO_BLOCK,
                itemBone.posZ() * PIXEL_TO_BLOCK);

        // 4. Vanilla ItemInHandLayer hand orientation + offset ((left ? -1 : 1)/16, +2/16, -10/16)
        float negHalfPi = (float) (-Math.PI * 0.5);
        float pi = (float) Math.PI;
        mat.rotateX(negHalfPi);
        mat.rotateY(pi);
        mat.translate(
                (isLeft ? -1.0F : 1.0F) * PIXEL_TO_BLOCK,
                2.0F * PIXEL_TO_BLOCK,
                -10.0F * PIXEL_TO_BLOCK);

        // 5. PAL / PALMore PlayerItemRotationFixMixin item rotation & scale
        if (Math.abs(itemBone.rotZ()) > EPSILON) {
            mat.rotateZ(-itemBone.rotZ());
        }
        if (Math.abs(itemBone.rotY()) > EPSILON) {
            mat.rotateY(-itemBone.rotY());
        }
        if (Math.abs(itemBone.rotX()) > EPSILON) {
            mat.rotateX(-itemBone.rotX());
        }
        mat.scale(safeScale(itemBone.scaleX()), safeScale(itemBone.scaleY()), safeScale(itemBone.scaleZ()));

        // Undo static vanilla hand frame (RotateY(-180) * RotateX(+90)) so neutral held item keeps Y-up orientation
        mat.rotateY(-pi);
        mat.rotateX(-negHalfPi);
    }

    /**
     * Exact two-stage held-item arm bend matching each host generation's actual {@code PoseStack} sequence:
     * <ul>
     *   <li>PAL 1.1.x / 1.2.x: {@code bendable-cuboids} native {@code Rx(+nativeBend)} around {@code y=+0.25},
     *       followed by {@code PalMoreBendableCuboids.applyArmItemBend} ({@code Rx(-bendX)} undo around {@code y=+0.25},
     *       then {@code bend.position -> y=+0.25 -> bend.scale -> Rz -> Ry -> Rx(+bendX) -> y=-0.25}) whenever extended
     *       PALMore channels are active.</li>
     *   <li>1.20.1 {@code playerAnimator} + PALMore: {@code HeldItemMixin} native
     *       {@code rotateAxis(+nativeBend, (cos(-axis), 0, sin(-axis)))} around {@code y=+0.25}, followed by
     *       {@code PalMoreBendyCuboids.applyArmItemBend} ({@code rotateAxis(-nativeBend, axisVec)} replacement,
     *       then {@code bend.position -> y=+0.25 -> bend.scale -> Rz -> Ry -> Rx(+bendX) -> y=-0.25}) when sidecar
     *       bend is active.</li>
     * </ul>
     */
    private static void applyHeldItemArmBend(Matrix4f mat, BonePose arm, BodyTransformOrder order) {
        if (!arm.hasAnyBend()) {
            return;
        }
        float pivotY = 4.0F * PIXEL_TO_BLOCK;
        if (order == BodyTransformOrder.LEGACY_PALMORE_1_20_1) {
            boolean hasNative = Math.abs(arm.nativeBend()) > EPSILON;
            Vector3f nativeAxisVec = hasNative ? legacyBendAxisVector(arm.nativeBendAxis()) : null;
            // Stage 1: playerAnimator HeldItemMixin (priority 1000)
            if (hasNative) {
                mat.translate(0.0F, pivotY, 0.0F);
                mat.rotate(new Quaternionf().rotateAxis(arm.nativeBend(), nativeAxisVec));
                mat.translate(0.0F, -pivotY, 0.0F);
            }
            // Stage 2: PALMore 1.20.1 ItemInHandLayerExtendedAnimMixin -> PalMoreBendyCuboids.applyArmItemBend (priority 2500)
            if (arm.hasExtendedBend()) {
                if (hasNative) {
                    mat.translate(0.0F, pivotY, 0.0F);
                    mat.rotate(new Quaternionf().rotateAxis(-arm.nativeBend(), nativeAxisVec));
                    mat.translate(0.0F, -pivotY, 0.0F);
                }
                applyExtendedBendAtHinge(mat, arm, 4.0F);
                mat.translate(0.0F, -pivotY, 0.0F);
            }
        } else {
            // Stage 1: bendable-cuboids ItemInHandLayerMixin_playerAnim.renderMixin
            if (Math.abs(arm.nativeBend()) > EPSILON) {
                mat.translate(0.0F, pivotY, 0.0F);
                mat.rotateX(arm.nativeBend());
                mat.translate(0.0F, -pivotY, 0.0F);
            }
            // Stage 2: PALMore ItemInHandLayerBendVectorMixin -> PalMoreBendableCuboids.applyArmItemBend
            if (arm.hasExtendedBend()) {
                if (Math.abs(arm.bendX()) > EPSILON) {
                    mat.translate(0.0F, pivotY, 0.0F);
                    mat.rotateX(-arm.bendX());
                    mat.translate(0.0F, -pivotY, 0.0F);
                }
                applyExtendedBendAtHinge(mat, arm, 4.0F);
                mat.translate(0.0F, -pivotY, 0.0F);
            }
        }
    }

    private static void applySegmentBendTransform(Matrix4f mat, BonePose bone, float hingeDropPx, BodyTransformOrder order) {
        if (!bone.hasAnyBend()) {
            mat.translate(0.0F, hingeDropPx * PIXEL_TO_BLOCK, 0.0F);
            return;
        }
        if (order == BodyTransformOrder.LEGACY_PALMORE_1_20_1 && !bone.hasExtendedBend()
                && Math.abs(bone.nativeBend()) > EPSILON) {
            mat.translate(0.0F, hingeDropPx * PIXEL_TO_BLOCK, 0.0F);
            mat.rotate(new Quaternionf().rotateAxis(bone.nativeBend(), legacyBendAxisVector(bone.nativeBendAxis())));
            return;
        }
        applyExtendedBendAtHinge(mat, bone, hingeDropPx);
    }

    private static void applyExtendedBendAtHinge(Matrix4f mat, BonePose bone, float hingeDropPx) {
        if (bone.hasNonTrivialBendTransform()) {
            mat.translate(
                    bone.bendPosX() * PIXEL_TO_BLOCK,
                    -bone.bendPosY() * PIXEL_TO_BLOCK,
                    bone.bendPosZ() * PIXEL_TO_BLOCK);
        }
        mat.translate(0.0F, hingeDropPx * PIXEL_TO_BLOCK, 0.0F);
        if (bone.hasNonTrivialBendTransform()) {
            mat.scale(safeScale(bone.bendScaleX()), safeScale(bone.bendScaleY()), safeScale(bone.bendScaleZ()));
        }
        if (Math.abs(bone.bendZ()) > EPSILON) {
            mat.rotateZ(bone.bendZ());
        }
        if (Math.abs(bone.bendY()) > EPSILON) {
            mat.rotateY(bone.bendY());
        }
        float rx = bone.hasExtendedBend() ? bone.bendX() : bone.nativeBend();
        if (Math.abs(rx) > EPSILON) {
            mat.rotateX(rx);
        }
    }

    private static Vector3f legacyBendAxisVector(float nativeBendAxis) {
        float axisAngle = -nativeBendAxis;
        return new Vector3f((float) Math.cos(axisAngle), 0.0F, (float) Math.sin(axisAngle));
    }

    private static void applyBoneRotation(Matrix4f mat, float rotX, float rotY, float rotZ) {
        if (Math.abs(rotX) > EPSILON || Math.abs(rotY) > EPSILON || Math.abs(rotZ) > EPSILON) {
            mat.rotateZYX(rotZ, rotY, rotX);
        }
    }

    private static float safeScale(float value) {
        if (!Float.isFinite(value) || Math.abs(value) < EPSILON) {
            return 1.0F;
        }
        return value;
    }

    private static Quaternionf extractNormalizedRotation(Matrix4f mat) {
        Quaternionf q = mat.getNormalizedRotation(new Quaternionf());
        if (!Float.isFinite(q.x) || !Float.isFinite(q.y) || !Float.isFinite(q.z) || !Float.isFinite(q.w)) {
            return new Quaternionf();
        }
        return q.normalize();
    }

    private static BonePose nonNull(@Nullable BonePose pose) {
        return pose != null ? pose : BonePose.IDENTITY;
    }
}
