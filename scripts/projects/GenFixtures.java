import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;

/**
 * Generates the signed content fixtures for the Android unit tests with THROWAWAY Ed25519 keys
 * (JDK built-in EdDSA). The private keys are never written anywhere; only public keys, documents
 * and signatures are. Run: java GenFixtures.java <test-resources>/content
 * Regenerating creates new keys and document hashes: update the key ids and paths in
 * Fixtures.java and IndexVerifierTest.java afterwards.
 */
public class GenFixtures {
    static Path out;

    public static void main(String[] args) throws Exception {
        out = Paths.get(args[0]);
        Files.createDirectories(out.resolve("v1"));
        KeyPair main = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        write("throwaway-public-key.txt", b64(raw(main)) + "\n");
        write("other-public-key.txt", b64(raw(other)) + "\n");

        String config = "{\n"
                + "  \"links\": {\n    \"discord\": \"https://discord.gg/projects\",\n    \"website\": \"https://projects.gg\"\n  },\n"
                + "  \"maintenance\": {\n    \"enabled\": false\n  },\n"
                + "  \"recommendedPack\": \"projects\",\n"
                + "  \"schema\": 1,\n"
                + "  \"servers\": [\n    {\n      \"address\": \"play.projects.gg\",\n      \"featured\": true,\n      \"id\": \"main\",\n      \"name\": \"Projects\",\n      \"port\": 25565\n    }\n  ]\n"
                + "}\n";
        String news = "{\n"
                + "  \"items\": [\n"
                + "    {\n      \"body\": \"## Yenilikler\\n\\n- Sodium 0.6\\n- Daha hızlı açılış\",\n      \"category\": \"patchNotes\",\n      \"featured\": false,\n      \"id\": \"patch-2-3\",\n      \"pinned\": true,\n      \"publishedAt\": \"2026-10-01T09:00:00Z\",\n      \"summary\": \"Yeni modlar ve performans iyileştirmeleri.\",\n      \"title\": \"2.3 yama notları\"\n    },\n"
                + "    {\n      \"category\": \"event\",\n      \"id\": \"bedwars-cup\",\n      \"publishedAt\": \"2026-10-14T18:00:00+03:00\",\n      \"summary\": \"Cumartesi akşamı başlıyor.\",\n      \"title\": \"Bed Wars turnuvası\"\n    },\n"
                + "    {\n      \"category\": \"poll\",\n      \"id\": \"scheduled\",\n      \"publishedAt\": \"2099-01-01T00:00:00Z\",\n      \"title\": \"Zamanlanmış\"\n    },\n"
                + "    {\n      \"expiresAt\": \"2026-10-10T00:00:00Z\",\n      \"id\": \"expired\",\n      \"publishedAt\": \"2026-09-01T00:00:00Z\",\n      \"title\": \"Süresi dolmuş\"\n    },\n"
                + "    {\n      \"id\": \"broken-date\",\n      \"publishedAt\": \"dün\",\n      \"title\": \"Tarihi bozuk\"\n    }\n"
                + "  ],\n  \"schema\": 1\n}\n";
        String pack = "{\n"
                + "  \"dependencies\": {\n    \"fabric-loader\": \"0.16.9\",\n    \"minecraft\": \"1.21.1\"\n  },\n"
                + "  \"files\": [\n"
                + "    {\n      \"downloads\": [\"https://cdn.modrinth.com/data/AANobbMI/versions/u1OEbNKx/sodium-fabric-0.6.0%2Bmc1.21.1.jar\"],\n      \"env\": {\"client\": \"required\", \"server\": \"unsupported\"},\n      \"fileSize\": 1048576,\n      \"hashes\": {\"sha1\": \"6f07dc1d0a287f925d7672e6884d85ceb3f339ed\"},\n      \"path\": \"mods/sodium-fabric-0.6.0+mc1.21.1.jar\"\n    }\n"
                + "  ],\n  \"formatVersion\": 1,\n  \"game\": \"minecraft\",\n  \"name\": \"Projects\",\n"
                + "  \"projects\": {\n    \"packId\": \"projects\",\n    \"servers\": [{\"address\": \"play.projects.gg\", \"name\": \"Projects\"}]\n  },\n"
                + "  \"versionId\": \"2.3.0\"\n}\n";

        String configPath = "config/launcher." + sha256(config) + ".json";
        String newsPath = "news/news." + sha256(news) + ".json";
        String packPath = "packs/projects/2.3.0.json";
        write("v1/" + configPath, config);
        write("v1/" + newsPath, news);
        write("v1/" + packPath, pack);

        String index = index(1792065600L, "2026-10-15T12:00:00Z", configPath, config, newsPath, news, packPath, pack);
        write("v1/index.json", index);
        write("v1/index.json.sig", sig(index, main));
        // Same index signed by the other key (rotation) and by the other key but labelled as main.
        write("index.other-key.sig", sig(index, other));
        write("index.mislabelled.sig", "{\n  \"keyId\": \"" + keyId(main) + "\",\n  \"signature\": \"" + b64(sign(index, other)) + "\"\n}\n");
        // Older publish (rollback) and a different publish with the same sequence.
        String older = index(1792065599L, "2026-10-15T11:59:59Z", configPath, config, newsPath, news, packPath, pack);
        write("index.older.json", older);
        write("index.older.json.sig", sig(older, main));
        String sameSeq = index(1792065600L, "2026-10-15T12:00:01Z", configPath, config, newsPath, news, packPath, pack);
        write("index.same-sequence.json", sameSeq);
        write("index.same-sequence.json.sig", sig(sameSeq, main));
        String newer = index(1792065700L, "2026-10-15T12:01:40Z", configPath, config, newsPath, news, packPath, pack);
        write("index.newer.json", newer);
        write("index.newer.json.sig", sig(newer, main));
        // Schema from the future.
        String future = index(1792065800L, "2026-10-15T12:03:20Z", configPath, config, newsPath, news, packPath, pack)
                .replace("\"schema\": 1", "\"schema\": 2");
        write("index.schema2.json", future);
        write("index.schema2.json.sig", sig(future, main));
        System.out.println("main keyId " + keyId(main) + ", other keyId " + keyId(other));
    }

    static String index(long sequence, String generatedAt, String configPath, String config, String newsPath, String news,
                        String packPath, String pack) throws Exception {
        return "{\n"
                + "  \"config\": " + docRef(configPath, config, "  ") + ",\n"
                + "  \"generatedAt\": \"" + generatedAt + "\",\n"
                + "  \"news\": " + docRef(newsPath, news, "  ") + ",\n"
                + "  \"packs\": [\n    {\n      \"id\": \"projects\",\n      \"latest\": \"2.3.0\",\n      \"manifest\": " + docRef(packPath, pack, "      ") + ",\n      \"name\": \"Projects\"\n    }\n  ],\n"
                + "  \"schema\": 1,\n"
                + "  \"sequence\": " + sequence + "\n"
                + "}\n";
    }

    static String docRef(String path, String doc, String indent) throws Exception {
        return "{\n" + indent + "  \"path\": \"" + path + "\",\n" + indent + "  \"sha256\": \"" + sha256(doc) + "\",\n"
                + indent + "  \"size\": " + doc.getBytes(StandardCharsets.UTF_8).length + "\n" + indent + "}";
    }

    static byte[] raw(KeyPair pair) {
        byte[] spki = pair.getPublic().getEncoded(); // 12-byte X.509 header + 32-byte key
        return Arrays.copyOfRange(spki, spki.length - 32, spki.length);
    }

    static String keyId(KeyPair pair) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(raw(pair))).substring(0, 16);
    }

    static byte[] sign(String text, KeyPair pair) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(pair.getPrivate());
        s.update(text.getBytes(StandardCharsets.UTF_8));
        return s.sign();
    }

    static String sig(String text, KeyPair pair) throws Exception {
        return "{\n  \"keyId\": \"" + keyId(pair) + "\",\n  \"signature\": \"" + b64(sign(text, pair)) + "\"\n}\n";
    }

    static String sha256(String text) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static void write(String name, String text) throws Exception {
        Path path = out.resolve(name);
        Files.createDirectories(path.getParent());
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }
}
