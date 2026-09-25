# CI Network Boundary

This repository may contain application/runtime code that accesses AniWorld as part of the product.

## Mandatory CI rule

GitHub Actions, CI jobs, build verification jobs, migration jobs, test jobs, helper scripts invoked by CI, and AI/Work execution used only for verification **must not make live HTTP(S) requests to `aniworld.to` or its subdomains**.

Provider behavior in CI must be verified with committed/local sanitized fixtures, mocks, fakes, or deterministic test doubles.

This restriction applies only to CI/verification execution. It does **not** prohibit the compiled application from accessing AniWorld at runtime where that is part of the product behavior.

If a future CI task appears to require live AniWorld access, stop and redesign the test around fixtures instead of adding the network request.
