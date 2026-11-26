package com.kltyton.lumenfx.internal.photon.core.math;

import com.kltyton.lumenfx.internal.photon.core.editor.ui.sceneeditor.sceneobject.ISceneObject;
import com.kltyton.lumenfx.internal.photon.core.utils.LDLibExtraCodecs;
import com.lowdragmc.photon.client.fx.instance.InstanceIds;
import com.lowdragmc.photon.client.fx.instance.InstanceTags;
import com.lowdragmc.photon.utils.PhotonValueSerializable;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Accessors(fluent = true)
public final class Transform implements PhotonValueSerializable {
    @Getter
    private UUID id = UUID.randomUUID();
    @Getter
    private Vector3f localPosition = new Vector3f();
    @Getter
    private Quaternionf localRotation = new Quaternionf();
    @Getter
    private Vector3f localScale = new Vector3f(1f, 1f, 1f);
    @Nullable
    private UUID parentId;
    private List<UUID> childIds = new ArrayList<>();

    @Getter @Nullable
    private Transform parent;
    private final List<Transform> children = new ArrayList<>();
    @Getter
    private final ISceneObject sceneObject;
    @Getter
    private boolean isValid = true;

    public Transform(ISceneObject sceneObject) {
        this.sceneObject = sceneObject;
    }

    @Override
    public void serialize(ValueOutput output) {
        output.store(InstanceTags.ID, LDLibExtraCodecs.UUID, id);
        output.store("localPosition", ExtraCodecs.VECTOR3F, localPosition);
        output.store("localRotation", ExtraCodecs.QUATERNIONF, localRotation);
        output.store("localScale", ExtraCodecs.VECTOR3F, localScale);
        if (parentId != null) output.store(InstanceTags.PARENT, LDLibExtraCodecs.UUID, parentId);
        output.store(InstanceTags.CHILDREN, LDLibExtraCodecs.UUID.listOf(), childIds);
    }

    @Override
    public void deserialize(ValueInput input) {
        id = input.read(InstanceTags.ID, LDLibExtraCodecs.UUID).orElse(id);
        localPosition = input.read("localPosition", ExtraCodecs.VECTOR3F).map(Vector3f::new).orElse(localPosition);
        localRotation = input.read("localRotation", ExtraCodecs.QUATERNIONF).map(Quaternionf::new).orElse(localRotation);
        localScale = input.read("localScale", ExtraCodecs.VECTOR3F).map(Vector3f::new).orElse(localScale);
        // Older Photon projects use "_NULL_" on a root, so read the parent as a string.
        parentId = InstanceIds.parse(input.getString(InstanceTags.PARENT)
                .or(() -> input.getString(InstanceTags.PREVIOUS_PARENT)).orElse(null));
        childIds = new ArrayList<>(input.read(InstanceTags.CHILDREN, LDLibExtraCodecs.UUID.listOf())
                .or(() -> input.read(InstanceTags.PREVIOUS_CHILDREN, LDLibExtraCodecs.UUID.listOf()))
                .orElseGet(List::of));
    }

    public List<Transform> children() {
        return Collections.unmodifiableList(children);
    }

    public void parent(@Nullable Transform newParent) {
        parent(newParent, true);
    }

    public void parent(@Nullable Transform newParent, boolean worldPositionStays) {
        if (newParent == this || newParent == parent) return;
        if (newParent != null && newParent.isInheritedParent(this)) return;
        Vector3f worldPos = worldPositionStays ? position() : null;
        Quaternionf worldRot = worldPositionStays ? rotation() : null;
        Vector3f worldScale = worldPositionStays ? scale() : null;
        if (parent != null) {
            parent.children.remove(this);
            parent.childIds.remove(this.id);
            parent.sceneObject.onChildChanged();
        }
        parent = newParent;
        parentId = newParent == null ? null : newParent.id;
        if (parent != null) {
            parent.children.add(this);
            if (!parent.childIds.contains(this.id)) parent.childIds.add(this.id);
            parent.sceneObject.onChildChanged();
        }
        if (worldPositionStays) {
            position(worldPos);
            rotation(worldRot);
            scale(worldScale);
        }
        sceneObject.onParentChanged();
    }

    public boolean isInheritedParent(Transform ancestor) {
        Transform cur = this.parent;
        while (cur != null) {
            if (cur == ancestor) return true;
            cur = cur.parent;
        }
        return false;
    }

    public int getSiblingIndex() {
        return parent == null ? -1 : parent.children.indexOf(this);
    }

    public void setSiblingIndex(int index) {
        if (parent == null) return;
        parent.children.remove(this);
        int clamped = Math.max(0, Math.min(index, parent.children.size()));
        parent.children.add(clamped, this);
        parent.childIds.clear();
        for (var child : parent.children) parent.childIds.add(child.id);
    }

    public void identity() {
        position(new Vector3f());
        rotation(new Quaternionf());
        scale(new Vector3f(1f, 1f, 1f));
    }

    public void identityLocal() {
        localPosition.set(0f, 0f, 0f);
        localRotation.identity();
        localScale.set(1f, 1f, 1f);
        sceneObject.onTransformChanged();
    }

    public Matrix4f localTransformMatrix() {
        return new Matrix4f().translationRotateScale(localPosition, localRotation, localScale);
    }

    public Matrix4f localToWorldMatrix() {
        if (parent == null) return localTransformMatrix();
        return parent.localToWorldMatrix().mul(localTransformMatrix());
    }

    public Matrix4f worldToLocalMatrix() {
        return localToWorldMatrix().invert();
    }

    public Transform set(Transform other) {
        return set(other, true);
    }

    public Transform set(Transform other, boolean local) {
        if (local) {
            localPosition.set(other.localPosition);
            localRotation.set(other.localRotation);
            localScale.set(other.localScale);
        } else {
            position(other.position());
            rotation(other.rotation());
            scale(other.scale());
        }
        sceneObject.onTransformChanged();
        return this;
    }

    public Vector3f position() {
        if (parent == null) return new Vector3f(localPosition);
        return parent.localToWorldMatrix().transformPosition(new Vector3f(localPosition));
    }

    public void position(Vector3f worldPosition) {
        if (parent == null) {
            localPosition.set(worldPosition);
        } else {
            localPosition.set(parent.worldToLocalMatrix().transformPosition(new Vector3f(worldPosition)));
        }
        sceneObject.onTransformChanged();
    }

    public void localPosition(Vector3f pos) {
        localPosition.set(pos);
        sceneObject.onTransformChanged();
    }

    public Quaternionf rotation() {
        if (parent == null) return new Quaternionf(localRotation);
        return parent.rotation().mul(localRotation);
    }

    public void rotation(Quaternionf worldRotation) {
        if (parent == null) {
            localRotation.set(worldRotation);
        } else {
            localRotation.set(parent.rotation().invert().mul(worldRotation));
        }
        sceneObject.onTransformChanged();
    }

    public void localRotation(Quaternionf rot) {
        localRotation.set(rot);
        sceneObject.onTransformChanged();
    }

    public Vector3f scale() {
        if (parent == null) return new Vector3f(localScale);
        return parent.scale().mul(localScale);
    }

    public void scale(Vector3f worldScale) {
        if (parent == null) {
            localScale.set(worldScale);
        } else {
            var ps = parent.scale();
            localScale.set(
                    ps.x == 0f ? 0f : worldScale.x / ps.x,
                    ps.y == 0f ? 0f : worldScale.y / ps.y,
                    ps.z == 0f ? 0f : worldScale.z / ps.z);
        }
        sceneObject.onTransformChanged();
    }

    public void localScale(Vector3f scale) {
        localScale.set(scale);
        sceneObject.onTransformChanged();
    }

    public Vector3f right() { return rotation().transform(new Vector3f(1f, 0f, 0f)); }
    public Vector3f left() { return rotation().transform(new Vector3f(-1f, 0f, 0f)); }
    public Vector3f up() { return rotation().transform(new Vector3f(0f, 1f, 0f)); }
    public Vector3f down() { return rotation().transform(new Vector3f(0f, -1f, 0f)); }
    public Vector3f forward() { return rotation().transform(new Vector3f(0f, 0f, 1f)); }
    public Vector3f back() { return rotation().transform(new Vector3f(0f, 0f, -1f)); }

    public void lookAt(Vector3f target) { lookAt(target, new Vector3f(0f, 1f, 0f)); }
    public void lookAt(Vector3f target, Vector3f up) {
        Vector3f dir = new Vector3f(target).sub(position());
        if (dir.lengthSquared() > 1e-8f) {
            rotation(new Quaternionf().lookAlong(dir.normalize(), up).invert());
        }
    }

    public void translate(Vector3f delta, float factor) {
        position(position().fma(factor, delta));
    }

    public void translateLocal(Vector3f delta, float factor) {
        localPosition.fma(factor, delta);
        sceneObject.onTransformChanged();
    }

    public void rotate(Vector3f axis, float angle) {
        rotation(new Quaternionf().fromAxisAngleRad(axis, angle).mul(rotation()));
    }

    public void rotateLocal(Vector3f euler) {
        localRotation.rotateXYZ(euler.x, euler.y, euler.z);
        sceneObject.onTransformChanged();
    }

    public void destroy() {
        isValid = false;
        if (parent != null) {
            parent.children.remove(this);
            parent = null;
        }
        for (var child : List.copyOf(children)) {
            child.parent = null;
        }
        children.clear();
    }

    public void awake() {
        var scene = sceneObject.getScene();
        if (scene != null && parentId != null && parent == null) {
            var parentObj = scene.getSceneObject(parentId);
            if (parentObj != null) {
                parent(parentObj.transform(), false);
            }
        }
    }

    public void rebuildChildOrder() {
        if (!childIds.isEmpty() && children.size() > 1) {
            children.sort(Comparator.comparingInt(c -> {
                int idx = childIds.indexOf(c.id);
                return idx < 0 ? Integer.MAX_VALUE : idx;
            }));
        }
    }

    public void _setInternalID(UUID id) { this.id = id; }
    public void _refreshInternalID() { this.id = UUID.randomUUID(); }
    public void _setInternalParentID(@Nullable UUID parentId) { this.parentId = parentId; }
    public @Nullable UUID _getInternalParentID() { return parentId; }
    public void _setInternalChildID(List<UUID> childIds) { this.childIds = new ArrayList<>(childIds); }
    public List<UUID> _getInternalChildID() { return childIds; }

    public void copyTransformFrom(Transform source, boolean copyLocal, boolean copyIds) {
        set(source, copyLocal);
        if (copyIds) {
            this.id = source.id;
            this.parentId = source.parentId;
            this.childIds = new ArrayList<>(source.childIds);
        }
    }
}
