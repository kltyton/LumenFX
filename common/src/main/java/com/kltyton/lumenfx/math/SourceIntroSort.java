package com.kltyton.lumenfx.math;

import java.util.Collections;
import java.util.List;
import java.util.function.BiPredicate;

/** Source unstable sort, including the comparison order used by Niagara's random event predicate. */
public final class SourceIntroSort {
    private SourceIntroSort() { }

    public static <T> void sort(List<T> values, BiPredicate<T, T> less) {
        if (values.size() < 2) return;
        sort(values, 0, values.size() - 1, (int) ((float) Math.log(values.size()) * 2), less);
    }

    private static <T> void sort(List<T> values, int low, int high, int depth, BiPredicate<T, T> less) {
        while (low < high) {
            int count = high - low + 1;
            if (depth == 0) {
                heapSort(values, low, count, less);
                return;
            }
            if (count <= 8) {
                for (int end = high; end > low; end--) {
                    int greatest = low;
                    for (int item = low + 1; item <= end; item++) {
                        if (less.test(values.get(greatest), values.get(item))) greatest = item;
                    }
                    Collections.swap(values, greatest, end);
                }
                return;
            }

            Collections.swap(values, low, low + count / 2);
            T pivot = values.get(low);
            int left = low;
            int right = high + 1;
            while (true) {
                do { left++; } while (left <= high && !less.test(pivot, values.get(left)));
                do { right--; } while (right > low && !less.test(values.get(right), pivot));
                if (left > right) break;
                Collections.swap(values, left, right);
            }
            Collections.swap(values, low, right);
            depth--;
            if (right - 1 - low >= high - left) {
                sort(values, left, high, depth, less);
                high = right - 1;
            } else {
                sort(values, low, right - 1, depth, less);
                low = left;
            }
        }
    }

    private static <T> void heapSort(List<T> values, int base, int count, BiPredicate<T, T> less) {
        for (int parent = (count - 2) / 2; parent >= 0; parent--) sift(values, base, parent, count, less);
        for (int end = count - 1; end > 0; end--) {
            Collections.swap(values, base, base + end);
            sift(values, base, 0, end, less);
        }
    }

    private static <T> void sift(List<T> values, int base, int index, int count, BiPredicate<T, T> less) {
        while (index < count / 2) {
            int child = index * 2 + 1;
            if (child + 1 < count && !less.test(values.get(base + child + 1), values.get(base + child))) child++;
            if (!less.test(values.get(base + index), values.get(base + child))) break;
            Collections.swap(values, base + index, base + child);
            index = child;
        }
    }
}
