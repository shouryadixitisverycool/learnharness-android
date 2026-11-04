package ai.learnharness.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ServerAddressTest {
    private static String origin(String typed) { return ServerAddress.normalize(typed).origin; }

    @Test public void typedAddressesReduceToAnHttpsOrigin() {
        assertEquals("https://dixithome.example.ts.net:8446", origin("dixithome.example.ts.net:8446"));
        assertEquals("https://dixithome.example.ts.net:8446", origin("  https://DixitHome.example.ts.net:8446/chat/abc?x=1#y "));
        assertEquals("https://lh.example.com", origin("lh.example.com:443/"));
        assertEquals("https://100.101.1.2:8446", origin("100.101.1.2:8446"));
    }

    @Test public void refusesAnythingButHttps() {
        for (String bad : new String[]{"", "   ", "http://lh.example.com", "ftp://host", "javascript:alert(1)", "https://user:pw@host", "https://"}) {
            ServerAddress.Result result = ServerAddress.normalize(bad);
            assertFalse(bad, result.ok());
            assertNull(bad, result.origin);
        }
        assertTrue(ServerAddress.normalize("http://lh.example.com").error.contains("https://"));
    }

    @Test public void sameOriginMatchesHostAndPortOnly() {
        String server = "https://dixithome.example.ts.net:8446";
        assertTrue(ServerAddress.sameOrigin(server, "https://dixithome.example.ts.net:8446/chat/1"));
        assertTrue(ServerAddress.sameOrigin(server, "https://DIXITHOME.example.ts.net:8446/"));
        assertFalse(ServerAddress.sameOrigin(server, "https://dixithome.example.ts.net/"));
        assertFalse(ServerAddress.sameOrigin(server, "http://dixithome.example.ts.net:8446/"));
        assertFalse(ServerAddress.sameOrigin(server, "https://evil.example.com:8446/"));
        assertTrue(ServerAddress.sameOrigin("https://lh.example.com", "https://lh.example.com:443/x"));
        assertFalse(ServerAddress.sameOrigin(server, "not a url"));
    }
}
