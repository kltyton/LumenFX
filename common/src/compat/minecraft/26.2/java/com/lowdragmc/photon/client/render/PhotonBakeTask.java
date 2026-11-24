/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import java.util.List;

/** Geometry generation registered during extraction and run when the view prepares its frame. */
@FunctionalInterface
public interface PhotonBakeTask {
    void bake(List<PhotonWorldRenderState.DrawJob> out);
}
