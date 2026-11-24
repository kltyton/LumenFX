/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.IScene;
import com.lowdragmc.lowdraglib2.editor.ui.sceneeditor.sceneobject.TransformRef;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/** Shared by every {@link ValueSpace#Custom}: resolving an authored transform reference. */
public final class CustomSpace {

    private CustomSpace() {
    }

    /** A direction given in the picked transform's axes, re-expressed in world axes (in place). */
    public static Vector3f dirToWorld(TransformRef ref, @Nullable IScene scene, Vector3f direction) {
        var transform = ref.getTransform(scene);
        return transform == null ? direction : transform.localToWorldMatrix().transformDirection(direction);
    }

    /** Transform caches are lazy and unsynchronized: build them on the game thread before a parallel update reads them. */
    public static void warm(TransformRef ref, @Nullable IScene scene) {
        var transform = ref.getTransform(scene);
        if (transform != null) {
            transform.localToWorldMatrix();
            transform.worldToLocalMatrix();
        }
    }

}
