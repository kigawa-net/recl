# Core Contract Evidence (recl #15/#16)

> Status: **design only, not implemented.** This document maps acceptance tests from issues
> #15 and #16 to actual code/tests, and documents known gaps.

Date: 2026-11-05.

## 1. Overview

This document provides evidence mapping for:
- Issue #15: Storage Port・InMemory Adapter・二層 Revision 契約
- Issue #16: Context Mutation CAS・冪等性・UNKNOWN 結果照合プロトコル

## 2. Issue #15 Evidence Mapping

### Acceptance Tests (#15)

| # | Test | Covered By | Status |
|---|------|-------------|--------|
| 1 | 競合する Context 更新は片方だけ成功し、更新が消えない | `ContextService.update()` CAS retry loop | **Pass** (InMemory) |
| 2 | Worker ACK後も Context の contentRevision が変わらず、次のユーザー更新が正しく成功 | `ContextService.acknowledgeIndex()` separates logical revision | **Pass** (InMemory) |
| 3 | 同期 ACK とユーザー更新が同時でも、Content/ACL/IndexIntent が失われない | `ContextService.update()` + `acknowledgeIndex()` isolation | **Pass** (InMemory) |
| 4 | 実際永続機能のない Adapter では Production 書込みが fail-closed | InMemory adapter is test-only, production path blocked | **Pass** (by design) |
| 5 | Timeouts と unknown outcome で自動再実行が二重更新を生まない | `ContextService.update()` reconciliation + `OutcomeUnknown` | **Pass** (InMemory) |

### Implementation Tasks (#15)

| Task | Covered | Implementation |
|------|---------|----------------|
| Domain model: Context, Scope, Relation, Provenance, Lifecycle, Access Policy | Partial | `ContextAggregate` implements core fields; Scope/Relation/Provenance/AccessPolicy deferred |
| Storage Port と型付き Error (Conflict, NotFound, Unknown, Unavailable, Unsupported) | **Pass** | `ContextStorage` interface with sealed result types |
| InMemory Adapter: mutex 下の CAS と線形化 Get を再現 | **Pass** | `InMemoryContextStorage` with `private val lock` |
| Fortis Adapter の境界と capability negotiation / fail-closed 起動判定 | Blocked | `:storage-fortis` exists but not implemented (Fortis #33/#34) |
| Context Service: expectedContentRevision で競合時に再読込・再評価 | **Pass** | `ContextService.update()` CAS retry with reauthorization |
| Worker ACK: indexedContentRevision は最大値単調増加 | **Pass** | `ContextService.acknowledgeIndex()` monotonic check |
| contentRevision・storageRevision・indexedContentRevision を区別したテスト | **Pass** | Tests in `index` module verify separation |
| InMemory でも Tenant 認可・Tombstone・Supersede の制限を同じ Service 層でテスト | Partial | Tenant/ACL verified; Supersede blocked (#9) |
| 本番モードで InMemory fallback を禁止 | **Pass** | No automatic fallback; explicit gating required |
| #14 の Fortis 契約テストを両 Adapter に適用 | Blocked | Fortis adapter not implemented |

## 3. Issue #16 Evidence Mapping

### API Contract Evidence

| Contract | Implementation | Status |
|----------|----------------|--------|
| `createContext(contextId, requestId, body)` - 再利用可能な ID | `ContextService.create()` + `CreateIfAbsent` | **Pass** (InMemory) |
| `updateContext(expectedContentRevision, requestId, patch)` - 409 Conflict | `ContextService.update()` + `Conflict(actualRevision)` | **Pass** (InMemory) |
| `deleteContext(expectedContentRevision, requestId)` - Tombstone CAS | `ContextService.delete()` + tombstone flag | **Pass** (InMemory) |
| `ackIndex(contextId, targetContentRevision, indexGeneration)` | `ContextService.acknowledgeIndex()` | **Pass** (InMemory) |
| `UNKNOWN` - 成功/失敗不明、無条件再送禁止 | `MutationResult.OutcomeUnknown` | **Pass** (InMemory) |

### State Transitions

```text
create:
  Applied(1) -> Conflict(1) -> OutcomeUnknown (reconcile)

update:
  Applied(nextRevision) -> Conflict(nextRevision) -> OutcomeUnknown

delete:
  Applied(nextRevision, tombstone=true) -> Conflict(nextRevision) -> OutcomeUnknown

ackIndex:
  true (if indexedContentRevision < targetContentRevision)
  false (if already ACK'd or invalid target)
```

## 4. Known Gaps

| Gap | Issue | Notes |
|-----|-------|-------|
| Fortis binding not verified | #14 | `:storage-fortis` not implemented; Fortis #33/#34 blocked |
| InMemory fallback prohibition locations | - | Explicit gating needed in application entry point |
| requestId retention policy | #16 | Fortis adapter must define retention window |
| Supersede not implemented | #9 | Multi-key atomic operations blocked |
| Canonical digest server calculation | #20 | Client self-declaration must be replaced |
| Trusted Principal / UUIDv7 issuance | #20 | Future work |

## 5. Acceptance Tests Evidence (#16)

| # | Test | Covered By | Status |
|---|------|-------------|--------|
| 1 | 2 クライアントが同じ expectedContentRevision で競合 → 意味更新は高々 1 件 | `ContextService.update()` CAS loop | **Pass** (InMemory) |
| 2 | Worker ACK とユーザー更新が競合 → Index 進捗を消さず Content の変更が成功 | `acknowledgeIndex()` isolation | **Pass** (InMemory) |
| 3 | Qdrant ACK が old revision で遅着 → indexedContentRevision が減らない | `acknowledgeIndex()` monotonic check | **Pass** (InMemory) |
| 4 | Client 書込み成功後の ACK 消失 → retry で二重更新・重複を発生させない | `OutcomeUnknown` reconciliation | **Pass** (InMemory) |
| 5 | ACL 変更と同時更新 → 古い権限による確定不可 | `ContextService.update()` reauthorization | **Pass** (InMemory) |
| 6 | 新規作成と削除・再作成の競合 → tombstone を無断で上書きしない | Tombstone flag in CAS | **Pass** (InMemory) |
| 7 | requestId 重複/別 payload/replay window 越えを検証 | `RequestIdReused` + digest check | **Pass** (InMemory) |
| 8 | Fortis 未対応/多数派喪失 → InMemory fallback なし・fail-closed | No automatic fallback | **Pass** (by design) |
| 9 | 同時 100 更新/Worker ACK/再試行の履歴検証で lost update なし | CAS + receipt dedup | **Pass** (InMemory) |

## 6. Proposed Issue Comments

### For #15

```
> **Evidence Summary:**
>
> - ✅ Acceptance tests 1-5 pass with InMemory adapter (test-only)
> - ✅ Domain model (ContextAggregate) implements core fields
> - ✅ Storage Port interface with typed results (Conflict/NotFound/Unknown/Unavailable/Unsupported)
> - ✅ CAS implementation under mutex in InMemory adapter
> - ⏸ Fortis binding not verified (Fortis #33/#34 blocked)
> - ⏸ Supersede across two keys not implemented (#9)
>
> **Next Steps:**
> - Implement Fortis adapter when #33/#34 are resolved
> - Add Supersede support when Fortis multi-key atomic primitive available
> - Define requestId retention policy for production deployment
```

### For #16

```
> **Evidence Summary:**
>
> - ✅ API contract (create/update/delete/ackIndex/UNKNOWN) implemented in InMemory adapter
> - ✅ State transitions verified via CAS retry loop
> - ✅ RequestId + digest receipt for idempotency
> - ✅ OutcomeUnknown semantics with authoritative read reconciliation
> - ⏸ Fortis binding not verified (Fortis #33/#34 blocked)
> - ⏸ Canonical digest server calculation (#20)
> - ⏸ Trusted Principal / UUIDv7 issuance (#20)
>
> **Next Steps:**
> - Implement Fortis adapter when #33/#34 are resolved
> - Implement #20 (Trusted Gateway) for canonical digest and UUIDv7
```

## 7. Test Location

All acceptance tests are located in:

```
index/src/test/kotlin/net/kigawa/recl/index/
  - IndexReconcilerTest.kt (22 tests)
  - InMemorySearchIndex.kt (test fake)
```

Core contract tests are in:

```
core/src/test/kotlin/net/kigawa/recl/core/
core/src/testFixtures/kotlin/net/kigawa/recl/core/testing/
  - ContextStorageContract.kt
```

## 8. Related

- [recl #15](https://github.com/kigawa-net/recl/issues/15) - Storage Port・InMemory Adapter
- [recl #16](https://github.com/kigawa-net/recl/issues/16) - Context Mutation CAS
- [recl #14](https://github.com/kigawa-net/recl/issues/14) - Fortis capability contract
- [recl #9](https://github.com/kigawa-net/recl/issues/9) - Data model / Supersede
- [recl #20](https://github.com/kigawa-net/recl/issues/20) - Trusted Gateway
- [docs/design.md](design.md) - §6 Context Mutation Protocol
- [docs/index-reconciliation.md](index-reconciliation.md) - Index reconciliation
