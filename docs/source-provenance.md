# Context Source Provenance and Verification (internal Core)

Status: **unverified client provenance only**. [recl #30](https://github.com/kigawa-net/recl/issues/30). This design does **not** ship an external source verifier, network fetcher, signed provider attestation, REST/MCP API, or production Fortis backend.

## Model

`ContextSource` carries `sourceId`, `SourceType`, canonical `uri`, optional locator, retrievedAt timestamp, SHA-256 content digest, `SourceTrust`, and a separate `VerificationState`.

Source types align with Kivra's memory module:

| Type | Canonical identity | Explicit restriction |
| --- | --- | --- |
| GITHUB_COMMIT | `https://github.com/{owner}/{repo}/commit/{40-hex-sha}` | A branch or tag is not immutable evidence |
| GITHUB_PR / GITHUB_ISSUE | `https://github.com/{owner}/{repo}/pull/{n}` or `issues/{n}` | Mutable references, recorded as UNVERIFIED without a provider proof |
| FILE | `repo://{owner}/{repo}/{40-hex-sha}/{safe/path}` | Workspace-local absolute paths cannot be persisted |
| EXKES_ARTIFACT / EXKES_LOG | `exkes://{environment}/{executionId}/artifact|log/{sha256-hex}` | Not a signed temporary URL; the execution and digest must be immutable |
| FUSHA_TASK | `fusha://{project}/{taskId}` | Not an authorization grant |
| USER_MESSAGE | `conversation://{conversationId}/{messageId}` | Not proof the user made a particular claim |
| URL | `https://{public-host}/{safe/path}` | No query/fragment/userinfo/custom ports; no network fetch in Core |

URI syntax is deliberately restrictive: raw percent escapes, userinfo, queries, fragments, path traversal, and credential-like path segments are rejected. This avoids accidentally persisting query tokens or presigned URLs. File references use immutable Git SHA rather than a host filesystem path. The lightweight host checks are **not** SSRF protection: any future URL fetcher must independently enforce DNS/IP validation on every redirect, network egress, TLS and time/byte limits.

A `SourceSubmission` is client-originated and has **no verification field**. The TrustedMutationGateway normalizes/validates at most 16 sources per Context, enforces unique IDs and returns typed `InvalidRequest` on invalid references. The conversion to `ContextSource` always starts as `UNVERIFIED`. Even a user-supplied `PRIMARY_SOURCE` claim is not equivalent to server verification.

`AGENT_INFERRED + UNVERIFIED` evidence cannot be used to create/transition a Context to `CONFIRMED`. The internal Future SourceVerifier may issue `VERIFIED`/ `STALE` / `INVALID` only after source-specific authenticity and content-digest checks; no client-facing verification endpoint is included in this PR. Caller-supplied URLs, Scope, GitHub repo names, exkes IDs, or checksums do not establish permissions.

## CAS, receipts, and reading

- Sources are stored in the same authoritative Context Aggregate as status, ACL, semantic revision, and index intent. A `ContextPatch.sources` of `null` leaves them unchanged and an empty list removes all sources.
- Source mutations use the existing expected semantic revision and physical CAS loop. The operation receipt hash now uses **`recl.mutation.v4`**, binding an ordered-by-source-ID canonical list and every source field. Field order on input is not semantic; changed URI/trust/digest/retrieval timestamp/locator is.
- `ContextSnapshot.sources` exposes safe **metadata** to a principal already authorized by Tenant/Owner/Tombstone checks, not internal receipts or physical CAS tokens. A response must preserve `verification` and `trust`; renderers must not present UNVERIFIED as a proven primary source.
- Automatic Pinned injection now requires VERIFIED for any non-USER_STATED source. Owner-attributed USER_STATED context may be injected as a user claim, not an external truth. New PRIMARY_SOURCE/AGENT_OBSERVED/AGENT_INFERRED sources remain UNVERIFIED until an independent trusted verifier is implemented; the conservative selection policy excludes them. This deliberately limits recall completeness.
- A confirmed Context without source metadata remains a legacy allowable case in the internal Core; production content provenance requirements can be tightened in a later version.
- InMemoryAdapter copies the caller's List of Sources on create/write/read to prevent accidental external list mutation without CAS. It is not durable, nor a Fortis compatibility claim.
- Versioning: v1/v2/v3 receipts are **not** interchangeable with v4. Any future durable Fortis receipt migration must retain replay identities and explicitly plan canonical format upgrades. `recl.memory.v1` is a separate, unimplemented public Provider contract.

## Still required before production

1. Verified source adapter and immutable GitHub/exkes/fusha-task source proof, with credential-safe fetching and authenticated Provider identity.
2. Normalized provider DTO and Kivra Fake-to-recl Contract Suite, including SourceTrust, Sensitivity, response loss, and stale/invalid verification claims ([recl #21](https://github.com/kigawa-net/recl/issues/21), [Kivra #71](https://github.com/kigawa-net/kivra/issues/71)).
3. Fortis durable authoritative Context + Receipt/Status and audit recovery ([recl #14](https://github.com/kigawa-net/recl/issues/14), [Fortis #44](https://github.com/kigawa-net/fortis/issues/44)); complete Pinned retrieval still needs Fortis [#45](https://github.com/kigawa-net/fortis/issues/45).
4. Production OIDC/RBAC/Repository Grants, provenance-aware prompt rendering and model token counts, and security review of unsafe/untrusted retrieved content.

**Do not enable recl as a production memory writer, source verifier, or automatic Pinned provider based on these internal Core tests alone.**
