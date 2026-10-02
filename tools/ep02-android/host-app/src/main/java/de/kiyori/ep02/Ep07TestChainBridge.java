package de.kiyori.ep02;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.util.encoders.Base64;
import org.erdtman.jcs.JsonCanonicalizer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Device-generated signed packages and catalogs for the test-only EP07 proof. */
public final class Ep07TestChainBridge {
    private static final String REPOSITORY_ID = "ep07.test.repository";
    private static final String ORIGIN = "https://packages.example.org";
    private static final String SOURCE_REPOSITORY = "https://github.com/animine-ai/release-extentions";
    private static final String SOURCE_COMMIT = repeat("a", 40);
    private static final String PUBLISHER_ID = "fixture.publisher";
    private static final String EXTENSION_ID = "fixture.release";
    private static final String PROVIDER_ID = "fixture";
    private static final String KEY_DOMAIN = "AREX-EP03-PUBLIC-TEST-ONLY:";
    private static final Instant FIXTURE_TIME = Instant.parse("2026-09-29T12:00:00Z");

    private Ep07TestChainBridge() {}

    public static JSONObject generate(File directory, byte[] baseModule) throws Exception {
        if (baseModule == null || baseModule.length < 8 || baseModule[0] != 0 || baseModule[1] != 'a' ||
            baseModule[2] != 's' || baseModule[3] != 'm') {
            throw new IllegalArgumentException("a core Wasm module is required");
        }
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("fixture directory unavailable");

        Ed25519PrivateKeyParameters[] keys = new Ed25519PrivateKeyParameters[5];
        String[] keyIds = new String[5];
        JSONArray trustedKeys = new JSONArray();
        for (int i = 0; i < keys.length; i++) {
            keys[i] = testKey(i);
            byte[] publicKey = keys[i].generatePublicKey().getEncoded();
            keyIds[i] = sha256(publicKey);
            trustedKeys.put(new JSONObject().put("keyId", keyIds[i])
                .put("publicKey", Base64.toBase64String(publicKey)));
        }

        PackageArtifact v1 = packageArtifact(directory, "v1", "1.0.0-test.1", 1L,
            withMarker(baseModule, "v1"), keys[4], keyIds[4]);
        PackageArtifact failedV2 = packageArtifact(directory, "v2-failure", "2.0.0-test.2", 2L,
            withMarker(baseModule, "v2-controlled-failure"), keys[4], keyIds[4]);
        PackageArtifact v2 = packageArtifact(directory, "v2", "2.0.0-test.3", 3L,
            withMarker(baseModule, "v2-success"), keys[4], keyIds[4]);
        PackageArtifact v3 = packageArtifact(directory, "v3", "3.0.0-test.4", 4L,
            withMarker(baseModule, "v3-success"), keys[4], keyIds[4]);

        JSONObject publisher = new JSONObject().put("publisherId", PUBLISHER_ID)
            .put("extensionId", EXTENSION_ID).put("providerId", PROVIDER_ID).put("keyId", keyIds[4])
            .put("roles", new JSONArray().put("CALENDAR"))
            .put("navigation", new JSONArray().put("OVERVIEW_NAVIGATION").put("EPISODE_NAVIGATION"))
            .put("hosts", new JSONArray().put("example.org"))
            .put("notBefore", "2026-01-01T00:00:00Z").put("expiresAt", "2030-01-01T00:00:00Z");
        JSONObject rootSigned = new JSONObject().put("schemaVersion", 1).put("repositoryId", REPOSITORY_ID)
            .put("version", 1).put("expiresAt", "2030-01-01T00:00:00Z").put("keys", trustedKeys)
            .put("roles", new JSONObject()
                .put("root", new JSONObject().put("threshold", 2)
                    .put("keyIds", new JSONArray().put(keyIds[0]).put(keyIds[1]).put(keyIds[2])))
                .put("index", new JSONObject().put("threshold", 1).put("keyIds", new JSONArray().put(keyIds[3]))))
            .put("publishers", new JSONArray().put(publisher))
            .put("revokedKeys", new JSONArray()).put("revokedDigests", new JSONArray());
        write(new File(directory, "root.json"), envelope(rootSigned, keys, new int[]{0, 1}, keyIds, "ROOT"));

        byte[] canonicalRoot = canonical(rootSigned);
        JSONObject pin = new JSONObject().put("repositoryId", REPOSITORY_ID)
            .put("initialRootSha256", sha256(canonicalRoot))
            .put("distributionOrigins", new JSONArray().put(ORIGIN));
        write(new File(directory, "test-pin.json"), pin.toString().getBytes(StandardCharsets.UTF_8));

        writeIndex(directory, "index-v1.json", 1, Arrays.asList(v1), null, keys[3], keyIds);
        writeIndex(directory, "index-v2-failure.json", 2, Arrays.asList(v1, failedV2), null, keys[3], keyIds);
        writeIndex(directory, "index-v2.json", 3, Arrays.asList(v1, failedV2, v2), null, keys[3], keyIds);
        writeIndex(directory, "index-v3.json", 4, Arrays.asList(v1, failedV2, v2, v3), null, keys[3], keyIds);
        writeIndex(directory, "index-v3-revoke-v1.json", 5, Arrays.asList(v1, failedV2, v2, v3), v1, keys[3], keyIds);

        return new JSONObject().put("testTrustOnly", true).put("repositoryId", REPOSITORY_ID)
            .put("origin", ORIGIN).put("rootDigest", sha256(canonicalRoot))
            .put("v1", v1.toJson()).put("failedV2", failedV2.toJson())
            .put("v2", v2.toJson()).put("v3", v3.toJson())
            .put("indexSequences", new JSONArray().put(1).put(2).put(3).put(4).put(5))
            .put("revocationIndexKeyId", keyIds[3]);
    }

    private static PackageArtifact packageArtifact(File directory, String name, String version, long sequence,
        byte[] module, Ed25519PrivateKeyParameters signer, String keyId) throws Exception {
        byte[] notice = ("AREX TEST ONLY: deterministic EP07 fixture " + name + "; never publish or use in production.\n")
            .getBytes(StandardCharsets.UTF_8);
        String moduleDigest = sha256(module);
        JSONObject provenance = new JSONObject().put("schemaVersion", 1)
            .put("sourceRepository", SOURCE_REPOSITORY).put("sourceCommit", SOURCE_COMMIT)
            .put("licenseSpdx", new JSONArray().put("MIT"))
            .put("components", new JSONArray().put(new JSONObject().put("name", "ep02-runtime-fixture")
                .put("origin", SOURCE_REPOSITORY).put("path", "tools/ep02-android/fixture")
                .put("licenseSpdx", "MIT")))
            .put("localModifications", new JSONArray()).put("compilerVersion", "rustc-1.95.0")
            .put("sdkVersion", "wasm32-unknown-unknown").put("dependencyLockDigest", repeat("b", 64))
            .put("reproducibleBuildCommand", "bash tools/ep02-android/build-fixture.sh")
            .put("workflowIdentity", ".github/workflows/ep02-runtime-android-proof.yml")
            .put("moduleDigest", moduleDigest);
        byte[] provenanceBytes = canonical(provenance);
        JSONObject digests = new JSONObject()
            .put("module", digestRecord(module, moduleDigest))
            .put("provenance", digestRecord(provenanceBytes, sha256(provenanceBytes)))
            .put("notice", digestRecord(notice, sha256(notice)));
        JSONObject manifest = new JSONObject().put("schemaVersion", 1)
            .put("extensionId", EXTENSION_ID).put("providerId", PROVIDER_ID).put("displayName", "Runtime Fixture")
            .put("version", version).put("releaseSequence", sequence).put("hostApiMin", 1).put("hostApiMax", 1)
            .put("capabilities", new JSONArray().put("CALENDAR"))
            .put("navigationCapabilities", new JSONArray().put("OVERVIEW_NAVIGATION").put("EPISODE_NAVIGATION"))
            .put("allowedHosts", new JSONArray().put("example.org")).put("digests", digests)
            .put("publisherId", PUBLISHER_ID).put("keyId", keyId)
            .put("sourceRepository", SOURCE_REPOSITORY).put("sourceCommit", SOURCE_COMMIT)
            .put("build", new JSONObject().put("toolchainVersion", "rustc-1.95.0")
                .put("target", "wasm32-unknown-unknown").put("lockfileDigest", repeat("b", 64))
                .put("workflowIdentity", ".github/workflows/ep02-runtime-android-proof.yml"));
        byte[] canonicalManifest = canonical(manifest);
        byte[] signatureBytes = sign("AREX-PACKAGE-V1\n", canonicalManifest, signer);
        JSONObject signature = new JSONObject().put("algorithm", "Ed25519").put("keyId", keyId)
            .put("signature", Base64.toBase64String(signatureBytes));

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
        entries.put("module.wasm", module);
        entries.put("provenance.json", provenanceBytes);
        entries.put("NOTICE", notice);
        entries.put("package.sig", signature.toString().getBytes(StandardCharsets.UTF_8));
        File archive = new File(directory, name + ".arex");
        writeDeterministicZip(archive, entries);
        byte[] archiveBytes = java.nio.file.Files.readAllBytes(archive.toPath());
        return new PackageArtifact(name, version, sequence, archive, sha256(archiveBytes), archiveBytes.length,
            sha256(canonicalManifest), moduleDigest, module.length);
    }

    private static void writeIndex(File directory, String fileName, long sequence, List<PackageArtifact> packages,
        PackageArtifact revoked, Ed25519PrivateKeyParameters key, String[] keyIds) throws Exception {
        JSONArray entries = new JSONArray();
        for (PackageArtifact item : packages) {
            entries.put(new JSONObject().put("extensionId", EXTENSION_ID).put("providerId", PROVIDER_ID)
                .put("displayName", "Runtime Fixture")
                .put("navigationCapabilities", new JSONArray().put("OVERVIEW_NAVIGATION").put("EPISODE_NAVIGATION"))
                .put("publisherId", PUBLISHER_ID).put("keyId", keyIds[4]).put("version", item.version)
                .put("releaseSequence", item.sequence).put("hostApiMin", 1).put("hostApiMax", 1)
                .put("packageUrl", ORIGIN + "/dist/" + EXTENSION_ID + "/" + item.version + "/" + item.archiveDigest + ".arex")
                .put("archiveSha256", item.archiveDigest).put("archiveBytes", item.archiveBytes)
                .put("manifestSha256", item.manifestDigest).put("yanked", false)
                .put("revoked", revoked != null && item.archiveDigest.equals(revoked.archiveDigest)));
        }
        JSONObject signed = new JSONObject().put("schemaVersion", 1).put("repositoryId", REPOSITORY_ID)
            .put("rootVersion", 1).put("sequence", sequence).put("issuedAt", FIXTURE_TIME.toString())
            .put("expiresAt", "2026-10-05T12:00:00Z").put("entries", entries);
        byte[] signature = sign("AREX-INDEX-V1\n", canonical(signed), key);
        JSONObject envelope = new JSONObject().put("signed", signed).put("signatures", new JSONArray()
            .put(new JSONObject().put("algorithm", "Ed25519").put("keyId", keyIds[3])
                .put("signature", Base64.toBase64String(signature))));
        write(new File(directory, fileName), envelope.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] envelope(JSONObject signed, Ed25519PrivateKeyParameters[] keys,
        int[] signers, String[] keyIds, String domain) throws Exception {
        JSONArray signatures = new JSONArray();
        for (int signer : signers) {
            byte[] signature = sign("AREX-" + domain + "-V1\n", canonical(signed), keys[signer]);
            signatures.put(new JSONObject().put("algorithm", "Ed25519").put("keyId", keyIds[signer])
                .put("signature", Base64.toBase64String(signature)));
        }
        return new JSONObject().put("signed", signed).put("signatures", signatures).toString()
            .getBytes(StandardCharsets.UTF_8);
    }

    private static JSONObject digestRecord(byte[] bytes, String digest) throws org.json.JSONException {
        return new JSONObject().put("sha256", digest).put("bytes", bytes.length);
    }

    private static Ed25519PrivateKeyParameters testKey(int label) throws Exception {
        return new Ed25519PrivateKeyParameters(MessageDigest.getInstance("SHA-256")
            .digest((KEY_DOMAIN + label).getBytes(StandardCharsets.UTF_8)), 0);
    }

    private static byte[] sign(String domain, byte[] payload, Ed25519PrivateKeyParameters key) {
        byte[] prefix = domain.getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[prefix.length + payload.length];
        System.arraycopy(prefix, 0, message, 0, prefix.length);
        System.arraycopy(payload, 0, message, prefix.length, payload.length);
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, key);
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }

    private static byte[] canonical(JSONObject value) throws Exception {
        return new JsonCanonicalizer(value.toString()).getEncodedString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] withMarker(byte[] module, String marker) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        byte[] name = "ep07.fixture".getBytes(StandardCharsets.UTF_8);
        writeLeb(payload, name.length);
        payload.write(name);
        payload.write(marker.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream result = new ByteArrayOutputStream(module.length + payload.size() + 8);
        result.write(module);
        result.write(0);
        writeLeb(result, payload.size());
        payload.writeTo(result);
        return result.toByteArray();
    }

    private static void writeLeb(ByteArrayOutputStream out, int value) {
        int current = value;
        do {
            int next = current & 0x7f;
            current >>>= 7;
            if (current != 0) next |= 0x80;
            out.write(next);
        } while (current != 0);
    }

    private static void writeDeterministicZip(File file, Map<String, byte[]> entries) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                byte[] bytes = item.getValue();
                CRC32 crc = new CRC32();
                crc.update(bytes);
                ZipEntry entry = new ZipEntry(item.getKey());
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(bytes.length);
                entry.setCompressedSize(bytes.length);
                entry.setCrc(crc.getValue());
                entry.setTime(0L);
                output.putNextEntry(entry);
                output.write(bytes);
                output.closeEntry();
            }
        }
    }

    private static void write(File file, byte[] bytes) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            output.getFD().sync();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static final class PackageArtifact {
        final String name;
        final String version;
        final long sequence;
        final File archive;
        final String archiveDigest;
        final long archiveBytes;
        final String manifestDigest;
        final String moduleDigest;
        final int moduleBytes;

        PackageArtifact(String name, String version, long sequence, File archive, String archiveDigest,
            long archiveBytes, String manifestDigest, String moduleDigest, int moduleBytes) {
            this.name = name;
            this.version = version;
            this.sequence = sequence;
            this.archive = archive;
            this.archiveDigest = archiveDigest;
            this.archiveBytes = archiveBytes;
            this.manifestDigest = manifestDigest;
            this.moduleDigest = moduleDigest;
            this.moduleBytes = moduleBytes;
        }

        JSONObject toJson() throws org.json.JSONException {
            return new JSONObject().put("name", name).put("version", version).put("releaseSequence", sequence)
                .put("packageDigest", archiveDigest).put("packageBytes", archiveBytes)
                .put("manifestDigest", manifestDigest).put("moduleDigest", moduleDigest)
                .put("moduleBytes", moduleBytes);
        }
    }
}
