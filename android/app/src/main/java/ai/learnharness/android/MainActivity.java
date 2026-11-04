package ai.learnharness.android;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.util.TypedValue;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.*;

/**
 * LearnHarness for Android, server shell: a WebView for your own
 * LearnHarness server (docs/android.md). Everything lives on the server;
 * the phone keeps only the address and the session cookie.
 *
 * - First launch (or "Connect…" in Settings): a native screen asks for the
 *   address, checks it is a LearnHarness server in server mode, and saves it.
 * - The WebView stays on that origin; other links open in the browser.
 * - The page gets two bridges over a message port delivered only to the main
 *   frame (never demo iframes): window.lhAndroidSaveText for exports, and
 *   window.lh.server for Settings → General → Server (connect, sign out).
 */
public class MainActivity extends Activity {
    private static final int MAX_EXPORT = 25 * 1024 * 1024;
    private static final int REQUEST_FILES = 1, REQUEST_AUDIO = 2, REQUEST_EXPORT = 3;
    private static final int BG = Color.rgb(20, 20, 20), FG = Color.rgb(230, 230, 230), MUTED = Color.rgb(150, 150, 150);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private View panel;
    private String server;
    private ValueCallback<Uri[]> fileCallback;
    private WebMessagePort bridgePort;
    private PermissionRequest audioRequest;
    private String exportPath;
    private int exportPickerRequest;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            exportPath = state.getString("exportPath");
            exportPickerRequest = state.getInt("exportPickerRequest");
        }
        pruneStagedExports();
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(root);
        server = prefs().getString("url", null);
        if (server == null) showConnect(null, null);
        else openServer();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::onBack);
    }

    private SharedPreferences prefs() { return getSharedPreferences("server", MODE_PRIVATE); }

    private String userAgentTag() {
        try { return " LearnHarnessAndroid/" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException error) { return " LearnHarnessAndroid/0"; }
    }

    private boolean onServer(Uri uri) { return uri != null && ServerAddress.sameOrigin(server, uri.toString()); }

    // ─── Back ───────────────────────────────────────────────────────────────

    private void onBack() {
        if (panel != null) {
            if (server != null && web != null) { hidePanel(); return; }
            moveTaskToBack(true);
            return;
        }
        if (web == null) { moveTaskToBack(true); return; }
        // Close the topmost menu, dialog or chains overlay, then an open sidebar,
        // before navigating back.
        web.evaluateJavascript("(() => { const layer = [...document.querySelectorAll('[data-lh-menu], [role=dialog], [role=menu], [data-testid=chain-table-overlay]')].filter(e=>e.getBoundingClientRect().width>0).at(-1); if(layer) { layer.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true})); return true; } const sidebar = document.querySelector('.lh-sidebar'); if(sidebar?.getAttribute('aria-hidden') === 'false') { sidebar.querySelector('[aria-label=\"Close sidebar\"]')?.click(); return true; } return false; })()", consumed -> {
            if (!"true".equals(consumed)) { if (web.canGoBack()) web.goBack(); else moveTaskToBack(true); }
        });
    }

    // ─── Native panels: connect and unreachable ─────────────────────────────

    private int dp(int value) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()); }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTypeface(Typeface.MONOSPACE);
        view.setPadding(0, 0, 0, dp(12));
        return view;
    }

    private Button button(String label, View.OnClickListener click) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTypeface(Typeface.MONOSPACE);
        button.setOnClickListener(click);
        return button;
    }

    private LinearLayout panelBox() {
        hidePanel();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));
        box.setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(box, new ScrollView.LayoutParams(-1, -1));
        panel = scroll;
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        return box;
    }

    private void hidePanel() {
        if (panel != null) { root.removeView(panel); panel = null; }
    }

    /** The address screen. `prefill` is the current address, if any. */
    private void showConnect(String prefill, String message) {
        LinearLayout box = panelBox();
        box.addView(text("Connect to Server", 22, FG));
        box.addView(text("Enter the address of your LearnHarness server, for example my-server.tail1234.ts.net:8446.", 14, MUTED));
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setTextColor(FG);
        address.setHintTextColor(MUTED);
        address.setHint("my-server.tail1234.ts.net:8446");
        address.setTypeface(Typeface.MONOSPACE);
        if (prefill != null) address.setText(prefill.replaceFirst("^https://", ""));
        box.addView(address);
        TextView status = text(message == null ? "" : message, 14, Color.rgb(240, 120, 110));
        status.setPadding(0, dp(8), 0, dp(12));
        box.addView(status);
        Button connect = button("Connect", null);
        Runnable submit = () -> {
            ServerAddress.Result result = ServerAddress.normalize(address.getText().toString());
            if (!result.ok()) { status.setText(result.error); return; }
            connect.setEnabled(false);
            status.setTextColor(MUTED);
            status.setText("Checking " + result.origin + "…");
            new Thread(() -> {
                String problem = probe(result.origin);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    connect.setEnabled(true);
                    if (problem != null) { status.setTextColor(Color.rgb(240, 120, 110)); status.setText(problem); return; }
                    boolean changed = !result.origin.equals(server);
                    prefs().edit().putString("url", result.origin).apply();
                    server = result.origin;
                    hidePanel();
                    if (changed || web == null) openServer();
                });
            }, "learnharness-probe").start();
        };
        connect.setOnClickListener(v -> submit.run());
        address.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_GO) { submit.run(); return true; } return false; });
        box.addView(connect);
        if (server != null && web != null) box.addView(button("Cancel", v -> hidePanel()));
        address.requestFocus();
    }

    /** null when the address is a LearnHarness server in server mode, else why not. */
    private String probe(String origin) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(origin + "/login").openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", "LearnHarness" + userAgentTag());
            if (connection.getResponseCode() == 200) {
                try (InputStream input = connection.getInputStream()) {
                    String body = new String(input.readNBytes(256 * 1024), java.nio.charset.StandardCharsets.UTF_8);
                    if (body.contains("action=\"/api/auth/login\"")) return null;
                }
            }
            return origin + " answered, but it isn't a LearnHarness server with server mode on.";
        } catch (IOException error) {
            return "Couldn't reach " + origin + ". Check the address, that Tailscale is connected, and that the server is running.";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void showUnreachable(String detail) {
        LinearLayout box = panelBox();
        box.addView(text("Can't reach your LearnHarness server", 20, FG));
        box.addView(text(server + "\n\n" + detail, 14, MUTED));
        box.addView(button("Try again", v -> { hidePanel(); if (web != null) web.reload(); else openServer(); }));
        box.addView(button("Change server", v -> showConnect(server, null)));
    }

    // ─── The server page ────────────────────────────────────────────────────

    @SuppressWarnings("SetJavaScriptEnabled")
    private void openServer() {
        closeWeb();
        web = new WebView(this);
        web.setBackgroundColor(BG);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + userAgentTag());
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame() || onServer(request.getUrl())) return false;
                external(request.getUrl());
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (onServer(Uri.parse(url))) installBridges(view);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showUnreachable(String.valueOf(error.getDescription()));
            }
            @Override public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler ssl, android.net.http.SslError error) {
                ssl.cancel();
                showUnreachable("The server's HTTPS certificate isn't valid.");
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (isFinishing() || isDestroyed()) return true;
                handler.post(MainActivity.this::openServer);
                return true;
            }
        });
        web.setDownloadListener((url, userAgent, disposition, mime, length) -> {
            if (!onServer(Uri.parse(url))) return;
            new Thread(() -> download(url, disposition, mime), "learnharness-export").start();
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try { startActivityForResult(intent, REQUEST_FILES); } catch (ActivityNotFoundException error) { fileCallback.onReceiveValue(null); fileCallback = null; }
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest request) {
                if (!onServer(request.getOrigin()) || !java.util.Arrays.equals(request.getResources(), new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE})) { request.deny(); return; }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                else { audioRequest = request; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO); }
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) { if (audioRequest == request) audioRequest = null; }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, Message result) {
                if (!gesture) return false;
                WebView popup = new WebView(MainActivity.this);
                popup.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView child, WebResourceRequest request) {
                        if (onServer(request.getUrl()) || "about".equals(request.getUrl().getScheme())) return false;
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
        web.loadUrl(server);
    }

    private void closeWeb() {
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        if (audioRequest != null) { audioRequest.deny(); audioRequest = null; }
        if (bridgePort != null) { bridgePort.close(); bridgePort = null; }
        if (web != null) { root.removeView(web); web.destroy(); web = null; }
    }

    private void external(Uri uri) {
        if (!"https".equals(uri.getScheme()) && !"mailto".equals(uri.getScheme())) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (ActivityNotFoundException error) { Toast.makeText(this, "No app can open this link", Toast.LENGTH_LONG).show(); }
    }

    /** Exports and the server settings row, over a port only the main frame receives. */
    private void installBridges(WebView view) {
        if (bridgePort != null) bridgePort.close();
        WebMessagePort[] ports = view.createWebMessageChannel();
        bridgePort = ports[0];
        ports[0].setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
            @Override public void onMessage(WebMessagePort port, WebMessage message) {
                try {
                    if (message.getData() == null || message.getData().length() > MAX_EXPORT * 2) return;
                    JSONObject payload = new JSONObject(message.getData());
                    if ("save".equals(payload.optString("type"))) {
                        saveDocument(payload.getString("name"), payload.getString("mime"), payload.getString("text").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } else if ("server".equals(payload.optString("type"))) {
                        serverRequest(port, payload.getInt("id"), payload.getString("op"));
                    }
                } catch (JSONException error) { Toast.makeText(MainActivity.this, "That didn't work. Try again.", Toast.LENGTH_LONG).show(); }
            }
        });
        String nonce = java.util.UUID.randomUUID().toString();
        String script = "window.addEventListener('message', function receive(e) {"
            + " if (e.data !== " + JSONObject.quote(nonce) + " || !e.ports[0]) return;"
            + " window.removeEventListener('message', receive);"
            + " const port = e.ports[0]; let next = 0; const waiting = new Map();"
            + " port.onmessage = (m) => { const r = JSON.parse(m.data); const w = waiting.get(r.id); if (!w) return; waiting.delete(r.id); r.ok ? w.resolve(r.result) : w.reject(new Error('failed')); };"
            + " const ask = (op) => new Promise((resolve, reject) => { const id = ++next; waiting.set(id, { resolve, reject }); port.postMessage(JSON.stringify({ type: 'server', op, id })); });"
            + " window.lhAndroidSaveText = (name, text, mime) => port.postMessage(JSON.stringify({ type: 'save', name, text, mime }));"
            + " window.lh = Object.assign(window.lh || {}, { platform: 'android', expandWindow() {}, server: { current: () => ask('current'), connect: () => ask('connect'), signOut: () => ask('sign-out') } });"
            + "});";
        view.evaluateJavascript(script, ignored -> view.postWebMessage(new WebMessage(nonce, new WebMessagePort[]{ports[1]}), Uri.parse(server)));
    }

    private void serverRequest(WebMessagePort port, int id, String op) {
        try {
            JSONObject reply = new JSONObject().put("id", id).put("ok", true);
            if ("current".equals(op)) {
                reply.put("result", new JSONObject().put("mode", "remote").put("url", server));
            } else if ("connect".equals(op)) {
                showConnect(server, null);
            } else if ("sign-out".equals(op)) {
                CookieManager cookies = CookieManager.getInstance();
                cookies.removeAllCookies(done -> { cookies.flush(); if (web != null) web.loadUrl(server); });
            } else {
                reply.put("ok", false);
            }
            port.postMessage(new WebMessage(reply.toString()));
        } catch (JSONException error) { /* the page's promise stays pending; nothing to report */ }
    }

    // ─── Files in and out ───────────────────────────────────────────────────

    private void download(String url, String disposition, String mime) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestProperty("Cookie", CookieManager.getInstance().getCookie(server));
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > MAX_EXPORT) throw new IOException("Cannot download this file");
            byte[] bytes;
            try (InputStream input = connection.getInputStream()) { bytes = input.readNBytes(MAX_EXPORT + 1); }
            String name = URLUtil.guessFileName(url, disposition, mime);
            runOnUiThread(() -> saveDocument(name, mime, bytes));
        } catch (IOException error) {
            runOnUiThread(() -> Toast.makeText(this, "Export failed. Try again.", Toast.LENGTH_LONG).show());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == REQUEST_AUDIO && audioRequest != null) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) audioRequest.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else audioRequest.deny();
            audioRequest = null;
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQUEST_FILES && fileCallback != null) { fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data)); fileCallback = null; }
        if (code == exportPickerRequest && code == REQUEST_EXPORT) {
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
        exportPickerRequest = REQUEST_EXPORT;
        try { startActivityForResult(intent, REQUEST_EXPORT); }
        catch (ActivityNotFoundException error) { staged.delete(); exportPath = null; exportPickerRequest = 0; Toast.makeText(this, "No document app is installed", Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("exportPath", exportPath);
        state.putInt("exportPickerRequest", exportPickerRequest);
        super.onSaveInstanceState(state);
    }

    private void pruneStagedExports() {
        File[] files = getCacheDir().listFiles((dir, name) -> name.startsWith("learnharness-export-"));
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        for (File file : files) if (!file.getAbsolutePath().equals(exportPath) && file.lastModified() < cutoff) file.delete();
    }

    @Override protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        closeWeb();
        super.onDestroy();
    }
}
