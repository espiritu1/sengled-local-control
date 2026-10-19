# Proposal: baseline-current-behavior

## Intent

Freeze the current behavior of the Android app (`SengledApp/`, package `com.sengled.control`) as reviewable spec deltas before Sonoff pairing-wizard work begins. With no test suite (`testDebugUnitTest` runs NO-SOURCE), these specs are the only regression baseline; undocumented quirks risk being "cleaned up" unintentionally by later phases.

## Scope

### In Scope
- Delta specs for eight domains of observed behavior (see Capabilities)
- Frozen-quirk annotations: hardcoded strings, dead code, pref duplication, README drift — recorded as current behavior, never fixed
- Future-change candidate list (fix candidates deferred to later changes)

### Out of Scope
- Any behavior change, refactor, or fix — quirks stay exactly as observed
- `SengledTools/` (gitignored separate fork)
- Adding tests or tooling

## Capabilities

### New Capabilities
- `pairing-provisioning`: 4-step Sengled wizard, UDP credential protocol, network pinning, manual add
- `device-discovery`: refresh fast path, MAC scan, static-IP handling
- `udp-control`: port-9080 JSON commands, per-operation sockets, lastPower/brightness semantics
- `mqtt-broker`: embedded MQTT 3.1.1 subset, TLS 8883, refcounting, shared notification
- `routines-schedules`: one routine per bulb, grace windows, catch-up, exact alarms
- `bulb-management`: `BulbRegistry` persistence, rename overlay, list operations
- `notifications`: shared persistent notification (id 1), event channel, permission guard
- `ui-locale-theme`: explicit locale (es default), forced-dark theme, main-screen elements

### Modified Capabilities
None — `openspec/specs/` is empty; all eight are new baseline specs.

## Approach

Convert each exploration domain into a delta spec under `specs/{domain}/spec.md` (Given/When/Then, RFC 2119), stating observed behavior only. Each spec ends with an Annotations block freezing quirks/dead code, cross-referenced to exploration sections. **Test impact:** no suite exists and this docs-only change adds none — verification is review against exploration plus `gradlew lint` / `compileDebugKotlin`.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `openspec/changes/baseline-current-behavior/proposal.md` | New | this proposal |
| `openspec/changes/baseline-current-behavior/specs/<domain>/spec.md` (×8) | New | baseline delta specs |
| `SengledApp/` (17 Kotlin files, `res/`, manifest) | Reference only | source of truth; zero edits |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Dead code fossilized as behavior (6 unused symbols) | Med | Annotate "unused — not behavior"; exclude from requirements |
| Hardcoded Spanish strings (16) conflict with apply rules | High | Freeze + annotate as violation; fix deferred |
| No automated regression check | High | Verify via review, `lint`, `compileDebugKotlin` |
| Prefs duplication: rename overlay vs registry | Med | Spec states overlay (`name_<id>`) wins |
| README drift (IPs, retry timing) | Med | Code is truth; specs cite code only |
| Aggregate spec text may exceed 400-line review budget | Med | `sdd-tasks` may chain per-domain slices |

## Rollback Plan

Docs-only change: revert the commit(s) adding proposal and specs. No runtime code touched; `openspec/specs/` stays unchanged until `sdd-archive` merges deltas.

## Dependencies

- Input: `exploration.md` (present in change folder)
- Merge into `openspec/specs/` by `sdd-archive` after verify

## Success Criteria

- [ ] Eight delta specs exist; every requirement traceable to exploration/code
- [ ] All seven exploration risks appear as annotations in at least one spec
- [ ] `git diff` touches only `openspec/` paths
- [ ] Spec corpus reviewable within budget, or explicitly chained per domain
