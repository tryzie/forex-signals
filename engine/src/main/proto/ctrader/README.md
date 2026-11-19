# Vendored cTrader Open API protocol files

- Source: https://github.com/spotware/openapi-proto-messages
- Commit: `a9676f27f40b60569fb8a510aeadedba901b74e9` (2026-11-04)
- Licence: **MIT** (see `LICENSE.spotware`). Copyright (c) 2021 Spotware.

## Local modifications
`OpenApiMessages.proto` has six request messages **removed**, so no Java class for them is ever
generated (SIGNALS ONLY, CLAUDE.md rule 1). Each removal is marked with a `// [forex-signals]`
comment in place:

- the new-order, cancel-order, amend-order, amend-position-SL/TP and close-position requests
- the margin-call-threshold update request (it changes account settings)

The other three files are unmodified. `ReadOnlyGuardTest` fails the build if any of these messages
reappear (for example after re-vendoring a newer version).
