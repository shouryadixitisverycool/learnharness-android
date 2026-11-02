package ai.learnharness.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RuntimeFilesTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void installMarkerDoesNotHideMissingRuntimeEntrypoints() throws IOException {
        File runtime = temp.newFolder();
        assertTrue(new File(runtime, ".ready").createNewFile());
        assertFalse(RuntimeFiles.isInstalled(runtime));
        assertTrue(new File(runtime, "server.js").createNewFile());
        assertFalse(RuntimeFiles.isInstalled(runtime));
        assertTrue(new File(runtime, "entry.cjs").createNewFile());
        assertTrue(RuntimeFiles.isInstalled(runtime));
    }
}
