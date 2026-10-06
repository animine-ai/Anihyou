package de.kiyori.ep02;

import com.axiel7.anihyou.release.core.extension.SourceRole;
import com.axiel7.anihyou.release.data.extension.*;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import org.json.JSONObject;

/** Only in the standalone proof APK. No bootstrap or test key enters the product app. */
public final class Ep05TestTrustBridge {
    private Ep05TestTrustBridge() {}

    public static VerifiedExtensionPackage verify(File chain) throws Exception {
        JSONObject pinJson = new JSONObject(new String(Files.readAllBytes(new File(chain, "test-pin.json").toPath()), "UTF-8"));
        HashSet<String> origins = new HashSet<>();
        for (int i = 0; i < pinJson.getJSONArray("distributionOrigins").length(); i++) {
            origins.add(pinJson.getJSONArray("distributionOrigins").getString(i));
        }
        AppTrustPin pin = new AppTrustPin(pinJson.getString("repositoryId"), pinJson.getString("initialRootSha256"), origins);
        ExtensionTrustVerifier trust = new ExtensionTrustVerifier(pin);
        Instant acceptedAt = Instant.parse("2026-09-29T12:00:00Z");
        TrustedRoot root = trust.root(Files.readAllBytes(new File(chain, "root.json").toPath()), null, acceptedAt);
        TrustedIndex index = trust.index(Files.readAllBytes(new File(chain, "index.json").toPath()), root, null, acceptedAt);
        if (index.getPackages().size() != 1) throw new IllegalArgumentException("one test package required");
        IndexedPackage entry = index.getPackages().get(0);
        return new ExtensionPackageVerifier(new CombinedWasmModuleProfileVerifier(new WasmtimeNativeModuleProfileVerifier()))
            .verify(new File(chain, "aniworld-test.arex"), entry.getBinding(), trust.publisher(root, entry, acceptedAt),
                EnumSet.allOf(SourceRole.class), new HashSet<>(Arrays.asList("aniworld.to")), 1, "wasmtime-48.0.3", acceptedAt);
    }
}
