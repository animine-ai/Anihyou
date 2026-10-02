#!/usr/bin/env python3
"""EP06 composition audit. Runtime authority and race tests remain mandatory."""
import hashlib
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
core = root / "private/release-core/src/main/kotlin/com/axiel7/anihyou/release/core"
data = root / "private/release-data/src/main/kotlin/com/axiel7/anihyou/release/data"
paths = [core / "source/ExtensionProductPolicy.kt", core / "navigation/ProviderNavigation.kt",
         core / "navigation/ProviderEpisodeMapping.kt",
         data / "extension/FileExtensionProductPolicyRepository.kt",
         data / "extension/ExtensionProviderNavigationProductRepository.kt",
         data / "extension/FileProviderNavigationStateStore.kt",
         data / "repository/SingleSourceShadowRefreshCoordinator.kt"]
for path in paths:
    value = path.read_text()
    assert not re.search(r"\b(AniWorldParser|AniWorldClient|AniWorldProvider|MALSyncClient|Jsoup)\b", value), path
    assert not re.search(r'https?://', value), path
    assert "/anime/stream/" not in value and "/animekalender" not in value, path
    assert "R2" not in re.sub(r"//[^\n]*|/\*.*?\*/", "", value, flags=re.S), path
policy = paths[0].read_text()
assert "activeReleaseSource: ExtensionSelectionKey?" in policy
assert "activeReleaseSources" not in policy
worker = paths[-1].read_text()
assert "snapshot.activeReleaseSource ?:" in worker
assert "withCurrentSelection(snapshot)" in worker
assert "withCurrentGeneration" in worker
assert "pinned.packageGeneration" in worker
assert "${pinned.packageGeneration}" in worker
assert "providerId.value != selected.providerId" in worker
navigation = (data / "extension/ExtensionProviderNavigationProductRepository.kt").read_text()
assert "ExtensionObservationPolicy { _, _ -> false }" in navigation
assert "store.upsertSegment(segment)" in navigation
assert "withCurrentGeneration" in navigation and "packageValue.packageGeneration" in navigation
assert "generations.commit" not in navigation and "authority.project" not in navigation
composition = (root / "app/src/main/java/com/axiel7/anihyou/AnimetrackerReleaseModule.kt").read_text()
product_binding = composition.split("single<AniWorldShadowRefreshCoordinator>")[1].split("single { AniWorldClient")[0]
assert "SingleSourceShadowRefreshCoordinator(" in product_binding
assert "ExtensionShadowSyncOrchestrator(" not in product_binding
resolver = (core / "navigation/ProviderNavigation.kt").read_text()
assert "releases?.key != active" in resolver
assert "releases.policyGeneration != policy.releaseGeneration" in resolver
assert 'uri.scheme == "https"' in resolver and "withCurrentProvider" in resolver
assert "val packageGeneration: Long = 0" in resolver
state_store = (data / "extension/FileProviderNavigationStateStore.kt").read_text()
assert "val packageGeneration: Long = 0" in state_store and 'put("packageGeneration"' in state_store
assert "it.packageGeneration == packageGeneration" in state_store
print(json.dumps({
    "staticProductCompositionPassed": True,
    "activeReleaseSourceIsScalarOptional": True,
    "productWorkerSelectionAndPackageFenced": True,
    "workerAndNavigationPackageGenerationFenced": True,
    "navigationObservationAuthorityDenied": True,
    "providerUrlBuilderAbsent": True,
    "legacyAndR2FallbackAbsentInProductRoute": True,
    "runtimeProofRequiredSeparately": True,
    "files": {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},
}, indent=2))
