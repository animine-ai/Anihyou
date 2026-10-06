# AREX package format v1

This document records the package layer used by the release extension host. It does not define the signed catalog or trust-root formats; those inputs must be authenticated before they reach `ExtensionPackageVerifier`.

## Container

An `.arex` file is a ZIP archive with exactly these five root entries:

| Entry | Contents | Maximum uncompressed size |
| --- | --- | ---: |
| `manifest.json` | UTF-8 package manifest | 64 KiB |
| `module.wasm` | Wasm core module | 8 MiB |
| `provenance.json` | UTF-8 build provenance | 256 KiB |
| `NOTICE` | License notices | 256 KiB |
| `package.sig` | UTF-8 Ed25519 signature envelope | 1 KiB |

The archive is limited to 8 MiB and its total uncompressed content to 12 MiB. Entries must be regular files in the archive root, unique, unencrypted, readable, and stored or deflated. Directory entries, links, additional entries, malformed lengths, CRC failures, and overlapping entry data are rejected.

## Manifest

The manifest is a JSON object with exactly these fields:

```json
{
  "schemaVersion": 1,
  "extensionId": "aniworld.release",
  "providerId": "aniworld",
  "displayName": "AniWorld",
  "version": "1.0.0",
  "releaseSequence": 1,
  "hostApiMin": 1,
  "hostApiMax": 1,
  "capabilities": ["CALENDAR"],
  "navigationCapabilities": ["OVERVIEW_NAVIGATION", "EPISODE_NAVIGATION"],
  "allowedHosts": ["aniworld.to"],
  "digests": {
    "module": {"sha256": "<lowercase SHA-256>", "bytes": 8},
    "provenance": {"sha256": "<lowercase SHA-256>", "bytes": 1},
    "notice": {"sha256": "<lowercase SHA-256>", "bytes": 1}
  },
  "publisherId": "animine-ai",
  "keyId": "publisher-key-1",
  "sourceRepository": "https://github.com/animine-ai/Anihyou",
  "sourceCommit": "<40 or 64 lowercase hexadecimal characters>",
  "build": {
    "toolchainVersion": "rustc-1.88.0",
    "target": "wasm32-wasip1",
    "lockfileDigest": "<lowercase SHA-256>",
    "workflowIdentity": ".github/workflows/extension-build.yml"
  }
}
```

Numbers must be safe integers. Release sequence is positive. The host API range must be exactly `1..1`. `capabilities` contains unique release `SourceRole` names; `navigationCapabilities` contains unique known navigation capability names and may be empty. At least one capability across both lists is required. `displayName` is authenticated presentation text of 1..64 Unicode scalar values, with controls and bidi formatting characters rejected; it is never a trust or Authority identifier. Host grants are unique lowercase DNS names without wildcards or IP literals. Repository URLs use HTTPS without credentials, query, or fragment. Unknown and duplicate object keys are rejected.

## Signature and provenance

All signed JSON is parsed as strict UTF-8, rejects duplicate keys, and is canonicalized with RFC 8785 JCS. The canonical manifest SHA-256 must match the authenticated catalog entry. `package.sig` has exactly `algorithm`, `keyId`, and `signature`; the algorithm is `Ed25519`, and the signature is canonical standard Base64 encoding of 64 bytes.

The signature input is the UTF-8 bytes of `AREX-PACKAGE-V1`, followed by one LF byte, followed by the JCS canonical manifest bytes. The signing key must be authorized for the exact publisher, extension, provider, release roles, navigation capabilities, and destinations. The authenticated catalog must bind `displayName` and navigation capabilities exactly to the signed manifest. No signed grant may exceed publisher/host scope. The capability set is passed to the Wasm profile verifier for export allowlisting.

`provenance.json` has exactly `schemaVersion`, `sourceRepository`, `sourceCommit`, `licenseSpdx`, `components`, `localModifications`, `compilerVersion`, `sdkVersion`, `dependencyLockDigest`, `reproducibleBuildCommand`, `workflowIdentity`, and `moduleDigest`. Repository, commit, lock digest, workflow identity, and module digest must match the signed manifest. Every content entry is checked against its signed byte count and SHA-256 before the package can be returned.

The verifier requires a Wasm core-module profile validator. The package layer does not grant guest access to network or Android APIs; destination-bound transport and runtime isolation remain host responsibilities.
