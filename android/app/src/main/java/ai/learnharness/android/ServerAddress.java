package ai.learnharness.android;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** A typed server address, reduced to the origin the app loads. Pure Java
 *  so it unit-tests without Android. */
final class ServerAddress {
    private ServerAddress() {}

    static final class Result {
        final String origin;
        final String error;
        private Result(String origin, String error) { this.origin = origin; this.error = error; }
        boolean ok() { return origin != null; }
    }

    /** "dixithome.example.ts.net:8446/chat/x" -> "https://dixithome.example.ts.net:8446".
     *  Only https: the password and session cookie must not travel in the clear,
     *  and `tailscale serve` gives every tailnet server an https address. */
    static Result normalize(String typed) {
        String text = typed == null ? "" : typed.trim();
        if (text.isEmpty()) return new Result(null, "Enter your server's address.");
        if (!text.contains("://")) text = "https://" + text;
        URI uri;
        try { uri = new URI(text); }
        catch (URISyntaxException error) { return new Result(null, "That isn't a valid address."); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("http")) return new Result(null, "Use the https:// address. On Tailscale, `tailscale serve` gives the server one.");
        if (!scheme.equals("https")) return new Result(null, "The address must start with https://.");
        if (uri.getRawUserInfo() != null) return new Result(null, "Leave the user name and password out of the address.");
        String host = uri.getHost();
        if (host == null || host.isEmpty()) return new Result(null, "That isn't a valid address.");
        int port = uri.getPort();
        String origin = "https://" + host.toLowerCase(Locale.ROOT) + (port == -1 || port == 443 ? "" : ":" + port);
        return new Result(origin, null);
    }

    /** Does this URL belong to the server's origin? */
    static boolean sameOrigin(String origin, String url) {
        if (origin == null || url == null) return false;
        try {
            URI a = new URI(origin), b = new URI(url);
            return "https".equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
        } catch (URISyntaxException | NullPointerException error) {
            return false;
        }
    }

    private static int effectivePort(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }
}
