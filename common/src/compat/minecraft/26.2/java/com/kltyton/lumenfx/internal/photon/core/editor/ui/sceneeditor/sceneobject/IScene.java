package com.kltyton.lumenfx.internal.photon.core.editor.ui.sceneeditor.sceneobject;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.UUID;

public interface IScene {
    @Nullable
    ISceneObject getSceneObject(UUID id);

    Collection<ISceneObject> getAllSceneObjects();

    default void addSceneObject(ISceneObject sceneObject) {
        sceneObject.setScene(this);
    }

    default void removeSceneObject(ISceneObject sceneObject) {
        sceneObject.setScene(null);
    }

    void addSceneObjectInternal(ISceneObject sceneObject);

    void removeSceneObjectInternal(ISceneObject sceneObject);
}
