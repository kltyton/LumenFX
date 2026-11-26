package com.kltyton.lumenfx.internal.photon.core.editor.ui.sceneeditor.sceneobject;

import com.kltyton.lumenfx.internal.photon.core.math.Transform;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public interface ISceneObject {
    default UUID id() {
        return transform().id();
    }

    Transform transform();

    default void setTransform(Transform transform) {
        transform().set(transform);
    }

    @Nullable
    IScene getScene();

    void setSceneInternal(@Nullable IScene scene);

    default void setScene(@Nullable IScene scene) {
        if (getScene() != scene) {
            if (getScene() != null) {
                getScene().removeSceneObjectInternal(this);
            }
            setSceneInternal(scene);
            if (scene == null) {
                onDestroy();
            } else {
                scene.addSceneObjectInternal(this);
                awake();
            }
            children().forEach(child -> child.setScene(scene));
            if (scene != null) {
                transform().rebuildChildOrder();
            }
        }
    }

    default void destroy() {
        setScene(null);
    }

    default void onDestroy() {
        transform().destroy();
    }

    default List<ISceneObject> children() {
        return transform().children().stream().map(Transform::sceneObject).toList();
    }

    default List<ISceneObject> getAllFlatChildren() {
        List<ISceneObject> children = children();
        return children.stream().flatMap(child -> child.getAllFlatChildren().stream()).toList();
    }

    default void onTransformChanged() { }

    default void onChildChanged() { }

    default void onParentChanged() { }

    default void updateTick() { }

    default void updateFrame(float partialTicks) { }

    default void executeAll(Consumer<ISceneObject> consumer) {
        consumer.accept(this);
        children().forEach(child -> child.executeAll(consumer));
    }

    default void executeAll(Consumer<ISceneObject> consumer, @Nullable Consumer<ISceneObject> before, @Nullable Consumer<ISceneObject> after) {
        if (before != null) before.accept(this);
        consumer.accept(this);
        children().forEach(child -> child.executeAll(consumer));
        if (after != null) after.accept(this);
    }

    default void awake() {
        transform().awake();
    }
}
