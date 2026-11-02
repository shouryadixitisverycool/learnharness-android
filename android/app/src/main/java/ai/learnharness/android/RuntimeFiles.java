package ai.learnharness.android;

import java.io.File;

final class RuntimeFiles {
    private RuntimeFiles() {}

    static boolean isInstalled(File directory) {
        return new File(directory, ".ready").isFile()
            && new File(directory, "server.js").isFile()
            && new File(directory, "entry.cjs").isFile();
    }
}
