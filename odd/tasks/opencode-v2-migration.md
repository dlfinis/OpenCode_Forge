# OpenCode v2 and IntelliJ compatibility

## Goal

Migrate the JetBrains plugin to OpenCode v2 while preserving supported IDE workflows, especially “Add Context” as an unsent terminal draft.

## Authorized scope

- Target V2; no dual V1/V2 compatibility is planned.
- Port startup/discovery, HTTP routes and DTOs, SSE events and recovery, authentication, and diff review.
- Keep “Add Context” as a draft; do not replace it with an immediately submitted prompt.
- Fix the failing V1 fake-server baseline and update tests, documentation, and IntelliJ compatibility metadata.
- Do not modify or delete existing `.atl/` files.

## Acceptance criteria and status

- [x] V2 routes and response shapes are implemented. `/api/session` retains `directory`; unsupported `directory` parameters were removed from `/api/session/active`, `/api/session/{id}`, and `/api/session/{id}/diff`.
- [x] V2 session DTO reads `location.directory` and V2 timestamps; V1 summary-diff fallback was removed.
- [x] SSE parser handles native `event:` + `data:` framing and supported JSON envelopes; tests cover V2 message shape (`type: user`) and user-message IDs for `diff?from=`.
- [x] All-location event stream is filtered safely: when location is absent, resolve session directory through `/api/session/{sessionID}` and fail closed for missing/unknown sessions; update cache on explicit location changes and clear it after reconnect.
- [x] Event path matching resolves filesystem aliases where supported; symlink coverage ran successfully on this host.
- [x] “Add Context” remains an unsent draft, inserted through the terminal input connector rather than the submit endpoint.
- [x] README and plugin metadata declare IntelliJ 2025.2+ (build 252); no upper build bound is declared.
- [x] Fake-server coverage and `./gradlew test --console=plain` passed after the V2 DTO/event corrections.
- [x] `./gradlew check --console=plain` and `git diff --check` after the last SSE-scope changes.
- [x] Final `verifyPlugin` against IntelliJ IDEA 2025.2.4 after the last SSE-scope changes: Compatible, with 2 scheduled-for-removal and 5 deprecated API usages.
- [ ] Live runtime verification of the V2 event stream. Installed CLI is v2.1.3, but the shared service reports v2.1.2; unauthenticated `curl /api/event` returned 401 and `opencode api get /api/event` timed out without events.
- [ ] Revisit API error surfacing: several client methods log HTTP failures and return nullable/empty results, which can conceal server errors.
- [ ] Determine an application-level readiness signal before writing context into a freshly launched OpenCode TUI. IntelliJ Terminal SDK connector/process readiness does not prove the TUI input loop is ready; no blind delay was added.

## Verification evidence

- Branch: `feat/opencode-v2-migration`.
- `./gradlew test --console=plain`: passed after final event-scope changes; 10 tests, 0 failures, 0 skipped.
- `./gradlew check --console=plain`: passed after the final event-scope changes.
- `git diff --check`: passed after the final event-scope changes.
- `./gradlew verifyPlugin --console=plain`: Compatible with IC 2025.2.4; 2 scheduled-for-removal and 5 deprecated API usages. Verification passed after preparing the expected empty searchable-options directory.
- `./gradlew clean test check verifyPlugin --console=plain`: failed at `prepareJarSearchableOptions` because the existing build disables `buildSearchableOptions`, leaving its expected input directory absent after `clean`; tests were FROM-CACHE. Independent `check` and sequential `verifyPlugin` passed afterward.
- Local CLI: `opencode v2.1.3`; shared service `/api/info`: `2.1.2`.
- No push, PR, merge, deployment, or `runIde` was performed.

## Known limitations and risks

- OpenCode V2 events are live-only and span server locations. Location-bearing events are filtered by canonical path and refresh session cache, including moves. Location-less events resolve via a synchronous API call on the SSE consumer thread; a slow server can pause event handling until request timeout.
- A complete turn that starts and finishes during an SSE disconnect cannot be reconstructed by active-session reconciliation, so its diff may be missed.
- Live event framing was validated against the documented contract and fake-server tests, not captured from the authenticated shared service.
- Fresh-TUI paste readiness remains unverified: a TTY connector becoming available is not evidence that OpenCode's input loop is ready. No timing-only workaround was introduced.
- `TerminalView` remains deprecated; `runIde` was not exercised.
- No upper IntelliJ build limit is declared, which is a compatibility promise that must be checked against future platform releases.

## Work-unit evidence

- Work-unit commit: `feat: migrate JetBrains plugin to OpenCode v2` (one cohesive migration; intermediate V1/V2 states are not independently usable). `.atl/` remained untouched and unstaged.
