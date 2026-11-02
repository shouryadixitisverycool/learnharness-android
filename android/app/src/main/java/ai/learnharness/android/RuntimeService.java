package ai.learnharness.android;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.Map;
import java.util.zip.*;
import org.json.*;

public class RuntimeService extends Service {
    static volatile String origin, token, failure;
    static volatile boolean ready;
    private static volatile boolean activityVisible;
    private static volatile RuntimeService instance;
    private Process process;
    private AndroidDemoRunner demos;
    private volatile boolean stopping;
    private final Handler idleHandler = new Handler(Looper.getMainLooper());
    private final Runnable idleCheck = this::checkIdleAndStop;
    private static final long IDLE_GRACE_MS = 10 * 60 * 1000L;
    private static final long ACTIVE_RECHECK_MS = 60 * 1000L;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        failure = null;
        ready = false;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("runtime", "Learning sessions", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "runtime").setSmallIcon(ai.learnharness.android.R.drawable.ic_stat_learnharness)
            .setContentTitle("LearnHarness").setContentText("Your learning server is running on this phone")
            .setContentIntent(open).setOngoing(true).build());
        new Thread(this::startRuntime, "learnharness-runtime").start();
    }

    static void requestIdleStop() {
        activityVisible = false;
        RuntimeService service = instance;
        if (service != null) {
            service.idleHandler.removeCallbacks(service.idleCheck);
            service.idleHandler.postDelayed(service.idleCheck, IDLE_GRACE_MS);
        }
    }

    static void cancelIdleStop() {
        activityVisible = true;
        RuntimeService service = instance;
        if (service != null) service.idleHandler.removeCallbacks(service.idleCheck);
    }

    private void checkIdleAndStop() {
        if (stopping || !ready) return;
        new Thread(() -> {
            boolean idle = false;
            try {
                JSONObject chats = getJson("/api/chats");
                JSONArray rows = chats.optJSONArray("chats");
                idle = rows != null;
                for (int i = 0; idle && i < rows.length(); i++) idle = !rows.getJSONObject(i).optBoolean("busy");
                JSONObject activity = getJson("/api/activity");
                idle &= activity.optJSONArray("jobs") != null && activity.optJSONArray("jobs").length() == 0;
                idle &= activity.optJSONArray("extractions") != null && activity.optJSONArray("extractions").length() == 0;
            } catch (Exception ignored) { /* Stay alive if the status check is uncertain. */ }
            if (idle && !activityVisible) stopSelf();
            else idleHandler.postDelayed(idleCheck, ACTIVE_RECHECK_MS);
        }, "learnharness-idle-check").start();
    }

    private JSONObject getJson(String path) throws IOException, org.json.JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(origin + path).openConnection();
        try {
            connection.setRequestProperty("Cookie", "lh_android=" + token);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            if (connection.getResponseCode() != 200) throw new IOException("status unavailable");
            try (InputStream input = connection.getInputStream()) {
                return new JSONObject(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }

    private void startRuntime() {
        try {
            File server = installServer();
            File profile = new File(getFilesDir(), "profile");
            if (!profile.isDirectory() && !profile.mkdirs()) throw new IOException("Cannot create learning storage");
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            StringBuilder hex = new StringBuilder();
            for (byte value : random) hex.append(String.format("%02x", value & 255));
            token = hex.toString();
            String demoSocket = "lh-demo-" + token;
            demos = new AndroidDemoRunner(this, demoSocket);
            int port = 43871; // Keep WebView localStorage at the same origin across restarts.
            origin = "http://127.0.0.1:" + port;
            String nativeDir = getApplicationInfo().nativeLibraryDir;
            ProcessBuilder builder = new ProcessBuilder(nativeDir + "/libnode.so", "--max-old-space-size=768", new File(server, "entry.cjs").getPath());
            builder.directory(server);
            Map<String, String> env = builder.environment();
            env.put("LD_LIBRARY_PATH", nativeDir);
            env.put("LH_NATIVE_DIR", nativeDir);
            env.put("LH_ANDROID_TOKEN", token);
            env.put("LH_ANDROID_DEMO_SOCKET", demoSocket);
            env.put("HOSTNAME", "127.0.0.1");
            env.put("PORT", Integer.toString(port));
            env.put("HOME", profile.getPath());
            env.put("TMPDIR", getCacheDir().getPath());
            env.put("LH_DATA_DIR", new File(profile, "data").getPath());
            env.put("LH_AUTH_FILE", new File(profile, "auth.json").getPath());
            env.put("NODE_EXTRA_CA_CERTS", new File(server, "cert.pem").getPath());
            env.put("NEXT_TELEMETRY_DISABLED", "1");
            builder.redirectErrorStream(true).redirectOutput(new File(getCacheDir(), "runtime.log"));
            synchronized (this) {
                if (stopping) return;
                process = builder.start();
            }
            long deadline = System.currentTimeMillis() + 90000;
            while (!stopping && process.isAlive() && System.currentTimeMillis() < deadline) {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(origin + "/api/settings").openConnection();
                    connection.setRequestProperty("Cookie", "lh_android=" + token);
                    connection.setConnectTimeout(1000);
                    connection.setReadTimeout(2000);
                    if (connection.getResponseCode() == 200) { ready = true; break; }
                } catch (IOException ignored) { /* Next is still starting. */ }
                finally { if (connection != null) connection.disconnect(); }
                Thread.sleep(250);
            }
            if (!ready && !stopping) throw new IOException("The local server did not start. Reopen the app to retry.");
            int status = process.waitFor();
            if (!stopping) throw new IOException("The local server stopped (" + status + "). Reopen the app to retry.");
        } catch (Exception error) {
            if (!stopping) { failure = error.getMessage(); stopSelf(); }
        }
    }

    private File installServer() throws IOException {
        String id;
        try (InputStream input = getAssets().open("runtime-id.txt")) { id = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim(); }
        if (!id.matches("[0-9a-f]{64}")) throw new IOException("Invalid runtime package");
        File root = new File(getFilesDir(), "runtime");
        File target = new File(root, id);
        if (RuntimeFiles.isInstalled(target)) return target;
        if (target.exists()) deleteTree(target);
        File staging = new File(root, "installing");
        deleteTree(staging);
        if (!staging.mkdirs()) throw new IOException("Cannot unpack the app");
        try (ZipInputStream zip = new ZipInputStream(getAssets().open("server.zip"))) {
            ZipEntry entry;
            String prefix = staging.getCanonicalPath() + File.separator;
            while ((entry = zip.getNextEntry()) != null) {
                File destination = new File(staging, entry.getName());
                if (!destination.getCanonicalPath().startsWith(prefix)) throw new IOException("Invalid runtime path");
                if (entry.isDirectory()) { if (!destination.isDirectory() && !destination.mkdirs()) throw new IOException("Cannot create runtime directory"); }
                else {
                    File parent = destination.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create runtime directory");
                    try (OutputStream output = new FileOutputStream(destination)) { zip.transferTo(output); }
                }
                zip.closeEntry();
            }
        }
        if (!new File(staging, "server.js").isFile()) throw new IOException("Incomplete runtime package");
        if (!new File(staging, ".ready").createNewFile() || !staging.renameTo(target)) throw new IOException("Cannot finish app installation");
        File[] older = root.listFiles();
        if (older != null) for (File old : older) if (!old.equals(target)) deleteTree(old);
        return target;
    }

    private static void deleteTree(File file) throws IOException {
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) { java.nio.file.Files.delete(file.toPath()); return; }
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (file.exists() && !file.delete()) throw new IOException("Cannot remove incomplete runtime");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onTimeout(int startId, int type) { failure = "Android paused the background server. Reopen LearnHarness to continue."; stopSelf(); }
    @Override public void onDestroy() {
        stopping = true;
        idleHandler.removeCallbacksAndMessages(null);
        if (instance == this) instance = null;
        ready = false;
        synchronized (this) { if (process != null) process.destroy(); }
        try { if (demos != null) demos.close(); } catch (IOException ignored) { }
        token = null;
        super.onDestroy();
    }
}
