package com.kltyton.lumenfx.client.render.mesh.nanite;

/** Adjusts the requested geometric detail using completed streaming feedback. */
final class NaniteStreamingQuality {
    private int overBudget, underBudget;
    private float scale = 1;

    float scale() { return scale; }

    void update(long requestedPages, int poolPages, float low, float high, float minimumQuality) {
        if (requestedPages < 0) return;
        float percentage = poolPages == 0 ? 0 : requestedPages / (float) poolPages * 100.0f;
        low = Math.clamp(low, 10, 100);
        high = Math.clamp(high, low, 100);
        overBudget = percentage > high ? Math.min(overBudget + 1, 2) : 0;
        underBudget = percentage < low ? Math.min(underBudget + 1, 30) : 0;
        if (overBudget >= 2) scale *= 0.97f;
        else if (underBudget >= 30) scale *= 1.01f;
        scale = Math.clamp(scale, Math.clamp(minimumQuality, 0.1f, 1), 1);
    }

    void reset() {
        overBudget = underBudget = 0;
        scale = 1;
    }
}
