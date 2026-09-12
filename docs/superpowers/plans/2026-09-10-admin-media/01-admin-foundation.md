# P1 — Admin Foundation

**Outcome:** An operator can grant/revoke ADMIN, and a current database administrator can create, list, read and edit managed movie drafts without seed overwrite or stale catalog cache.

**Depends on:** none.

## Files

Create:

- `backend/src/main/resources/db/migration/V2__admin_foundation.sql`
- `backend/src/main/java/com/lvfast/streaming/identity/RoleService.java`
- `backend/src/main/java/com/lvfast/streaming/identity/AdminAuthorizationManager.java`
- `backend/src/main/java/com/lvfast/streaming/audit/AuditService.java`
- `backend/src/main/java/com/lvfast/streaming/audit/JdbcAuditRepository.java`
- `backend/src/main/java/com/lvfast/streaming/ops/AdminRoleCommand.java`
- `backend/src/main/java/com/lvfast/streaming/administration/AdminMovieController.java`
- `backend/src/main/java/com/lvfast/streaming/administration/AdminMovieService.java`
- `backend/src/main/java/com/lvfast/streaming/administration/JdbcAdminMovieRepository.java`
- `backend/src/main/java/com/lvfast/streaming/administration/AdminMovieInput.java`
- `backend/src/main/java/com/lvfast/streaming/administration/AdminMovieView.java`
- `backend/src/main/java/com/lvfast/streaming/catalog/CatalogRevisionRepository.java`
- `backend/src/test/java/com/lvfast/streaming/support/ApiTestSupport.java`
- `backend/src/test/java/com/lvfast/streaming/administration/AdminFoundationHttpTest.java`
- `backend/src/test/java/com/lvfast/streaming/catalog/ManagedCatalogRevisionTest.java`
- `docs/runbooks/admin-bootstrap.md`

Modify:

- `backend/src/main/java/com/lvfast/streaming/identity/UserAccount.java`
- `backend/src/main/java/com/lvfast/streaming/identity/AuthService.java`
- `backend/src/main/java/com/lvfast/streaming/identity/JwtAccessTokenIssuer.java`
- `backend/src/main/java/com/lvfast/streaming/identity/AuthController.java`
- `backend/src/main/java/com/lvfast/streaming/identity/SecurityConfiguration.java`
- `backend/src/main/java/com/lvfast/streaming/catalog/Movie.java`
- `backend/src/main/java/com/lvfast/streaming/catalog/CatalogCache.java`
- `backend/src/main/java/com/lvfast/streaming/catalog/RedisCatalogCache.java`
- `backend/src/main/java/com/lvfast/streaming/catalog/CatalogService.java`
- `backend/src/main/java/com/lvfast/streaming/catalog/CatalogManifestImporter.java`
- `backend/src/main/resources/application.yml`
- `backend/src/test/java/com/lvfast/streaming/ArchitectureTest.java`
- `docs/api/openapi.yaml`

## Interfaces produced

```java
public interface RoleService {
    java.util.Set<String> roles(java.util.UUID userId);
    boolean isAdmin(java.util.UUID userId);
}

public interface AuditService {
    void record(java.util.UUID actorId, String actorType, String action,
                String entityType, java.util.UUID entityId, String requestId,
                java.util.Map<String,Object> before,
                java.util.Map<String,Object> after);
}
```

Admin endpoints and `AdminMovie`/`MovieInput` shapes follow [contracts.md](contracts.md). Movie revisions begin at zero. Update requests use quoted numeric `If-Match`; a stale value returns 412 and missing header returns 428.

## Acceptance criteria

- **P1-A:** Registration always grants USER and rejects caller-supplied roles. Auth responses expose roles.
- **P1-B:** `/api/v1/admin/**` checks the current database ADMIN role. Removing the role immediately changes the result from 200 to 403 for an otherwise valid token.
- **P1-C:** The operator command grants/revokes ADMIN for an existing account, records audit and refuses removal of the last administrator.
- **P1-D:** Admin creates/reads/lists/updates a DRAFT with nullable runtime; input cannot set runtime or media URLs.
- **P1-E:** Concurrent updates using the same ETag yield one success and one 412; a small `operation_request` table stores scoped idempotent command responses so repeated create returns the same movie.
- **P1-F:** First admin edit adopts a LEGACY row as MANAGED. Later manifest import cannot overwrite its metadata/genres or publication state.
- **P1-G:** Admin/import mutations bump a PostgreSQL catalog revision used in Redis keys; no wildcard `KEYS` invalidation remains.

## Implementation sequence

- [ ] Add `AdminFoundationHttpTest` with a real authenticated random-port request helper. First prove USER=403, ADMIN=200 and revoked ADMIN=403.
- [ ] Run `mvn -f backend/pom.xml --batch-mode --no-transfer-progress -Dtest=AdminFoundationHttpTest#enforcesCurrentDatabaseAdminRole test`; confirm failure is missing current-role behavior.
- [ ] Add V2 role/audit/editorial columns and `operation_request`, registration role assignment and database-backed admin authorization. Add the operator command as a conditional non-web runner invoked with `--spring.main.web-application-type=none --app.catalog.import-enabled=false --app.ops.roles="grant:username"` or `revoke:username`.
- [ ] Rerun only the named authorization test. Add and run focused tests for grant/revoke/last-admin behavior in `AdminFoundationHttpTest`.
- [ ] Add draft CRUD, validation, audit, ETag revision CAS and create idempotency. Drive each behavior with a named method in `AdminFoundationHttpTest`; run only that method after its change.

Representative concurrency assertion:

```java
assertThat(java.util.List.of(first.status(), second.status()))
    .containsExactlyInAnyOrder(200, 412);
```

- [ ] Add catalog revision namespacing and importer ownership protection. Write `ManagedCatalogRevisionTest` to cache a movie, adopt/edit it, rerun import and assert the edit remains visible.
- [ ] Run `mvn -f backend/pom.xml --batch-mode --no-transfer-progress -Dtest=ManagedCatalogRevisionTest test`.
- [ ] Update OpenAPI and run `mvn -f backend/pom.xml --batch-mode --no-transfer-progress -Dtest=OpenApiContractTest test` because the public/auth/admin contract changed.
- [ ] Run `ArchitectureTest` only if new package dependencies changed after its most recent passing result.
- [ ] Review the diff, update `README.md` ledger and write `handoffs/P1.md`. Stop; do not start P2.

## Packet completion evidence

Fresh passing output is required for `AdminFoundationHttpTest`, `ManagedCatalogRevisionTest` and `OpenApiContractTest`. Existing `AuthHttpTest` or `CatalogTestcontainersTest` is run only if its execution path was changed after those focused results. Do not run the backend suite.

Commit explicit P1 paths with message `feat(admin-media): complete P1 admin foundation` when committing is authorized.
