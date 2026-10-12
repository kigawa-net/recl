# GitHub SourceVerifier adapter — recl #35

**Status: implemented adapter and contract tests; NOT deployed or attested in production.**

This JVM module connects the recl internal `TrustedSourceAttestor` port with GitHub's REST API. It does **not** offer an HTTP/MCP endpoint, GitHub App installation broker, organization grant database, Kubernetes deployment, or the Fortis adapter. Both server-side security ports must be configured and audited before it can be enabled.

## Dependency / call flow

```text
SourceVerificationService (Core, trusted-worker-only)
  -> GitHubSourceAttestor
    -> GitHubRepositoryGrant (trusted tenant/owner/repository decision)
    -> GitHubInstallationCredentials (trusted installation token broker)
    -> GitHubRestDocumentReader (fixed-origin GET, bounded)
    -> GitHubEvidenceDigest (versioned canonical snapshot, SHA256)
  -> ContextStorage authoritative reread + CAS (Core)
```

API source identifiers are parsed only from canonical, prevalidated `https://github.com/{owner}/{repo}/commit/{40-hex-sha}`, `issues/{number}`, or `pull/{number}` references. The adapter constructs requests to `https://api.github.com/repos/{owner}/{repo}/commits|issues|pulls/{id}`, never to a client-provided host. GitHub App bearer tokens are supplied by a server-owned `GitHubInstallationCredentials` Port and placed **only in the Authorization header**, never in URLs or persistent Context metadata.

`GitHubRepositoryGrant` is a mandatory *separate* authority check. Neither Context `scopes` nor the user-provided GitHub Source URI proves tenant ownership or private-repository access. The Core's owner-only security check is not sufficient for the future public Provider; the real grant implementation must verify authenticated tenant, GitHub installation assignment and revocation, and repository access.

## Versioned evidence digest

The digest is **not the Git object's SHA**. It is SHA256 over each UTF-8 field preceded by a 4-byte big-endian length, in order. The first field is a version/domain string:

| Type | Domain | Fields after domain |
|---|---|---|
| Commit | `recl.github.commit.evidence.v1` | lowercase `owner/repo`, commit SHA, tree SHA, commit message |
| Issue | `recl.github.issue.evidence.v1` | lowercase `owner/repo`, issue number, normalized `updated_at`, title, body, state |
| Pull Request | `recl.github.pull.evidence.v1` | lowercase `owner/repo`, PR number, normalized `updated_at`, title, body, state, head SHA, base SHA |

SHA is lowercased and the timestamp is normalized by `Instant.parse(...).toString()`. A null issue/PR body is represented as an empty string. `GitHubEvidenceDigest.fromResponse(target, responseBody)` implements this projection.

**Limits:** The issue/PR digest does not verify comments, review approvals, labels, checks, events or linked resources. A GitHub Commit reference proves only that the specific SHA was resolved to the canonical API snapshot under the assigned installation credential. It does not prove that the Content's AI summary is true. Distinct GitHub API projections or additional authenticated content need separately versioned digests.

A stored `SourceSubmission.contentDigest` must equal this SHA256 projection digest to become `VERIFIED`. A changed Commit digest yields `Invalid`; a changed PR/Issue snapshot yields `Stale`. Bad API response shapes or unexpected SHAs fail closed rather than being accepted.

## Network / failure handling

- Real transport: `JdkGitHubHttpEngine` using JDK `HttpClient`, default TLS validation, redirects set to **NEVER**, connect timeout 5 seconds, per-request timeout 10 seconds.
- Response bodies are streamed from the GitHub API with an upper bound of 512 KiB (including errors); oversized responses are rejected and not stored.
- 401/403/404 are grouped as `ForbiddenOrMissing`, intentionally not disclosing private repository existence to external callers. 3xx, 429, 5xx, malformed payloads and oversized responses fail closed.
- A timeout is `OutcomeUnknown`; an I/O or remote availability failure is `Unavailable`. The source is never upgraded to verified on those outcomes.
- A successful verification receipt is bounded to 10 minutes by default and cannot outlive its installation credential minus a 30-second safety margin. If the token has under 30 seconds remaining, verification is rejected.
- Evidence includes only tenant/context/source/revision, digest, audience, server-owned verifier ID, and a non-secret diagnostic attestation ID. This ID **is not a cryptographic signature or durable replay receipt**.
- The GitHub API host is fixed, but this alone is not a complete SSRF/network egress defense. Production needs verified proxy/DNS egress policy, CA trust, installation credential rotation, approval rules, telemetry and audit logging with token scrubbing.

## Running

From the repository root:

```bash
gradle :source-github:test --no-daemon
gradle test --no-daemon
```

This project uses GitHub Actions with Gradle 8.14.3 and JDK 21. The module uses Jackson for bounded JSON parsing and JUnit for deterministic contract tests.

## Acceptance before production

- [ ] Configure **actual GitHub App installation** token broker with per-tenant repository grants, expiry/revocation and minimum permissions. No PAT/client-provided credential fallback.
- [ ] Test live GitHub API snapshots and private repository grant changes with real authenticated deployments, including provenance SHA/Digest fixture generation and API rate limits.
- [ ] Add policy-enforced outbound egress and monitoring; verify redirect/DNS/IP behavior against the deployment environment.
- [ ] Implement authenticated SourceVerifier Worker scheduling, attestation audit/TTL revalidation and revocation.
- [ ] Wire real Fortis linearizable storage, CAS, durable request receipts, leadership/restart recovery and Pinned scan before enabling remote features (recl #14/#16/#26, Fortis #44/#45/#46).
- [ ] Define Kivra `recl.memory.v1` mapping and execute cross-repository live contract tests (recl #21, Kivra #71).

**Passing fake HTTP/Grant tests validates only the adapter's internal contract. It does not enable production `sourceVerificationV1`.**
