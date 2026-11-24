package com.kltyton.lumenfx.effect.niagara.parameter;

import java.util.Comparator;

/** Native name identity folds ASCII letters and preserves every non-ASCII UTF-16 code unit. */
public final class SourceNames {
    public static final Comparator<String> ASCII_CASE_INSENSITIVE = SourceNames::compare;
    private SourceNames() { }

    public static boolean equal(String left, String right) { return compare(left, right) == 0; }

    /** Editor FName lexical ordering compares the base text before the internal numeric suffix. */
    public static int lexicalCompare(String left, String right) {
        int a = numberStart(left), b = numberStart(right);
        String aText = a < 0 ? left : left.substring(0, a - 1), bText = b < 0 ? right : right.substring(0, b - 1);
        int names = compare(aText, bText);
        if (names != 0) return names;
        long aNumber = a < 0 ? 0 : Long.parseLong(left.substring(a)) + 1;
        long bNumber = b < 0 ? 0 : Long.parseLong(right.substring(b)) + 1;
        return Long.compare(aNumber, bNumber);
    }

    private static int numberStart(String name) {
        int start = name.length();
        while (start > 0 && name.charAt(start - 1) >= '0' && name.charAt(start - 1) <= '9') start--;
        int digits = name.length() - start;
        if (digits == 0 || start == 0 || name.charAt(start - 1) != '_' || digits > 10
                || digits > 1 && name.charAt(start) == '0') return -1;
        return Long.parseLong(name.substring(start)) < Integer.MAX_VALUE ? start : -1;
    }

    public static String canonical(String name) {
        char[] folded = null;
        for (int index = 0; index < name.length(); index++) {
            char value = name.charAt(index);
            if (value < 'A' || value > 'Z') continue;
            if (folded == null) folded = name.toCharArray();
            folded[index] = (char)(value + ('a' - 'A'));
        }
        return folded == null ? name : new String(folded);
    }

    private static int compare(String left, String right) {
        int count = Math.min(left.length(), right.length());
        for (int index = 0; index < count; index++) {
            char a = left.charAt(index), b = right.charAt(index);
            if (a == b) continue;
            if ((a | b) < 128) {
                if (a >= 'A' && a <= 'Z') a += 'a' - 'A';
                if (b >= 'A' && b <= 'Z') b += 'a' - 'A';
            }
            if (a != b) return a - b;
        }
        return left.length() - right.length();
    }
}

