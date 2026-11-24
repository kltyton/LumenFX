package com.kltyton.lumenfx.effect.niagara.component;

import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Builds the component-local draw order used by Niagara's translucent mesh sort. */
public final class NiagaraRendererDrawOrder {
    private NiagaraRendererDrawOrder() { }

    public static Map<String, EmitterDefinition> resolve(@Nullable NiagaraDefinition system, List<EmitterDefinition> definitions) {
        if (system == null) return Map.of();
        var byName = new HashMap<String, EmitterDefinition>();
        for (var definition : definitions) byName.put(definition.name(), definition);
        var ordered = new ArrayList<EmitterDefinition>();
        for (var emitter : system.emitters()) {
            for (var renderer : emitter.renderers()) {
                var definition = byName.get(renderer.name());
                if (definition != null && !(emitter.simulation() instanceof NiagaraDefinition.DisabledSimulation)) ordered.add(definition);
            }
        }
        if (ordered.size() > 1) {
            int depth = (int)(2f * (float)Math.log(ordered.size()));
            order(ordered, 0, ordered.size() - 1, depth);
        }
        for (int rank = 0; rank < ordered.size(); rank++) {
            var definition = ordered.get(rank);
            byName.put(definition.name(), definition.withSortOrder(rank));
        }
        return Map.copyOf(byName);
    }

    // The source's unstable sort gives equal hints a particular submission order.
    private static void order(List<EmitterDefinition> values, int first, int last, int depth) {
        int size = last - first + 1;
        if (size < 2) return;
        if (depth == 0) {
            heapSort(values, first, size);
        } else if (size <= 8) {
            for (int end = last; end > first; end--) {
                int maximum = first;
                for (int index = first + 1; index <= end; index++) {
                    if (values.get(index).sortOrder() > values.get(maximum).sortOrder()) maximum = index;
                }
                Collections.swap(values, maximum, end);
            }
        } else {
            Collections.swap(values, first, first + size / 2);
            int pivot = values.get(first).sortOrder();
            int left = first;
            int right = last + 1;
            while (true) {
                do { left++; } while (left <= last && values.get(left).sortOrder() <= pivot);
                do { right--; } while (right > first && values.get(right).sortOrder() >= pivot);
                if (left > right) break;
                Collections.swap(values, left, right);
            }
            Collections.swap(values, first, right);
            order(values, first, right - 1, depth - 1);
            order(values, left, last, depth - 1);
        }
    }

    private static void heapSort(List<EmitterDefinition> values, int start, int size) {
        for (int parent = (size - 2) / 2; parent >= 0; parent--) sift(values, start, parent, size);
        for (int end = size - 1; end > 0; end--) {
            Collections.swap(values, start, start + end);
            sift(values, start, 0, end);
        }
    }

    private static void sift(List<EmitterDefinition> values, int start, int parent, int size) {
        for (int child = parent * 2 + 1; child < size; child = parent * 2 + 1) {
            if (child + 1 < size && values.get(start + child + 1).sortOrder() >= values.get(start + child).sortOrder()) child++;
            if (values.get(start + child).sortOrder() <= values.get(start + parent).sortOrder()) return;
            Collections.swap(values, start + parent, start + child);
            parent = child;
        }
    }
}
