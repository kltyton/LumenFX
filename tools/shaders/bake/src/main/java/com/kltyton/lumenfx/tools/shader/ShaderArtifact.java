package com.kltyton.lumenfx.tools.shader;

import com.google.gson.JsonObject;

record ShaderArtifact(String path, byte[] bytes, String sha256) {
    JsonObject toJson() {
        final JsonObject json = new JsonObject();
        json.addProperty("path", this.path);
        json.addProperty("size", this.bytes.length);
        json.addProperty("sha256", this.sha256);
        return json;
    }
}
