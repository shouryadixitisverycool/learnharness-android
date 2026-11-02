package ai.learnharness.android;

import java.util.List;

final class DemoDiagnostics {
    static final int MAX_ENTRIES = 64;
    static final int MAX_CHARS = 512;

    private DemoDiagnostics() {}

    static void append(List<String> entries, String value) {
        if (entries.size() >= MAX_ENTRIES) return;
        int end = Math.min(MAX_CHARS, value.length());
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        entries.add(value.substring(0, end));
    }
}
