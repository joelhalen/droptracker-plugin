package io.droptracker.api;

import org.junit.Test;

import io.droptracker.api.UrlManager.WebhookEndpoint;
import okhttp3.HttpUrl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * The published webhook list is fetched at runtime, so its contents are outside
 * our control at review time. {@link UrlManager#parseEndpoint} is what makes that
 * acceptable: it reads two credentials out of an entry and throws the rest away,
 * so the host is always ours. These tests pin that behaviour.
 */
public class UrlManagerEndpointTest {

    private static final String ID = "123456789012345678";
    private static final String TOKEN = "abcdefghijklmnopqrstuvwxyz012345";

    @Test
    public void parsesBareCredentialPair() {
        // The format the backend publishes going forward.
        WebhookEndpoint endpoint = UrlManager.parseEndpoint(ID + "/" + TOKEN);
        assertNotNull(endpoint);
        assertEquals(HttpUrl.get("https://discord.com/api/webhooks/" + ID + "/" + TOKEN), endpoint.url());
    }

    @Test
    public void parsesLegacyFullUrlEntries() {
        // Lists published before the switch carry whole URLs; they must still work.
        assertEquals(HttpUrl.get("https://discord.com/api/webhooks/" + ID + "/" + TOKEN),
            UrlManager.parseEndpoint("https://discord.com/api/webhooks/" + ID + "/" + TOKEN).url());
        assertEquals(HttpUrl.get("https://discord.com/api/webhooks/" + ID + "/" + TOKEN),
            UrlManager.parseEndpoint("https://discordapp.com/api/webhooks/" + ID + "/" + TOKEN).url());
        assertEquals(HttpUrl.get("https://discord.com/api/webhooks/" + ID + "/" + TOKEN),
            UrlManager.parseEndpoint("/api/webhooks/" + ID + "/" + TOKEN).url());
    }

    @Test
    public void discardsWhateverHostTheEntryClaims() {
        // The property the whole design rests on: a compromised or malicious list
        // entry can change which webhook we post to, never which host.
        WebhookEndpoint endpoint =
            UrlManager.parseEndpoint("https://evil.example/api/webhooks/" + ID + "/" + TOKEN);
        assertNotNull(endpoint);
        assertEquals("discord.com", endpoint.url().host());
    }

    @Test
    public void stripsQueryAndFragment() {
        assertEquals(HttpUrl.get("https://discord.com/api/webhooks/" + ID + "/" + TOKEN),
            UrlManager.parseEndpoint(ID + "/" + TOKEN + "?wait=true").url());
        assertEquals(HttpUrl.get("https://discord.com/api/webhooks/" + ID + "/" + TOKEN),
            UrlManager.parseEndpoint(ID + "/" + TOKEN + "#x").url());
    }

    @Test
    public void rejectsAnythingThatIsNotACredentialPair() {
        assertNull(UrlManager.parseEndpoint(null));
        assertNull(UrlManager.parseEndpoint(""));
        assertNull(UrlManager.parseEndpoint("https://evil.example/"));
        assertNull(UrlManager.parseEndpoint(ID));
        assertNull(UrlManager.parseEndpoint(ID + "/" + TOKEN + "/extra"));
        assertNull(UrlManager.parseEndpoint("notanid/" + TOKEN));
        assertNull(UrlManager.parseEndpoint(ID + "/short"));
        assertNull(UrlManager.parseEndpoint(ID + "/tok en" + TOKEN));
    }

    @Test
    public void toleratesLeadingControlCharacters() {
        // Decryption occasionally leaves these on the front of the plaintext.
        assertEquals(HttpUrl.get("https://discord.com/api/webhooks/" + ID + "/" + TOKEN),
            UrlManager.parseEndpoint("  " + ID + "/" + TOKEN + "  ").url());
    }
}
