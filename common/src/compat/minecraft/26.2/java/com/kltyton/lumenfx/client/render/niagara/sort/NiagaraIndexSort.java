// Copyright Epic Games, Inc. All Rights Reserved.
package com.kltyton.lumenfx.client.render.niagara.sort;

import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntComparator;

/** CPU renderer index sorting with the pinned engine's partitions, equal-key swaps and heap fallback. */
public final class NiagaraIndexSort {
    private final int[] stack = new int[32 * 3];
    private int[] signedKeys = IntArrays.EMPTY_ARRAY;

    /**
     * Sort the surviving particle rows as SortAndCullIndices does.
     * Rows enter in dataset order, which also supplies the tie order for the stable radix branch.
     */
    public void sortParticles(int[] rows, int count, int[] keys, int radixThreshold) {
        if (count < 2) return;
        if (radixThreshold == -1 || count <= radixThreshold) {
            sort(rows, 0, count, (left, right) -> Integer.compareUnsigned(keys[left], keys[right]));
            return;
        }
        signedKeys = IntArrays.ensureCapacity(signedKeys, keys.length, 0);
        for (int index = 0; index < count; index++) {
            int row = rows[index];
            signedKeys[row] = keys[row] ^ Integer.MIN_VALUE;
        }
        IntArrays.radixSortIndirect(rows, signedKeys, 0, count, true);
    }

    /** Distance and view depth use the native strictly-positive descending key permutation. */
    public static int viewKey(float sourceValue) {
        return ~(Float.floatToRawIntBits(sourceValue) | Integer.MIN_VALUE);
    }

    public void sort(int[] values, int first, int end, IntComparator predicate) {
        int count = end - first;
        if (count < 2) return;
        int top = 0;
        stack[0] = first; stack[1] = end - 1; stack[2] = (int)((float)Math.log((float)count) * 2f);
        while (top >= 0) {
            int base = top-- * 3;
            int low = stack[base], high = stack[base + 1], depth = stack[base + 2];
            while (true) {
                count = high - low + 1;
                if (depth == 0) {
                    heapSort(values, low, count, predicate);
                    break;
                }
                if (count <= 8) {
                    while (high > low) {
                        int maximum = low;
                        for (int item = low + 1; item <= high; item++)
                            if (predicate.compare(values[maximum], values[item]) < 0) maximum = item;
                        swap(values, maximum, high--);
                    }
                    break;
                }
                swap(values, low + count / 2, low);
                int innerLow = low, innerHigh = high + 1;
                while (true) {
                    do { innerLow++; }
                    while (innerLow <= high && !(predicate.compare(values[low], values[innerLow]) < 0));
                    do { innerHigh--; }
                    while (innerHigh > low && !(predicate.compare(values[innerHigh], values[low]) < 0));
                    if (innerLow > innerHigh) break;
                    swap(values, innerLow, innerHigh);
                }
                swap(values, low, innerHigh);
                depth--;
                if (innerHigh - 1 - low >= high - innerLow) {
                    if (low + 1 < innerHigh) {
                        base = ++top * 3;
                        stack[base] = low; stack[base + 1] = innerHigh - 1; stack[base + 2] = depth;
                    }
                    if (high > innerLow) { low = innerLow; continue; }
                } else {
                    if (high > innerLow) {
                        base = ++top * 3;
                        stack[base] = innerLow; stack[base + 1] = high; stack[base + 2] = depth;
                    }
                    if (low + 1 < innerHigh) { high = innerHigh - 1; continue; }
                }
                break;
            }
        }
    }

    private static void heapSort(int[] values, int first, int count, IntComparator predicate) {
        for (int parent = (count - 2) / 2; parent >= 0; parent--) siftDown(values, first, parent, count, predicate);
        for (int index = count - 1; index > 0; index--) {
            swap(values, first, first + index);
            siftDown(values, first, 0, index, predicate);
        }
    }

    private static void siftDown(int[] values, int first, int index, int count, IntComparator predicate) {
        while (index * 2 + 1 < count) {
            int child = index * 2 + 1, right = child + 1;
            if (right < count && !(predicate.compare(values[first + right], values[first + child]) < 0)) child = right;
            if (!(predicate.compare(values[first + index], values[first + child]) < 0)) return;
            swap(values, first + index, first + child);
            index = child;
        }
    }

    private static void swap(int[] values, int first, int second) {
        int value = values[first]; values[first] = values[second]; values[second] = value;
    }
}
