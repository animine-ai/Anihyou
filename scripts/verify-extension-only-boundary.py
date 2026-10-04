#!/usr/bin/env python3
"""Static V3 composition guard. Runtime failure-path proofs are separate tests."""
import hashlib
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
base = root / "private/release-data/src/main/kotlin/com/axiel7/anihyou/release/data"
paths = [base / "extension" / name for name in (
    "ExtensionHostCoordinator.kt", "ProductionExtensionDispatches.kt",
    "ProductionExtensionHostBoundary.kt", "ProductionExtensionSources.kt",
    "ExtensionInstallStore.kt", "FileExtensionSourceRepository.kt", "ReviewedExtensionSourceTrustBootstrap.kt")]
paths += [base / "repository/ExtensionShadowSyncOrchestrator.kt"]
for path in paths:
    value = path.read_text()
    assert not re.search(r"\b(AniWorldParser|AniWorldClient|AniWorldProvider|AniWorldCanonicalRouteParser|AniWorldIdentityResolver|MALSyncClient|Jsoup)\b", value), path
    assert "/anime/stream/" not in value, path
    assert "/animekalender" not in value, path
    assert "https://aniworld.to" not in value, path
sources = (base / "extension/ProductionExtensionSources.kt").read_text()
bootstrap = (base / "extension/ReviewedExtensionSourceTrustBootstrap.kt").read_text()
assert "bootstrap = ManualTrustBootstrap(reviewedSourceBootstrap(configuration), manualTrust)" in sources
manual = (base / "extension/ManualExtensionTrustStore.kt").read_text()
assert "independent.authenticate(source)?.let { return it }" in manual
assert "val record = store.find(source.url) ?: return null" in manual
assert not re.search(r"(?i)(TEST_ANCHOR|TrustFixture|trustAll|always)", manual.split("internal class ManualTrustBootstrap")[1].split("override val provisioned")[0].replace("never answers yes", ""))
assert "if (configuration == null) return UnavailableExtensionSourceTrustBootstrap" in bootstrap
assert "AppTrustPin(configuration.repositoryId, configuration.initialRootSha256" in bootstrap
assert "source.origin in pin.distributionOrigins" in bootstrap
assert not re.search(r"(?i)(TEST_ANCHOR|TrustFixture|trustAvailable\s*=\s*true|TOFU)", bootstrap)
assert "test-pin" not in sources and "test-publisher" not in sources
installer = (base / "extension/ExtensionInstallStore.kt").read_text()
assert "verifier.verify" in installer and "rollbackBad" in installer
# The view models default their presentation repository to an empty one; the plugin must still inject the real one.
assert "skipDefaultValues = false" in (root / "app/build.gradle.kts").read_text()
mapping = (base / "aniworld/AniWorldExtensionTargetSource.kt").read_text()
assert "/anime/stream/" not in mapping and "providerUrl = null" in mapping
result = {
    "staticV3CompositionPassed": True,
    "productionBootstrapFailClosed": True,
    "providerRouteBuilderAbsentInV3Host": True,
    "legacyProviderDependencyAbsentInV3Dispatch": True,
    "runtimeProofRequiredSeparately": True,
    "files": {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},
}
print(json.dumps(result, indent=2))
