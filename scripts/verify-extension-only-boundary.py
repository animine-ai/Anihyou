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
    "ExtensionInstallStore.kt", "FileExtensionSourceRepository.kt")]
paths += [base / "repository/ExtensionShadowSyncOrchestrator.kt"]
for path in paths:
    value = path.read_text()
    assert not re.search(r"\b(AniWorldParser|AniWorldClient|AniWorldProvider|AniWorldCanonicalRouteParser|AniWorldIdentityResolver|MALSyncClient|Jsoup)\b", value), path
    assert "/anime/stream/" not in value, path
    assert "/animekalender" not in value, path
    assert "https://aniworld.to" not in value, path
sources = (base / "extension/ProductionExtensionSources.kt").read_text()
assert "bootstrap = UnavailableExtensionSourceTrustBootstrap" in sources
assert "test-pin" not in sources and "test-publisher" not in sources
installer = (base / "extension/ExtensionInstallStore.kt").read_text()
assert "verifier.verify" in installer and "rollbackBad" in installer
# EP08: the generic refresh contracts stay provider neutral; provider-named canary interfaces live elsewhere.
for contract_name in ("ReleaseRefreshContracts.kt", "ShadowRefreshPorts.kt"):
    contracts = (root / "private/release-core/src/main/kotlin/com/axiel7/anihyou/release/core/api" / contract_name).read_text()
    # The persisted source type names (ANIWORLD_*) are compatibility values and stay; no other provider mention is allowed.
    contracts_without_persisted_names = re.sub(r'"ANIWORLD_[A-Z_]+"', '""', contracts)
    assert not re.search(r"aniworld", contracts_without_persisted_names, re.IGNORECASE), contract_name + " must stay provider neutral"
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
