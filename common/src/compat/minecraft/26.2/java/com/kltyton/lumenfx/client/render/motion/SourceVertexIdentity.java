package com.kltyton.lumenfx.client.render.motion;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import java.util.Arrays;

/**
 * Stable source-vertex identity for baked model faces.
 *
 * <p>Scopes reference the actual source objects, so IDs stay stable for the lifetime of the cached
 * face/vertex pair and are only reset by {@link #clear()}. No per-vertex scope objects are created;
 * the thread-local stack grows only with nesting.</p>
 */
public final class SourceVertexIdentity {
    private static final Integer[] QUAD_CORNERS = {0, 1, 2, 3};
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private SourceVertexIdentity() {
    }

    public static void push(Object face, Object vertex) {
        State state = STATE.get();
        int index = state.depth;
        state.ensureDepth(index + 1);
        state.faces[index] = face;
        state.vertices[index] = vertex;
        state.quad[index] = false;
        state.quadCorner[index] = 0;
        state.depth = index + 1;
    }

    public static void pushQuad(Object quad) {
        State state = STATE.get();
        int index = state.depth;
        state.ensureDepth(index + 1);
        state.faces[index] = quad;
        state.vertices[index] = null;
        state.quad[index] = true;
        state.quadCorner[index] = 0;
        state.depth = index + 1;
    }

    public static void pop() {
        State state = STATE.get();
        int depth = state.depth;
        if (depth == 0) {
            return;
        }
        int index = depth - 1;
        state.faces[index] = null;
        state.vertices[index] = null;
        state.quad[index] = false;
        state.quadCorner[index] = 0;
        state.depth = index;
    }

    public static int nextVertexId() {
        State state = STATE.get();
        int depth = state.depth;
        if (depth == 0) {
            return 0;
        }
        int index = depth - 1;
        Object face = state.faces[index];
        if (face == null) {
            return 0;
        }
        Object vertex;
        if (state.quad[index]) {
            int corner = state.quadCorner[index] & 3;
            state.quadCorner[index] = (corner + 1) & 3;
            vertex = QUAD_CORNERS[corner];
        } else {
            vertex = state.vertices[index];
        }
        if (vertex == null) {
            return 0;
        }
        return state.idFor(face, vertex);
    }

    public static void clear() {
        State state = STATE.get();
        state.depth = 0;
        state.nextId = 0;
        state.cache.clear();
        Arrays.fill(state.faces, null);
        Arrays.fill(state.vertices, null);
        Arrays.fill(state.quad, false);
        Arrays.fill(state.quadCorner, 0);
    }

    private static final class State {
        private final Reference2ReferenceOpenHashMap<Object, Reference2IntOpenHashMap<Object>> cache =
                new Reference2ReferenceOpenHashMap<>();
        private Object[] faces = new Object[8];
        private Object[] vertices = new Object[8];
        private boolean[] quad = new boolean[8];
        private int[] quadCorner = new int[8];
        private int depth;
        private int nextId;

        private void ensureDepth(int needed) {
            if (needed <= this.faces.length) {
                return;
            }
            int capacity = this.faces.length;
            while (capacity < needed) {
                capacity <<= 1;
            }
            this.faces = Arrays.copyOf(this.faces, capacity);
            this.vertices = Arrays.copyOf(this.vertices, capacity);
            this.quad = Arrays.copyOf(this.quad, capacity);
            this.quadCorner = Arrays.copyOf(this.quadCorner, capacity);
        }

        private int idFor(Object face, Object vertex) {
            Reference2IntOpenHashMap<Object> byVertex = this.cache.get(face);
            if (byVertex == null) {
                byVertex = new Reference2IntOpenHashMap<>();
                byVertex.defaultReturnValue(0);
                this.cache.put(face, byVertex);
            }
            int existing = byVertex.getInt(vertex);
            if (existing != 0) {
                return existing;
            }
            int id = Math.incrementExact(this.nextId);
            this.nextId = id;
            byVertex.put(vertex, id);
            return id;
        }
    }
}
