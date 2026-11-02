package ai.learnharness.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class DemoDiagnosticsTest {
    @Test public void boundsDiagnosticCountAndTextWithoutSplittingSurrogates() {
        List<String> entries = new ArrayList<>();
        String longText = "x".repeat(DemoDiagnostics.MAX_CHARS - 1) + "\uD83D\uDE80";
        for (int i = 0; i < DemoDiagnostics.MAX_ENTRIES + 10; i++) DemoDiagnostics.append(entries, longText);

        assertEquals(DemoDiagnostics.MAX_ENTRIES, entries.size());
        assertEquals(DemoDiagnostics.MAX_CHARS - 1, entries.get(0).length());
        assertFalse(Character.isHighSurrogate(entries.get(0).charAt(entries.get(0).length() - 1)));
    }
}
