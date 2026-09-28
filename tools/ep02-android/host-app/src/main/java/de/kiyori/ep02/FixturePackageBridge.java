package de.kiyori.ep02;

import com.axiel7.anihyou.release.core.extension.NavigationCapability;
import com.axiel7.anihyou.release.core.extension.SourceRole;
import com.axiel7.anihyou.release.data.extension.AuthorizedExtensionPublisherKey;
import com.axiel7.anihyou.release.data.extension.CombinedWasmModuleProfileVerifier;
import com.axiel7.anihyou.release.data.extension.ExtensionPackageVerifier;
import com.axiel7.anihyou.release.data.extension.StrictWasmModuleProfileVerifier;
import com.axiel7.anihyou.release.data.extension.VerifiedCatalogPackageBinding;
import com.axiel7.anihyou.release.data.extension.VerifiedExtensionPackage;
import com.axiel7.anihyou.release.data.extension.WasmtimeNativeModuleProfileVerifier;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.util.encoders.Base64;
import org.erdtman.jcs.JsonCanonicalizer;

/** Test-only bridge. Java deliberately exercises Kotlin-internal package verifier types. */
public final class FixturePackageBridge {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final Set<NavigationCapability> NAVIGATION =
        EnumSet.of(NavigationCapability.OVERVIEW_NAVIGATION, NavigationCapability.EPISODE_NAVIGATION);

    private FixturePackageBridge() {}

    public static VerifiedExtensionPackage verify(File directory, byte[] module) throws Exception {
        byte[] provenance = provenance(module);
        byte[] notice = "SPDX-License-Identifier: MIT\n".getBytes(StandardCharsets.UTF_8);
        String manifest = manifest(module, provenance, notice);
        byte[] canonical = new JsonCanonicalizer(manifest).getEncodedString().getBytes(StandardCharsets.UTF_8);

        Ed25519PrivateKeyParameters privateKey =
            new Ed25519PrivateKeyParameters(seed(), 0);
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        byte[] domain = "AREX-PACKAGE-V1\n".getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[domain.length + canonical.length];
        System.arraycopy(domain, 0, message, 0, domain.length);
        System.arraycopy(canonical, 0, message, domain.length, canonical.length);
        signer.update(message, 0, message.length);
        String signature = Base64.toBase64String(signer.generateSignature());

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
        entries.put("module.wasm", module);
        entries.put("provenance.json", provenance);
        entries.put("NOTICE", notice);
        entries.put("package.sig",
            ("{\"algorithm\":\"Ed25519\",\"keyId\":\"fixture-key-1\",\"signature\":\"" +
                signature + "\"}").getBytes(StandardCharsets.UTF_8));

        File archive = new File(directory, "ep02-runtime-fixture.arex");
        writeDeterministicZip(archive, entries);
        byte[] archiveBytes = java.nio.file.Files.readAllBytes(archive.toPath());

        VerifiedCatalogPackageBinding catalog = new VerifiedCatalogPackageBinding(
            "fixture.release",
            "fixture",
            "Runtime Fixture",
            NAVIGATION,
            "fixture-publisher",
            "fixture-key-1",
            "1.0.0",
            1L,
            sha256(archiveBytes),
            archiveBytes.length,
            sha256(canonical),
            false
        );
        AuthorizedExtensionPublisherKey publisherKey = new AuthorizedExtensionPublisherKey(
            "fixture-key-1",
            "fixture-publisher",
            "fixture.release",
            "fixture",
            privateKey.generatePublicKey().getEncoded(),
            1L,
            EnumSet.of(SourceRole.CALENDAR),
            NAVIGATION,
            java.util.Collections.singleton("example.org"),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2027-01-01T00:00:00Z"),
            false
        );
        ExtensionPackageVerifier verifier = new ExtensionPackageVerifier(
            new CombinedWasmModuleProfileVerifier(new WasmtimeNativeModuleProfileVerifier())
        );
        return verifier.verify(
            archive,
            catalog,
            publisherKey,
            EnumSet.of(SourceRole.CALENDAR),
            java.util.Collections.singleton("example.org"),
            1,
            "wasmtime-48.0.3-cranelift-android",
            NOW
        );
    }

    /** Proves the structural reader alone accepts an opcode proposal disabled by native Wasmtime. */
    public static void assertNativeFeatureGateRejectsSimd() {
        byte[] module = simdVector();
        new StrictWasmModuleProfileVerifier().verify(module, NAVIGATION);
        boolean rejected = false;
        try {
            new WasmtimeNativeModuleProfileVerifier().verify(module, NAVIGATION);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        if (!rejected) throw new AssertionError("SIMD opcode passed native frozen feature gate");
    }

    private static byte[] seed() {
        byte[] result = new byte[32];
        for (int i = 0; i < result.length; i++) result[i] = (byte)(i + 1);
        return result;
    }

    private static String manifest(byte[] module, byte[] provenance, byte[] notice) {
        return "{\"schemaVersion\":1,\"extensionId\":\"fixture.release\",\"providerId\":\"fixture\"," +
            "\"displayName\":\"Runtime Fixture\",\"version\":\"1.0.0\",\"releaseSequence\":1," +
            "\"hostApiMin\":1,\"hostApiMax\":1,\"capabilities\":[\"CALENDAR\"]," +
            "\"navigationCapabilities\":[\"OVERVIEW_NAVIGATION\",\"EPISODE_NAVIGATION\"]," +
            "\"allowedHosts\":[\"example.org\"],\"digests\":{" +
            "\"module\":{\"sha256\":\"" + sha256(module) + "\",\"bytes\":" + module.length + "}," +
            "\"provenance\":{\"sha256\":\"" + sha256(provenance) + "\",\"bytes\":" + provenance.length + "}," +
            "\"notice\":{\"sha256\":\"" + sha256(notice) + "\",\"bytes\":" + notice.length + "}}," +
            "\"publisherId\":\"fixture-publisher\",\"keyId\":\"fixture-key-1\"," +
            "\"sourceRepository\":\"https://github.com/animine-ai/Anihyou\",\"sourceCommit\":\"" + repeat("a", 40) + "\"," +
            "\"build\":{\"toolchainVersion\":\"rustc-1.95.0\",\"target\":\"wasm32-unknown-unknown\"," +
            "\"lockfileDigest\":\"" + repeat("b", 64) + "\",\"workflowIdentity\":\".github/workflows/ep02-runtime-android-proof.yml\"}}";
    }

    private static byte[] provenance(byte[] module) {
        String value = "{\"schemaVersion\":1,\"sourceRepository\":\"https://github.com/animine-ai/Anihyou\"," +
            "\"sourceCommit\":\"" + repeat("a", 40) + "\",\"licenseSpdx\":[\"MIT\"],\"components\":[]," +
            "\"localModifications\":[],\"compilerVersion\":\"rustc-1.95.0\",\"sdkVersion\":\"wasm32-unknown-unknown\"," +
            "\"dependencyLockDigest\":\"" + repeat("b", 64) + "\",\"reproducibleBuildCommand\":\"cargo build --locked --release\"," +
            "\"workflowIdentity\":\".github/workflows/ep02-runtime-android-proof.yml\",\"moduleDigest\":\"" + sha256(module) + "\"}";
        return value.getBytes(StandardCharsets.UTF_8);
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

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) out.append(String.format("%02x", value & 0xff));
            return out.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static byte[] simdVector() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, 0, 97, 115, 109, 1, 0, 0, 0);

        ByteArrayOutputStream types = new ByteArrayOutputStream();
        write(types, 4,
            0x60, 2, 0x7f, 0x7f, 1, 0x7f,
            0x60, 1, 0x7f, 1, 0x7f,
            0x60, 2, 0x7f, 0x7f, 0,
            0x60, 2, 0x7f, 0x7f, 1, 0x7e);
        section(out, 1, types.toByteArray());

        ByteArrayOutputStream imports = new ByteArrayOutputStream();
        write(imports, 1);
        name(imports, "arex_v1"); name(imports, "diagnostic"); write(imports, 0, 0);
        section(out, 2, imports.toByteArray());

        section(out, 3, bytes(6, 1, 2, 3, 3, 3, 3));
        section(out, 5, bytes(1, 1, 1, 2));

        ByteArrayOutputStream exports = new ByteArrayOutputStream();
        String[] names = {"memory","arex_alloc","arex_free","plan_requests","parse_responses","plan_navigation","parse_navigation"};
        write(exports, names.length);
        for (int i = 0; i < names.length; i++) {
            name(exports, names[i]);
            if (i == 0) write(exports, 2, 0);
            else write(exports, 0, i);
        }
        section(out, 7, exports.toByteArray());

        ByteArrayOutputStream code = new ByteArrayOutputStream();
        write(code, 6);
        body(code, bytes(0, 0x41, 0, 0x0b));
        body(code, bytes(0, 0x0b));
        ByteArrayOutputStream simd = new ByteArrayOutputStream();
        write(simd, 0, 0xfd, 0x0c);
        for (int i = 0; i < 16; i++) write(simd, 0);
        write(simd, 0x1a, 0x42, 0, 0x0b);
        body(code, simd.toByteArray());
        body(code, bytes(0, 0x42, 0, 0x0b));
        body(code, bytes(0, 0x42, 0, 0x0b));
        body(code, bytes(0, 0x42, 0, 0x0b));
        section(out, 10, code.toByteArray());
        return out.toByteArray();
    }

    private static void section(ByteArrayOutputStream out, int id, byte[] payload) {
        if (payload.length >= 128) throw new AssertionError("fixture section unexpectedly needs multi-byte LEB");
        write(out, id, payload.length);
        out.write(payload, 0, payload.length);
    }

    private static void body(ByteArrayOutputStream code, byte[] body) {
        if (body.length >= 128) throw new AssertionError("body too large");
        write(code, body.length);
        code.write(body, 0, body.length);
    }

    private static void name(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        write(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte)values[i];
        return result;
    }

    private static void write(ByteArrayOutputStream out, int... values) {
        for (int value : values) out.write(value);
    }
}
