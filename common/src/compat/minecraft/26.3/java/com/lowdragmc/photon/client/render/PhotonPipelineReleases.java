/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import com.kltyton.lumenfx.client.graphics.RenderPearlCompat;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

/** Releases compiled RenderPearl pipelines from both custom and host caches when retired. */
final class PhotonPipelineReleases {
    private PhotonPipelineReleases() {}

    static void release(RenderPipeline pipeline) {
        RenderPearlCompat.releasePipeline(pipeline);
    }
}
