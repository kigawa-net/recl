# GitHub App Installation Grant / Token Broker (internal implementation)

Tracked by [recl #37](https://github.com/kigawa-net/recl/issues/37). This is the **broker protocol/transport implementation and deterministic tests**, not a deployed GitHub App or production-backed Tenant Grant database.

## Trust chain

```text
SourceVerificationService (trusted worker)
  -> GitHubSourceAttestor
    -> AuthoritativeGitHubAppAccess.canRead()
         GitHubInstallationGrantLedger.read(authenticated tenant, owner, repository)
    -> AuthoritativeGitHubAppAccess.getToken()
         fresh grant read -> GitHubRestInstallationTokenIssuer -> fresh grant read
    -> GitHubRestDocumentReader (fixed api.github.com)
    -> GitHubSourceAttestor.canRead() RECHECK after network response
    -> GitHubEvidenceDigest / Core conditional CAS
```

A `GitHubInstallationGrant` binds `tenantId`, `ownerId`, exact `GitHubRepository`, GitHub Installation ID, monotonic generation, expiry and revoked flag. No client `Scope` string or GitHub Source URL authorizes repository access. The `GitHubInstallationGrantLedger` is an injected **server-owned repository** and MUST be backed by an authenticated, strong-consistency store before production. Test Fakes only simulate this.

The broker reads the grant twice around issuance and refuses a changed/revoked generation. The GitHubSourceAttestor rechecks grants after its network call, so a revocation during a slow GET cannot produce a new verified proof. This **does not eliminate** the tiny TOCTOU gap between final grant check and Fortis CAS, nor revoke a Github-issued bearer token instantly. Production still needs token TTL, independent deny-list checks, worker fencing, and revocation-aware durable receipts.

## GitHub App installation token transport

`GitHubAppSigningIdentity` signs a short-lived RS256 App JWT with a trusted private RSA key. `GitHubRestInstallationTokenIssuer` constructs exactly:

```text
POST https://api.github.com/app/installations/{installationId}/access_tokens
Authorization: Bearer <GitHub App JWT>
Content-Type: application/json
{
  "repositories": ["<approved repository name>"],
  "permissions": {
    "contents": "read",
    "issues": "read",
    "pull_requests": "read"
  }
}
```

No PAT fallback or arbitrary URL is accepted. The JDK HTTP transport inherited from `GitHubRestDocumentReader` uses default TLS, redirects **NEVER**, finite timeout, bounded 16-KiB responses, and places tokens only in headers. The response must contain **exactly the requested repository** plus required read-only permissions; unexpected permission scopes and missing allowlist fail closed. Grants cap the returned Installation Token's effective expiry; no credential cache is used. `GitHubInstallationToken.toString()` never reveals the bearer token.

Server-owned RSA private keys and installation IDs must originate from a secured secrets broker or equivalent control plane. This module **does not create/install a GitHub App, retrieve deployment secrets, or grant a repository**. The `GitHubInstallationGrantLedger` must not be replaced with caller-controlled JSON or cached allow=true from `Context.scopes`.

## Validation and remaining production gates

Phase-1 tests use deterministic fake GrantLedger and HTTP engine with real JDK RSA signing to test: wrong tenant/owner/repo, revoke/generation race, token TTL bounds, fixed endpoint/RS256 signature, repository allowlist, read-only permissions, rate limit/timeouts and revalidation after GitHub GET.

Remaining required:
- [ ] Actual GitHub App and installation credentials/key rotation, private repository installation test and GitHub API responses under real auth
- [ ] Authenticated Tenant/Owner Identity + authoritative GrantLedger with revocation/fencing proof (Fortis #44 and recl #14)
- [ ] Workload identity/effective GitHub App permissions, audit trails with token scrubbing, network egress policy, CA/DNS/redirect fault tests
- [ ] Re-evaluate revocation before committing SourceVerification to Fortis, and expiration/revocation jobs for previously verified sources
- [ ] Bind deployment artifact SHA, secrets rotation, grant revision, live E2E, Source Verification capability to Kivra #71
- [ ] Real recl MCP/REST server and Kivra MemoryProvider matching contract

**Phase 1 completion is NOT production GitHub App or memory-provider readiness.**
