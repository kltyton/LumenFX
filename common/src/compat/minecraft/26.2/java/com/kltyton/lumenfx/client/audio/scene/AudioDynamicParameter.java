package com.kltyton.lumenfx.client.audio.scene;

/** Audio-device step interpolation used by Sound Class and Sound Mix overrides. */
final class AudioDynamicParameter {
    private float value, start, target, elapsed, duration, delta;

    AudioDynamicParameter(float value) { this.value = start = target = value; }

    void set(float target, float duration) {
        if (this.target == target && this.duration == duration) return;
        this.target = target;
        if (duration > 0) {
            delta = target - value;
            start = value;
            elapsed = 0;
            this.duration = duration;
        } else {
            start = value = target;
            delta = this.duration = 0;
        }
    }

    void advance(float seconds) {
        if (duration <= 0) return;
        float fraction = elapsed / duration;
        if (fraction < 1) value = delta * fraction + start;
        else {
            value = start + delta;
            duration = 0;
        }
        elapsed += seconds;
    }

    float value() { return value; }
    boolean done() { return elapsed >= duration; }
}
