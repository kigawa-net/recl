# Pinned Context: authoritative enumeration and bounded selection

Status: **internal Core slice only**. This is not yet a production-ready Pinned Memory API or Claude Code MCP tool. Parent: [recl #26](https://github.com/kigawa-net/recl/issues/26).

## Intended flow

```text
authenticated client
  -> TrustedPinnedQueryGateway (SessionAuthenticator interface)
  -> PinnedContextReader
  -> ContextStorage.enumerateTenant (complete authoritative snapshot OR explicit error)
  -> per-ID authoritative ContextStorage.get
  -> tenant / owner / tombstone / pinned / CONFIRMED / exact scope filter
  -> stable id sort -> token-counted selection -> bounded ContextSnapshot list
```

**No Qdrant dependency**. This allows a future Pinned endpoint to work when semantic search is degraded, provided Fortis can prove a complete and consistent enumeration of Context IDs.

## Existing implementation contract

- `ContextAggregate.pinned` defaults to `false`. Create can request an initial pin; update/unpin through `ContextPatch.pinned` is a semantic CAS change and increments `contentRevision`. Index ACK does not change the logical revision.
- The internal mutation canonical digest now uses **`recl.mutation.v2`** to include the new pinned create/patch fields (version bump is intentional). v1 receipts were never a durable public protocol; any migration to persistent receipt storage must explicitly retain/translate old receipts. This is independent of the proposed external `recl.memory.v1` REST contract.
- A new `ContextStorage.enumerateTenant(tenantId,maxItems)` primitive defaults to `Unsupported`; it must return `Complete`, `Unavailable`, or `TooLarge`, not an incomplete prefix as a successful result. The test-only InMemory adapter returns a deterministic, atomically captured snapshot for up to `maxItems` entries.
- The caller's scope list is a **relevance filter**. Owner and tenant authorization come from the authenticated principal. The `global` scope applies only inside the authenticated tenant, never across tenants. Scopes are currently **exact-match**; hierarchy/prefix semantics are deferred.
- Only confirmed, pinned, not-tombstoned, owner-authorized contexts are selected. An explicit Direct Get can still retrieve inactive historical records, but automatic pinned injection cannot.
- Every enumerated Context ID is **reread** from the authoritative storage before selection. Revoked ownership or deletion observed on re-read removes it. Any required read returning `Unavailable` causes the entire request to fail, never a partial context set misreported as complete.
- Stable selection order is Context ID ascending. Token-count selection returns `usedTokens` and `limited`; `limited=true` means some otherwise eligible items were skipped due to count/budget. Unauthorized entries are not counted in the public result.
- The injectable `ContextTokenCounter` must count the **exact final prompt rendering**, including citation metadata and separators. The tests use a deterministic content-length counter, **which is not a real tokenizer or a proof of a model budget**. Production must also bound surrounding instructions and transport framing.

## Consistency boundaries

The InMemory adapter provides a consistent snapshot of keys at enumeration time, and a **later** authoritative read of each object. It is possible for a Context to be created/pinned *after* the snapshot; it will not be in that request's result. There is no global atomic snapshot covering enumeration, all ACL checks and network response.

Future Fortis implementation must define a snapshot/cursor/pagination contract or atomically maintained pinned index with clear linearization semantics, and must revalidate permissions before sending data. The current single-response enumeration is bounded at 10,000 objects and returns `TooLarge` instead of fabricating completion. Large tenants require a proven paginated enumeration design before production enablement.

Scope and security blockers:

- Production `SessionAuthenticator` (OIDC/service principal verification, tenant grants) is not implemented.
- ContextAggregate does not yet track validFrom/validUntil, sensitivity, priority, provenance, or full RBAC. **Automatic Pinned retrieval must not be enabled for production before these policies are added and verified**.
- No public MCP/REST pinned endpoint has been implemented.
- Fortis does not yet supply a verified authoritative scan capability to recl; [Fortis #44](https://github.com/kigawa-net/fortis/issues/44) conditional KV batch alone does not guarantee `scanAtRevision`.
- Pin selection from a live multi-node Fortis snapshot is **unsupported** until [recl #14](https://github.com/kigawa-net/recl/issues/14) verifies real scan, CAS, read and crash-recovery behavior.
- Kivra integration requires [recl #21](https://github.com/kigawa-net/recl/issues/21) and [Kivra #71](https://github.com/kigawa-net/kivra/issues/71) contract/capability tests, not merely Fake test success.

## Acceptance cases covered by the internal test adapter

Scope/owner/tenant/status/tombstone filtering, deterministic size and token-budget limits, CAS pin/unpin & request digest replay, missing enumeration capability and over-limit errors, required read failure, scan-to-read owner revocation and tombstone change, authentication failure, and index ACK semantic revision invariance.

The complete production acceptance criteria in recl #26 remain **open**.
