package gg.projects.mobile.content;

import static gg.projects.mobile.content.IndexVerifierTest.expect;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Release builds use the embedded mirrors and keys; debug builds may add a local override. */
public class ContentSourcesTest {
    @Test
    public void releaseSourcesAreTheEmbeddedOnes() {
        ContentSources production = ContentSources.production();
        assertEquals(ContentConfig.MIRRORS, production.mirrors);
        assertEquals(ContentConfig.TRUSTED_KEYS, production.trustedKeys);
        assertFalse(production.override);
    }

    @Test
    public void debugOverrideAddsMirrorsAndOneTestKey() throws Exception {
        String key = Fixtures.publicKey();
        ContentSources sources = ContentSources.withDebugOverride(("{\"mirrors\": [\"http://10.0.2.2:8789/v1\"], \"testKey\": \"" + key + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(sources.override);
        assertEquals(Collections.singletonList("http://10.0.2.2:8789/v1/"), sources.mirrors);
        assertEquals(ContentConfig.TRUSTED_KEYS.size() + 1, sources.trustedKeys.size());
        assertTrue("the embedded keys stay trusted", sources.trustedKeys.containsAll(ContentConfig.TRUSTED_KEYS));
        assertEquals(key, sources.trustedKeys.get(sources.trustedKeys.size() - 1));
        // The test key verifies the signed fixture.
        new IndexVerifier(sources.trustedKeys).verify(Fixtures.bytes("v1/index.json"), Fixtures.bytes("v1/index.json.sig"));

        // Without mirrors the embedded ones are used; a broken file is refused.
        assertEquals(ContentConfig.MIRRORS, ContentSources.withDebugOverride("{}".getBytes(StandardCharsets.UTF_8)).mirrors);
        expect(ContentException.Kind.INVALID, () -> ContentSources.withDebugOverride("{\"mirrors\": [\"file:///sdcard\"]}".getBytes(StandardCharsets.UTF_8)));
        expect(ContentException.Kind.INVALID, () -> ContentSources.withDebugOverride("{\"testKey\": \"short\"}".getBytes(StandardCharsets.UTF_8)));
        expect(ContentException.Kind.INVALID, () -> ContentSources.withDebugOverride("not json".getBytes(StandardCharsets.UTF_8)));
    }
}
