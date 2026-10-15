package gg.projects.mobile.content;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Signed content fixtures from src/test/resources/content, generated with throwaway Ed25519
 * keys by scripts/projects/GenFixtures.java (private keys are never stored).
 */
final class Fixtures {
    private Fixtures() {}

    static final String MIRROR_A = "https://mirror-a.test/v1/";
    static final String MIRROR_B = "https://mirror-b.test/v1/";
    static final String NEWS_PATH = "news/news.7ce2c1e5358806c665323ea858c93fec7486028cb742f7654f011a970f8752ff.json";
    static final String CONFIG_PATH = "config/launcher.e0aba007fe933ad13f1304362d8cece4bc6dfd7d063530cbed7743d4de4e2fb3.json";
    static final String PACK_PATH = "packs/projects/2.3.0.json";

    static byte[] bytes(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/content/" + name)) {
            if (in == null) throw new IllegalArgumentException("missing fixture " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String text(String name) {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }

    static String publicKey() {
        return text("throwaway-public-key.txt").trim();
    }

    static String otherPublicKey() {
        return text("other-public-key.txt").trim();
    }

    static IndexVerifier verifier() throws ContentException {
        List<String> keys = new ArrayList<>();
        keys.add(publicKey());
        return new IndexVerifier(keys);
    }

    /** An in-memory mirror network: URL to bytes, or to a failure. Records every request. */
    static final class FakeHttp implements HttpFetcher {
        final Map<String, byte[]> responses = new HashMap<>();
        final Map<String, IOException> failures = new HashMap<>();
        final List<String> requests = new ArrayList<>();

        FakeHttp serve(String url, byte[] body) {
            responses.put(url, body);
            return this;
        }

        /** Serves the whole signed fixture tree on {@code mirror}. */
        FakeHttp serveTree(String mirror) {
            serve(mirror + "index.json", bytes("v1/index.json"));
            serve(mirror + "index.json.sig", bytes("v1/index.json.sig"));
            serve(mirror + CONFIG_PATH, bytes("v1/" + CONFIG_PATH));
            serve(mirror + NEWS_PATH, bytes("v1/" + NEWS_PATH));
            serve(mirror + PACK_PATH, bytes("v1/" + PACK_PATH));
            return this;
        }

        @Override
        public byte[] get(String url, long maxBytes) throws IOException {
            requests.add(url);
            IOException failure = failures.get(url);
            if (failure != null) throw failure;
            byte[] body = responses.get(url);
            if (body == null) throw new HttpStatusException(404, url);
            if (body.length > maxBytes) throw new IOException("too large");
            return body;
        }
    }

    static final class NoNetwork implements HttpFetcher {
        @Override
        public byte[] get(String url, long maxBytes) throws IOException {
            throw new FileNotFoundException("offline: " + url);
        }
    }
}
