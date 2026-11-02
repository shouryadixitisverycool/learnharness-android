package ai.learnharness.android;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.*;
import android.webkit.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.regex.*;

/** A separate WebView with no native bridge, file access, or app-origin cookies. */
final class AndroidDemoRunner implements AutoCloseable {
    private final Context context;
    private final LocalServerSocket server;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private volatile LocalSocket active;
    private WebView running;

    AndroidDemoRunner(Context context, String socketName) throws IOException {
        this.context = context;
        server = new LocalServerSocket(socketName);
        new Thread(this::listen, "learnharness-demo").start();
    }

    private void listen() {
        while (!closed) {
            try (LocalSocket socket = server.accept()) {
                active = socket;
                socket.setSoTimeout(10000);
                byte[] bytes = socket.getInputStream().readNBytes(4 * 1024 * 1024 + 1);
                if (bytes.length > 4 * 1024 * 1024) throw new IOException("Demo too large");
                JSONObject request = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<JSONObject> result = new AtomicReference<>(failure("Android demo timed out"));
                ui.post(() -> execute(request, reply -> { result.set(reply); latch.countDown(); }));
                latch.await(42, TimeUnit.SECONDS);
                byte[] response = result.get().toString().getBytes(StandardCharsets.UTF_8);
                if (response.length > 1024 * 1024) response = failure("Android demo response is too large").toString().getBytes(StandardCharsets.UTF_8);
                socket.getOutputStream().write(response);
            } catch (IOException | JSONException ignored) { /* The caller receives a failed/closed response. */ }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            finally { active = null; }
        }
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void execute(JSONObject request, Consumer<JSONObject> reply) {
        if (closed) { reply.accept(failure("Android demo runner stopped")); return; }
        WebView view = new WebView(context);
        running = view;
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setAllowFileAccess(false);
        view.getSettings().setAllowContentAccess(false);
        view.getSettings().setDomStorageEnabled(false);
        view.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        view.layout(0, 0, 900, 700);
        List<String> console = new ArrayList<>(), failed = new ArrayList<>();
        AtomicBoolean done = new AtomicBoolean();
        Runnable[] timeout = new Runnable[1];
        Consumer<JSONObject> finish = result -> {
            if (done.getAndSet(true)) return;
            ui.removeCallbacks(timeout[0]);
            view.stopLoading(); view.destroy(); running = null;
            reply.accept(result);
        };
        timeout[0] = () -> finish.accept(failure("Android demo exceeded 40 seconds"));
        ui.postDelayed(timeout[0], 40000);
        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) DemoDiagnostics.append(console, message.message());
                return true;
            }
        });
        view.setWebViewClient(new WebViewClient() {
            private boolean loaded;
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError error) { DemoDiagnostics.append(failed, r.getUrl() + " (" + error.getDescription() + ")"); }
            @Override public void onPageFinished(WebView v, String url) {
                if (loaded || done.get()) return;
                loaded = true;
                ui.postDelayed(() -> {
                    if (done.get()) return;
                    String probe = request.optString("probe", "undefined");
                    view.evaluateJavascript("window.__lhProbe={done:false};Promise.resolve().then(()=> (0,eval)(" + JSONObject.quote(probe) + ")).then(value=>window.__lhProbe={done:true,value},error=>window.__lhProbe={done:true,error:String(error)});", ignored -> {
                        long deadline = System.currentTimeMillis() + 5000;
                        Runnable poll = new Runnable() {
                            @Override public void run() {
                                if (done.get()) return;
                                view.evaluateJavascript("JSON.stringify({errors:window.__lhErrors||[],probe:window.__lhProbe})", value -> {
                                    if (done.get()) return;
                                    try {
                                        if (value == null || value.length() > 256 * 1024) { finish.accept(failure("Demo probe result is too large")); return; }
                                        JSONObject state = new JSONObject((String) new JSONTokener(value).nextValue());
                                        JSONObject probeState = state.optJSONObject("probe");
                                        if (probeState == null || !probeState.optBoolean("done")) {
                                            if (System.currentTimeMillis() < deadline) { ui.postDelayed(this, 50); return; }
                                            finish.accept(failure("Demo probe timed out")); return;
                                        }
                                        List<String> errors = new ArrayList<>();
                                        JSONArray pageErrors = state.optJSONArray("errors");
                                        for (int i = 0; pageErrors != null && i < pageErrors.length(); i++) DemoDiagnostics.append(errors, pageErrors.optString(i));
                                        if (probeState.has("error")) DemoDiagnostics.append(errors, "probe failed: " + probeState.getString("error"));
                                        JSONObject result = new JSONObject().put("consoleErrors", new JSONArray(console)).put("pageErrors", new JSONArray(errors)).put("failedRequests", new JSONArray(failed))
                                            .put("ok", console.isEmpty() && errors.isEmpty() && failed.isEmpty());
                                        if (request.has("probe") && probeState.has("value")) result.put("probeResult", probeState.get("value"));
                                        finish.accept(result);
                                    } catch (JSONException | ClassCastException error) { finish.accept(failure("Cannot read demo probe: " + error.getMessage())); }
                                });
                            }
                        };
                        poll.run();
                    });
                }, Math.max(0, Math.min(10000, request.optInt("settleMs", 2000))));
            }
        });
        String html = request.optString("html");
        String bootstrap = "<link rel=\"icon\" href=\"data:,\"><script>window.__lhErrors=[];(function(){const lhError=e=>{if(window.__lhErrors.length<64)window.__lhErrors.push(String(e).slice(0,512))};addEventListener('error',e=>lhError(e.message||'Resource failed'));addEventListener('unhandledrejection',e=>lhError(e.reason))})();</script>";
        Matcher head = Pattern.compile("<head\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        html = head.find() ? html.substring(0, head.end()) + bootstrap + html.substring(head.end()) : bootstrap + html;
        view.loadDataWithBaseURL("https://learnharness-demo.invalid/", html, "text/html", "UTF-8", null);
    }

    private static JSONObject failure(String error) {
        try { return new JSONObject().put("ok", false).put("consoleErrors", new JSONArray()).put("pageErrors", new JSONArray().put(error)).put("failedRequests", new JSONArray()); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    @Override public void close() throws IOException {
        closed = true; server.close();
        if (active != null) active.close();
        ui.post(() -> { if (running != null) { running.destroy(); running = null; } });
    }
}
