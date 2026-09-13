# Admin bootstrap runbook

The first administrator is granted by an operator command. Registration never creates an
administrator and never accepts caller-supplied roles.

## Prerequisites

- A running PostgreSQL database migrated to the latest schema (`V2__admin_foundation.sql`).
- An existing account created through normal registration.
- A local Maven build of the backend.

## Grant ADMIN

Run the backend as a non-web command with the catalog importer disabled:

```powershell
mvn -f backend/pom.xml --batch-mode --no-transfer-progress spring-boot:run `
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none --app.catalog.import-enabled=false --app.ops.roles=grant:alice"
```

This grants `ADMIN` to the account `alice` and appends a `ROLE_GRANTED` audit event.

## Revoke ADMIN

```powershell
mvn -f backend/pom.xml --batch-mode --no-transfer-progress spring-boot:run `
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none --app.catalog.import-enabled=false --app.ops.roles=revoke:alice"
```

Revocation refuses to remove the last remaining administrator and fails with a non-zero exit.

## Notes

- The command value must be exactly `grant:<username>` or `revoke:<username>`.
- `--spring.main.web-application-type=none` prevents the web server from starting.
- `--app.catalog.import-enabled=false` prevents the catalog manifest from being re-imported
  during the administrative run.
- Authorization is always evaluated against the current database role, so revoking a role
  takes effect immediately even for already-issued tokens.
