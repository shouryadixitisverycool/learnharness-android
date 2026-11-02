package ai.learnharness.android;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.*;

public class MainActivity extends Activity {
    private WebView web;
    private TextView status;
    private FrameLayout root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> fileCallback;
    private WebMessagePort exportPort;
    private PermissionRequest audioRequest;
    private String exportPath;
    private int exportPickerRequest;
    private boolean loaded;
    private static final int MAX_EXPORT = 25 * 1024 * 1024;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            exportPath = state.getString("exportPath");
            exportPickerRequest = state.getInt("exportPickerRequest");
        }
        pruneStagedExports();
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(20, 20, 20));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(root);
        status = new TextView(this);
        status.setText("Starting LearnHarness…\n\nThe first launch unpacks the app on your phone.");
        status.setTextColor(Color.rgb(230, 230, 230));
        status.setGravity(Gravity.CENTER);
        status.setPadding(32, 32, 32, 32);
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        startForegroundService(new Intent(this, RuntimeService.class));
        handler.post(this::awaitRuntime);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, () -> {
            if (web == null) { moveTaskToBack(true); return; }
            web.evaluateJavascript("(() => { const layer = [...document.querySelectorAll('[data-lh-menu], [role=dialog], [role=menu], [data-testid=chain-table-overlay]')].filter(e=>e.getBoundingClientRect().width>0).at(-1); if(layer) { layer.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true})); return true; } const sidebar = document.querySelector('.lh-sidebar'); if(sidebar?.getAttribute('aria-hidden') === 'false') { sidebar.querySelector('[aria-label=\"Close sidebar\"]')?.click(); return true; } return false; })()", consumed -> {
                if (!"true".equals(consumed)) { if (web.canGoBack()) web.goBack(); else moveTaskToBack(true); }
            });
        });
    }

    @Override protected void onResume() {
        super.onResume();
        RuntimeService.cancelIdleStop();
        if (web == null || RuntimeService.ready) return;
        loaded = false;
        root.removeView(web);
        web.destroy();
        web = null;
        status.setText("Starting LearnHarness…");
        status.setVisibility(View.VISIBLE);
        startForegroundService(new Intent(this, RuntimeService.class));
        handler.postDelayed(this::awaitRuntime, 250);
    }

    @Override protected void onStop() {
        super.onStop();
        RuntimeService.requestIdleStop();
    }

    private void awaitRuntime() {
        if (isFinishing() || isDestroyed()) return;
        if (RuntimeService.failure != null) {
            status.setText("LearnHarness could not start.\n\n" + RuntimeService.failure + "\n\nTap to retry.");
            status.setOnClickListener(view -> { status.setOnClickListener(null); status.setText("Starting LearnHarness…"); startForegroundService(new Intent(this, RuntimeService.class)); handler.postDelayed(this::awaitRuntime, 500); });
        } else if (RuntimeService.ready && !loaded) {
            loaded = true;
            openApp();
        } else handler.postDelayed(this::awaitRuntime, 250);
    }

    private boolean local(Uri uri) {
        return RuntimeService.origin != null && "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
            && uri.getPort() == Uri.parse(RuntimeService.origin).getPort();
    }

    private void external(Uri uri) {
        if (!"https".equals(uri.getScheme()) && !"mailto".equals(uri.getScheme())) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (ActivityNotFoundException error) { Toast.makeText(this, "No app can open this link", Toast.LENGTH_LONG).show(); }
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void openApp() {
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(20, 20, 20));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " LearnHarnessAndroid");
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setCookie(RuntimeService.origin, "lh_android=" + RuntimeService.token + "; Path=/; HttpOnly; SameSite=Strict");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                if (local(request.getUrl())) return false;
                external(request.getUrl());
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!local(Uri.parse(url))) return;
                status.setVisibility(View.GONE);
                // The message port is delivered only to the app's main frame, never demo iframes.
                if (exportPort != null) exportPort.close();
                WebMessagePort[] ports = view.createWebMessageChannel();
                exportPort = ports[0];
                ports[0].setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
                    @Override public void onMessage(WebMessagePort port, WebMessage message) {
                        try {
                            if (message.getData() == null || message.getData().length() > MAX_EXPORT) return;
                            JSONObject payload = new JSONObject(message.getData());
                            saveDocument(payload.getString("name"), payload.getString("mime"), payload.getString("text").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        } catch (JSONException error) { Toast.makeText(MainActivity.this, "Cannot export this file", Toast.LENGTH_LONG).show(); }
                    }
                });
                String nonce = java.util.UUID.randomUUID().toString();
                view.evaluateJavascript("window.addEventListener('message', function receive(e) { if(e.data !== " + JSONObject.quote(nonce) + " || !e.ports[0]) return; window.removeEventListener('message', receive); const port=e.ports[0]; window.lhAndroidSaveText=(name,text,mime)=>port.postMessage(JSON.stringify({name,text,mime})); });", ignored ->
                    view.postWebMessage(new WebMessage(nonce, new WebMessagePort[]{ports[1]}), Uri.parse(RuntimeService.origin)));
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    status.setVisibility(View.VISIBLE);
                    status.setText("The local page could not load. Tap to retry.");
                    status.setOnClickListener(v -> {
                        if (RuntimeService.ready) web.reload();
                        else { loaded = false; root.removeView(web); web.destroy(); web = null; startForegroundService(new Intent(MainActivity.this, RuntimeService.class)); handler.postDelayed(MainActivity.this::awaitRuntime, 500); }
                    });
                }
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (isFinishing() || isDestroyed()) return true;
                recoverRenderer();
                return true;
            }
        });
        web.setDownloadListener((url, userAgent, disposition, mime, length) -> {
            if (!local(Uri.parse(url))) return;
            new Thread(() -> {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setRequestProperty("Cookie", CookieManager.getInstance().getCookie(RuntimeService.origin));
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(30000);
                    if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > MAX_EXPORT) throw new IOException("Cannot download this file");
                    byte[] bytes;
                    try (InputStream input = connection.getInputStream()) { bytes = input.readNBytes(MAX_EXPORT + 1); }
                    String name = URLUtil.guessFileName(url, disposition, mime);
                    runOnUiThread(() -> saveDocument(name, mime, bytes));
                } catch (IOException error) { runOnUiThread(() -> Toast.makeText(this, "Export failed. Try again.", Toast.LENGTH_LONG).show()); }
                finally { if (connection != null) connection.disconnect(); }
            }, "learnharness-export").start();
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try { startActivityForResult(intent, 1); } catch (ActivityNotFoundException error) { fileCallback.onReceiveValue(null); fileCallback = null; }
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest request) {
                if (!local(request.getOrigin()) || !java.util.Arrays.equals(request.getResources(), new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE})) { request.deny(); return; }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                else { audioRequest = request; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2); }
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) { if (audioRequest == request) audioRequest = null; }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, Message result) {
                if (!gesture) return false;
                WebView popup = new WebView(MainActivity.this);
                popup.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView child, WebResourceRequest request) {
                        if (local(request.getUrl()) || "about".equals(request.getUrl().getScheme())) return false;
                        external(request.getUrl());
                        handler.post(child::destroy);
                        return true;
                    }
                });
                ((WebView.WebViewTransport) result.obj).setWebView(popup);
                result.sendToTarget();
                return true;
            }
        });
        root.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
        web.loadUrl(RuntimeService.origin);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 2 && audioRequest != null) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) audioRequest.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else audioRequest.deny();
            audioRequest = null;
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == 1 && fileCallback != null) { fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data)); fileCallback = null; }
        if (code == exportPickerRequest && code == 3) {
            File staged = exportPath == null ? null : new File(exportPath);
            exportPath = null;
            exportPickerRequest = 0;
            if (result == RESULT_OK && data != null && data.getData() != null && staged != null && staged.isFile()) {
                Uri destination = data.getData();
                new Thread(() -> {
                    try (InputStream input = new FileInputStream(staged); OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                        if (output == null) throw new IOException("No destination");
                        input.transferTo(output);
                        runOnUiThread(() -> Toast.makeText(this, "File saved", Toast.LENGTH_SHORT).show());
                    } catch (IOException error) { runOnUiThread(() -> Toast.makeText(this, "Could not save the file. Try exporting again.", Toast.LENGTH_LONG).show()); }
                    finally { staged.delete(); }
                }, "learnharness-save").start();
            } else if (staged != null) staged.delete();
        }
    }

    private void saveDocument(String name, String mime, byte[] bytes) {
        if (isFinishing() || isDestroyed() || exportPath != null) return;
        if (bytes.length > MAX_EXPORT) { Toast.makeText(this, "Exports are limited to 25 MB", Toast.LENGTH_LONG).show(); return; }
        File staged;
        try {
            staged = File.createTempFile("learnharness-export-", ".tmp", getCacheDir());
            try (OutputStream output = new FileOutputStream(staged)) { output.write(bytes); }
        } catch (IOException error) { Toast.makeText(this, "Could not prepare the export. Check free storage and try again.", Toast.LENGTH_LONG).show(); return; }
        exportPath = staged.getAbsolutePath();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime == null || !mime.contains("/") ? "application/octet-stream" : mime)
            .putExtra(Intent.EXTRA_TITLE, name.replaceAll("[/\\\\\\p{Cntrl}]", "_").substring(0, Math.min(128, name.length())));
        exportPickerRequest = 3;
        try { startActivityForResult(intent, 3); }
        catch (ActivityNotFoundException error) { staged.delete(); exportPath = null; exportPickerRequest = 0; Toast.makeText(this, "No document app is installed", Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("exportPath", exportPath);
        state.putInt("exportPickerRequest", exportPickerRequest);
        super.onSaveInstanceState(state);
    }

    private void recoverRenderer() {
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        if (audioRequest != null) { audioRequest.deny(); audioRequest = null; }
        if (exportPort != null) { exportPort.close(); exportPort = null; }
        if (web != null) { root.removeView(web); web.destroy(); web = null; }
        loaded = false;
        status.setText("LearnHarness is restoring this screen…");
        status.setVisibility(View.VISIBLE);
        if (!RuntimeService.ready) startForegroundService(new Intent(this, RuntimeService.class));
        handler.removeCallbacksAndMessages(null);
        handler.post(this::awaitRuntime);
    }

    private void pruneStagedExports() {
        File[] files = getCacheDir().listFiles((dir, name) -> name.startsWith("learnharness-export-"));
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        for (File file : files) if (!file.getAbsolutePath().equals(exportPath) && file.lastModified() < cutoff) file.delete();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        if (audioRequest != null) audioRequest.deny();
        if (exportPort != null) exportPort.close();
        if (web != null) { root.removeView(web); web.destroy(); }
        super.onDestroy();
    }
}
