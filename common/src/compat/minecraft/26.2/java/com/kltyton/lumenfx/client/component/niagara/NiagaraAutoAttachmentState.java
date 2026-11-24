package com.kltyton.lumenfx.client.component.niagara;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.component.ComponentValue;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAutoAttachmentParameters;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAutoAttachmentParameters.Rule;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSocketInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSocketInputs.Source;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.client.transform.SceneComponentTransform;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.NiagaraComponentAttachment;
import com.kltyton.lumenfx.effect.niagara.NiagaraComponentAttachment.RelativeTransform;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/** Owns NiagaraComponent's saved relative transform and activation-only parent attachment. */
public final class NiagaraAutoAttachmentState {
    private record Parts(double x, double y, double z, double qx, double qy, double qz, double qw,
                         double sx, double sy, double sz) { }

    private final NiagaraComponentAttachment authored;
    private final boolean absoluteLocation, absoluteRotation, absoluteScale;
    private final Parts authoredRelative;
    private long activation = Long.MIN_VALUE;
    private boolean didAutoAttach, attached, parentLost;
    private @Nullable String attachedParent;
    private String attachedSocket = "None";
    private Parts savedRelative;
    private Parts currentRelative;
    private @Nullable Pose registrationPose;
    private Pose lastRequestedPose = Pose.at(net.minecraft.world.phys.Vec3.ZERO);
    private Pose lastWorldPose = Pose.at(net.minecraft.world.phys.Vec3.ZERO);

    public NiagaraAutoAttachmentState(NiagaraComponentAttachment authored, Pose registrationPose) {
        this.authored = authored;
        RelativeTransform relative = authored.relativeTransform();
        absoluteLocation = relative.absoluteLocation();
        absoluteRotation = relative.absoluteRotation();
        absoluteScale = relative.absoluteScale();
        authoredRelative = parts(relativeTransform(relative));
        reset(registrationPose);
    }

    public void update(EffectHandle handle, boolean active, long activationId) {
        var requested = handle.inputWithoutNiagaraAttachment();
        boolean enabled = enabled(handle);
        NiagaraAutoAttachmentParameters parameters = parameters(handle);
        if (active && activationId != activation) {
            activation = activationId;
            if (enabled) attach(handle, requested.pose(), handle.niagaraSocketInputs(), parameters);
            return;
        }
        if (!didAutoAttach) {
            handle.setNiagaraAttachmentPose(null);
            return;
        }
        if (!attached || parentLost) {
            handle.setNiagaraAttachmentPose(lastWorldPose);
            return;
        }
        if (!requested.pose().equals(lastRequestedPose)) {
            var parent = resolveParent(handle.niagaraSocketInputs(), attachedParent, attachedSocket);
            if (parent == null) {
                attached = false;
                parentLost = true;
                handle.setNiagaraAttachmentPose(lastWorldPose);
                return;
            }
            currentRelative = relativeForWorld(requested.pose(), parent);
            lastRequestedPose = requested.pose();
        }
        var parent = resolveParent(handle.niagaraSocketInputs(), attachedParent, attachedSocket);
        if (parent == null) {
            attached = false;
            parentLost = true;
            handle.setNiagaraAttachmentPose(lastWorldPose);
            return;
        }
        lastWorldPose = worldPose(currentRelative, parent);
        handle.setNiagaraAttachmentPose(lastWorldPose);
    }

    private void attach(EffectHandle handle, Pose currentWorld, NiagaraSocketInputs inputs,
                        NiagaraAutoAttachmentParameters parameters) {
        if (!enabled(handle)) {
            handle.setNiagaraAttachmentPose(null);
            return;
        }
        if (parameters.parentIdentity() == null) {
            detachWithoutRestore(handle);
            return;
        }
        boolean alreadyAttached = attached && attachedParent != null
                && SourceNames.equal(attachedParent, parameters.parentIdentity())
                && SourceNames.equal(attachedSocket, parameters.socketName());
        if (alreadyAttached) {
            var currentParent = resolveParent(inputs, parameters.parentIdentity(), parameters.socketName());
            if (currentParent == null) {
                handle.setNiagaraAttachmentPose(lastWorldPose);
                return;
            }
            if (!currentWorld.equals(lastRequestedPose)) currentRelative = relativeForWorld(currentWorld, currentParent);
            lastRequestedPose = currentWorld;
            lastWorldPose = worldPose(currentRelative, currentParent);
            handle.setNiagaraAttachmentPose(lastWorldPose);
            return;
        }
        var parent = resolveParent(inputs, parameters.parentIdentity(), parameters.socketName());
        if (parent == null) {
            detachWithoutRestore(handle);
            return;
        }
        boolean previouslyAutoAttached = didAutoAttach;
        if (previouslyAutoAttached) cancel(handle);
        Pose attachWorld = previouslyAutoAttached ? handle.inputWithoutNiagaraAttachment().pose() : currentWorld;
        // Registration preserves the authored relative transform until the host moves the detached component.
        savedRelative = authored.enabled() && attachWorld.equals(registrationPose)
                ? authoredRelative : parts(worldTransform(attachWorld));
        registrationPose = null;
        currentRelative = switchRules(parameters, savedRelative, attachWorld, parent);
        attachedParent = parameters.parentIdentity();
        attachedSocket = parameters.socketName();
        attached = true;
        parentLost = false;
        didAutoAttach = true;
        lastRequestedPose = currentWorld;
        lastWorldPose = worldPose(currentRelative, parent);
        handle.setNiagaraAttachmentPose(lastWorldPose);
    }

    /** Mirrors CancelAutoAttachment(true): restore activation-time relative SRT, then detach KeepRelative. */
    public void complete(EffectHandle handle, boolean autoManageEnabled, boolean autoDestroy) {
        if (autoDestroy) {
            clear(handle);
            return;
        }
        if (autoManageEnabled) cancel(handle);
    }

    public void close(EffectHandle handle, boolean autoManageEnabled, boolean autoDestroy) {
        if (autoDestroy) clear(handle);
        else if (autoManageEnabled) cancel(handle);
        else if (didAutoAttach) {
            handle.restoreNiagaraDetachedPose(lastWorldPose);
            clearState();
        } else handle.setNiagaraAttachmentPose(null);
    }

    public void reset(Pose initialPose) {
        activation = Long.MIN_VALUE;
        didAutoAttach = false;
        attached = false;
        parentLost = false;
        attachedParent = null;
        attachedSocket = "None";
        savedRelative = authoredRelative;
        currentRelative = authoredRelative;
        registrationPose = initialPose;
        lastRequestedPose = initialPose;
        lastWorldPose = initialPose;
    }

    /** Returns the parent's native world bounds only for inactive, detached auto-managed components. */
    public @Nullable NiagaraBounds inactiveParentBounds(EffectHandle handle, boolean inactive) {
        if (!inactive || attached || !enabled(handle)) return null;
        NiagaraAutoAttachmentParameters parameters = parameters(handle);
        if (parameters.parentIdentity() == null) return null;
        Source source = find(handle.niagaraSocketInputs(), parameters.parentIdentity());
        if (source == null) return null;
        return source.worldBounds();
    }

    public boolean attached() { return attached; }

    private boolean enabled(EffectHandle handle) {
        Boolean override = handle.niagaraAutoManageAttachment();
        return override == null ? authored.enabled() : override;
    }

    private NiagaraAutoAttachmentParameters parameters(EffectHandle handle) {
        NiagaraAutoAttachmentParameters override = handle.niagaraAutoAttachmentParameters();
        return override == null ? authored.parameters() : override;
    }

    private void cancel(EffectHandle handle) {
        if (didAutoAttach) handle.restoreNiagaraDetachedPose(pose(savedRelative));
        else handle.setNiagaraAttachmentPose(null);
        clearState();
        lastWorldPose = handle.inputWithoutNiagaraAttachment().pose();
        lastRequestedPose = lastWorldPose;
    }

    private void detachWithoutRestore(EffectHandle handle) {
        // ActivateInternal clears bDidAutoAttach before handling a missing new parent.
        if (didAutoAttach) handle.restoreNiagaraDetachedPose(pose(currentRelative));
        else handle.setNiagaraAttachmentPose(null);
        clearState();
        lastWorldPose = handle.inputWithoutNiagaraAttachment().pose();
        lastRequestedPose = lastWorldPose;
    }

    private void clearState() {
        attached = false;
        didAutoAttach = false;
        parentLost = false;
        attachedParent = null;
        attachedSocket = "None";
    }

    private void clear(EffectHandle handle) {
        handle.setNiagaraAttachmentPose(null);
        clearState();
    }

    private Parts switchRules(NiagaraAutoAttachmentParameters parameters, Parts relative, Pose world,
                              SceneComponentTransform parent) {
        Parts keepWorld = relativeForWorld(world, parent);
        return new Parts(
                choose(parameters.locationRule(), relative.x(), keepWorld.x(), 0),
                choose(parameters.locationRule(), relative.y(), keepWorld.y(), 0),
                choose(parameters.locationRule(), relative.z(), keepWorld.z(), 0),
                choose(parameters.rotationRule(), relative.qx(), keepWorld.qx(), 0),
                choose(parameters.rotationRule(), relative.qy(), keepWorld.qy(), 0),
                choose(parameters.rotationRule(), relative.qz(), keepWorld.qz(), 0),
                choose(parameters.rotationRule(), relative.qw(), keepWorld.qw(), 1),
                choose(parameters.scaleRule(), relative.sx(), keepWorld.sx(), 1),
                choose(parameters.scaleRule(), relative.sy(), keepWorld.sy(), 1),
                choose(parameters.scaleRule(), relative.sz(), keepWorld.sz(), 1));
    }

    private static double choose(Rule rule, double relative, double world, double snap) {
        return switch (rule) { case KeepRelative -> relative; case KeepWorld -> world; case SnapToTarget -> snap; };
    }

    private Parts relativeForWorld(Pose worldPose, SceneComponentTransform parent) {
        var world = worldTransform(worldPose);
        var worldParts = parts(world);
        var parentParts = parts(parent);
        double reciprocalX = safeReciprocal(parentParts.sx());
        double reciprocalY = safeReciprocal(parentParts.sy());
        double reciprocalZ = safeReciprocal(parentParts.sz());
        double scaleX = worldParts.sx() * reciprocalX;
        double scaleY = worldParts.sy() * reciprocalY;
        double scaleZ = worldParts.sz() * reciprocalZ;
        Parts relative;
        if (worldParts.sx() < 0 || worldParts.sy() < 0 || worldParts.sz() < 0
                || parentParts.sx() < 0 || parentParts.sy() < 0 || parentParts.sz() < 0) {
            var localMatrix = new Matrix4d(parent.matrix()).invert().mul(new Matrix4d(world.matrix()));
            var desiredScale = new Vector3d(scaleX, scaleY, scaleZ);
            for (int column = 0; column < 3; column++) {
                double x = localMatrix.get(column, 0), y = localMatrix.get(column, 1), z = localMatrix.get(column, 2);
                double square = x * x + y * y + z * z;
                double inverseLength = square > 0 ? 1 / Math.sqrt(square) : 1;
                double sign = (column == 0 ? desiredScale.x : column == 1 ? desiredScale.y : desiredScale.z) < 0 ? -1 : 1;
                localMatrix.set(column, 0, x * inverseLength * sign)
                        .set(column, 1, y * inverseLength * sign)
                        .set(column, 2, z * inverseLength * sign);
            }
            var local = new SceneComponentTransform();
            local.source(localMatrix);
            Parts decomposed = parts(local);
            relative = new Parts(decomposed.x(), decomposed.y(), decomposed.z(), decomposed.qx(), decomposed.qy(),
                    decomposed.qz(), decomposed.qw(), scaleX, scaleY, scaleZ);
        } else {
            var inverseParent = new Quaterniond(parentParts.qx(), parentParts.qy(), parentParts.qz(), parentParts.qw()).invert();
            var translation = new Vector3d(worldParts.x() - parentParts.x(), worldParts.y() - parentParts.y(), worldParts.z() - parentParts.z());
            inverseParent.transform(translation).mul(reciprocalX, reciprocalY, reciprocalZ);
            var worldRotation = new Quaterniond(worldParts.qx(), worldParts.qy(), worldParts.qz(), worldParts.qw());
            var rotation = new Quaterniond(inverseParent).mul(worldRotation);
            relative = new Parts(translation.x, translation.y, translation.z, rotation.x, rotation.y, rotation.z, rotation.w,
                    scaleX, scaleY, scaleZ);
        }
        return new Parts(absoluteLocation ? worldParts.x() : relative.x(), absoluteLocation ? worldParts.y() : relative.y(),
                absoluteLocation ? worldParts.z() : relative.z(),
                absoluteRotation ? worldParts.qx() : relative.qx(), absoluteRotation ? worldParts.qy() : relative.qy(),
                absoluteRotation ? worldParts.qz() : relative.qz(), absoluteRotation ? worldParts.qw() : relative.qw(),
                absoluteScale ? worldParts.sx() : relative.sx(), absoluteScale ? worldParts.sy() : relative.sy(),
                absoluteScale ? worldParts.sz() : relative.sz());
    }

    private static double safeReciprocal(double value) { return Math.abs(value) > 1.0e-8 ? 1.0 / value : 0; }

    private Pose worldPose(Parts relative, SceneComponentTransform parent) {
        var child = new SceneComponentTransform();
        child.relative(new Vector3d(relative.x(), relative.y(), relative.z()),
                new Quaterniond(relative.qx(), relative.qy(), relative.qz(), relative.qw()),
                new Vector3d(relative.sx(), relative.sy(), relative.sz()),
                absoluteLocation, absoluteRotation, absoluteScale, parent);
        return pose(child);
    }

    private @Nullable SceneComponentTransform resolveParent(NiagaraSocketInputs inputs, @Nullable String identity,
                                                             String socketName) {
        if (identity == null) return identityTransform();
        Source source = find(inputs, identity);
        if (source == null) return null;
        Transform component = source.component();
        if (component == null) return null;
        var parent = sceneTransform(component);
        if (socketName == null || socketName.isBlank() || SourceNames.equal(socketName, "None")) return parent;
        for (var socket : source.sockets()) if (SourceNames.equal(socket.name(), socketName))
            return compose(socket.local(), parent);
        // USceneComponent::GetSocketTransform returns the component transform for a missing/base socket.
        return parent;
    }

    private static @Nullable Source find(NiagaraSocketInputs inputs, String identity) {
        Source source = find(inputs.attachments(), identity);
        if (source != null) return source;
        source = find(inputs.actors().values(), identity);
        return source != null ? source : find(inputs.parameters().values(), identity);
    }

    private static @Nullable Source find(Iterable<Source> sources, String identity) {
        for (Source source : sources) if (SourceNames.equal(source.identity(), identity)) return source;
        return null;
    }

    private static SceneComponentTransform compose(Transform local, SceneComponentTransform parent) {
        var child = new SceneComponentTransform();
        child.relative(new Vector3d(local.x(), local.y(), local.z()),
                new Quaterniond(local.qx(), local.qy(), local.qz(), local.qw()),
                new Vector3d(local.scaleX(), local.scaleY(), local.scaleZ()), parent);
        return child;
    }

    private static SceneComponentTransform sceneTransform(Transform transform) {
        var result = new SceneComponentTransform();
        result.source(new Vector3d(transform.x(), transform.y(), transform.z()),
                new Quaterniond(transform.qx(), transform.qy(), transform.qz(), transform.qw()),
                new Vector3d(transform.scaleX(), transform.scaleY(), transform.scaleZ()));
        return result;
    }

    private static SceneComponentTransform identityTransform() {
        var result = new SceneComponentTransform();
        result.source(new Vector3d(), new Quaterniond(), new Vector3d(1));
        return result;
    }

    private static SceneComponentTransform worldTransform(Pose pose) {
        var result = new SceneComponentTransform();
        result.owner(pose);
        return result;
    }

    private static SceneComponentTransform relativeTransform(RelativeTransform transform) {
        var result = new SceneComponentTransform();
        var parent = identityTransform();
        result.relative(ComponentValue.doubles(ComponentValue.Kind.DOUBLE3, transform.x(), transform.y(), transform.z(), 0),
                ComponentValue.doubles(ComponentValue.Kind.ROTATOR, transform.pitch(), transform.yaw(), transform.roll(), 0),
                ComponentValue.doubles(ComponentValue.Kind.DOUBLE3, transform.scaleX(), transform.scaleY(), transform.scaleZ(), 0),
                transform.absoluteLocation(), transform.absoluteRotation(), transform.absoluteScale(), parent);
        return result;
    }

    private static Parts parts(SceneComponentTransform transform) {
        var matrix = transform.matrix();
        var rotation = transform.rotation(new Quaterniond());
        var scale = transform.scale(new Vector3d());
        return new Parts(matrix.m30(), matrix.m31(), matrix.m32(), rotation.x, rotation.y, rotation.z, rotation.w,
                scale.x, scale.y, scale.z);
    }

    private static Pose pose(Parts parts) {
        return new Pose(new net.minecraft.world.phys.Vec3(parts.x() * .01, parts.z() * .01, parts.y() * .01),
                (float)-parts.qx(), (float)-parts.qz(), (float)-parts.qy(), (float)parts.qw(),
                (float)parts.sx(), (float)parts.sz(), (float)parts.sy());
    }

    private static Pose pose(SceneComponentTransform transform) { return pose(parts(transform)); }
}
