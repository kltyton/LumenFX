package com.kltyton.lumenfx.client.render.motion;

import java.util.List;

/** Immutable current/previous GPU-vertex streams and ordered run metadata for one submission. */
public final class SubmissionMotion {
    public enum Status {
        VALID,
        NEW_SUBMISSION,
        MISSING_PREVIOUS,
        TOPOLOGY_COUNT_MISMATCH,
        TOPOLOGY_UNRESOLVED,
        RUN_STRUCTURE_MISMATCH,
        DRAW_IDENTITY_MISMATCH,
        DRAW_COUNT_MISMATCH,
        DRAW_COUNT_UNVERIFIED
    }

    private final SubmitKey key;
    private final float[] current;
    private final int currentFloatCount;
    private final float[] previous;
    private final int previousFloatCount;
    private final boolean previousPresent;
    private final boolean previousValid;
    private final Status status;
    private final List<RunMotion> runs;
    private final List<RunMotion> previousRuns;

    SubmissionMotion(
            SubmitKey key,
            float[] current,
            int currentFloatCount,
            float[] previous,
            int previousFloatCount,
            boolean previousPresent,
            boolean previousValid,
            Status status,
            List<RunMotion> runs,
            List<RunMotion> previousRuns) {
        this.key = key;
        this.current = current;
        this.currentFloatCount = currentFloatCount;
        this.previous = previous;
        this.previousFloatCount = previousFloatCount;
        this.previousPresent = previousPresent;
        this.previousValid = previousValid;
        this.status = status;
        this.runs = runs;
        this.previousRuns = previousRuns;
    }

    public SubmitKey key() {
        return this.key;
    }

    public int currentVertexCount() {
        return this.currentFloatCount / 3;
    }

    public int currentFloatCount() {
        return this.currentFloatCount;
    }

    public int previousVertexCount() {
        return this.previousFloatCount / 3;
    }

    public int previousFloatCount() {
        return this.previousFloatCount;
    }

    /** A real previous stream exists for the immediately preceding frame. */
    public boolean previousPresent() {
        return this.previousPresent;
    }

    /** The previous stream is proven pairable with the current stream. */
    public boolean previousValid() {
        return this.previousValid;
    }

    public Status status() {
        return this.status;
    }

    public List<RunMotion> runs() {
        return this.runs;
    }

    public List<RunMotion> previousRuns() {
        return this.previousRuns;
    }

    public float currentX(int vertex) {
        return this.current[this.currentIndex(vertex)];
    }

    public float currentY(int vertex) {
        return this.current[this.currentIndex(vertex) + 1];
    }

    public float currentZ(int vertex) {
        return this.current[this.currentIndex(vertex) + 2];
    }

    public float previousX(int vertex) {
        return this.previous[this.previousIndex(vertex)];
    }

    public float previousY(int vertex) {
        return this.previous[this.previousIndex(vertex) + 1];
    }

    public float previousZ(int vertex) {
        return this.previous[this.previousIndex(vertex) + 2];
    }

    public void copyCurrentTo(float[] destination, int offset) {
        System.arraycopy(this.current, 0, destination, offset, this.currentFloatCount);
    }

    /** Copies the real previous stream; refuses only when no previous stream exists. */
    public void copyPreviousTo(float[] destination, int offset) {
        if (!this.previousPresent) {
            throw new IllegalStateException("No previous stream for " + this.key + " (" + this.status + ")");
        }
        System.arraycopy(this.previous, 0, destination, offset, this.previousFloatCount);
    }

    private int currentIndex(int vertex) {
        if (vertex < 0 || vertex >= this.currentFloatCount / 3) {
            throw new IndexOutOfBoundsException("vertex " + vertex);
        }
        return vertex * 3;
    }

    private int previousIndex(int vertex) {
        if (!this.previousPresent) {
            throw new IllegalStateException("No previous stream for " + this.key + " (" + this.status + ")");
        }
        if (vertex < 0 || vertex >= this.previousFloatCount / 3) {
            throw new IndexOutOfBoundsException("vertex " + vertex);
        }
        return vertex * 3;
    }
}
