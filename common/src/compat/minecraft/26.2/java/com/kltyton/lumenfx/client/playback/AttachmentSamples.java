package com.kltyton.lumenfx.client.playback;

/** Latches the source sample for a whole simulation step, including its substeps. */
public final class AttachmentSamples {
    private long sequence = Long.MIN_VALUE;
    private double step = Double.NaN;
    private boolean fresh;

    public boolean permitsEmission(long sampleSequence, boolean renderTracked, double step) {
        if (this.step != step || sequence != sampleSequence) {
            fresh = sequence != sampleSequence;
            sequence = sampleSequence;
            this.step = step;
        }
        return !renderTracked || fresh;
    }

    public void reset() {
        sequence = Long.MIN_VALUE;
        step = Double.NaN;
        fresh = false;
    }
}
