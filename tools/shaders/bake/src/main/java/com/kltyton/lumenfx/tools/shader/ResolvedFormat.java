package com.kltyton.lumenfx.tools.shader;

import com.google.gson.JsonObject;

record ResolvedFormat(
        String pf,
        String vk,
        String gpuFormat,
        String dxcImageFormat,
        boolean runtimeResolved,
        String rule
) {
    JsonObject toJson() {
        final JsonObject json = new JsonObject();
        json.addProperty("formatManifest", this.pf);
        json.addProperty("gpuFormat", this.gpuFormat);
        json.addProperty("vkFormat", this.vk);
        if (this.dxcImageFormat != null) {
            json.addProperty("dxcImageFormat", this.dxcImageFormat);
        }
        json.addProperty("runtimeResolved", this.runtimeResolved);
        if (this.rule != null) {
            json.addProperty("rule", this.rule);
        }
        return json;
    }
}
