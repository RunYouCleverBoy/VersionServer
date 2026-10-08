# System Spec: Version Server HTTP API

## Purpose

Provide a JWT-authenticated HTTP API for publishing and retrieving versioned project files, with Admin and Client roles and per-project authorization for Clients.

## Actors

| Actor | Description |
|-------|-------------|
| Admin | Full operational control: enroll users, grant project access, upload/delete files; sees every project |
| Client | Authenticated consumer; sees only granted projects; may list/download those files |
| Anonymous | May call login/logout, load the web client static assets, and read `/doc` documentation |

## Invariants

1. Every protected operation requires a valid JWT issued by login (Bearer header and/or `VS_TOKEN` cookie). Auth is cryptographic JWT verification, not an opaque server-side session map.
2. Missing or invalid JWTs yield `401`.
3. Wrong role for an Admin-only operation yields `403`.
4. Project access for Clients is explicit (grant). Admins bypass project grants.
5. Grants may only target **existing** enrolled user ids. Unknown user ids yield `404`.
6. Users are created only by Admin enrollment (plus automatic seed of default users when the JSON store is missing, blank, or has no users).
7. File bytes are accessed only through the `FileStore` port (filesystem adapter today).
8. A version may contain many files, including nested relative paths under that version.
9. Path traversal (`..`) in file names is rejected.
10. Persistence of users, grants, and artifact index survives process restart (JSON store). File bytes survive restart under the configured storage root. A JWT issued before restart remains valid afterward when the same signing secret is used.
11. The JSON store never keeps plaintext passwords: only SHA-256 hex digests (`passwordHash`). Login/enroll/change-password APIs still accept plaintext over the wire; comparison is hash-based.

## Data locations

Defaults are relative to the process working directory (`data/` is not checked into git):

| Store | Default | Contents |
|-------|---------|----------|
| JSON database | `data/store.json` (`VERSION_SERVER_JSON`) | Users, grants, artifact metadata |
| File storage root | `data/files` (`VERSION_SERVER_STORAGE`) | Versioned file bytes at `{project}/{version}/{file...}` |

If the JSON database path is missing, blank, or has no users, the server writes a default database with the seeded accounts before serving requests.

## Capabilities

### Authentication

- Anyone may `POST /login` with user id and password.
- Success returns a JWT, user id, and role, and sets an `HttpOnly` cookie containing that JWT.
- Failure returns `401` and no token/cookie.
- Anyone may `POST /logout` to clear the auth cookie (`204`).
- Cookie-only requests (no `Authorization` header) authenticate when the cookie JWT verifies.
- `GET /projects` includes the authenticated caller’s user id and role alongside the project list.
- Any authenticated user may `POST /password` with current and new password.
- Wrong current password yields `401`; empty new password yields `400`.
- After change, login accepts only the new password; the change persists across restart.

### User administration (Admin)

- Admin may `POST /users` to enroll a user with password and role (`Admin` or `Client`).
- Duplicate user id yields `409 Conflict`.
- Blank user id or empty password yields `400`.
- Admin may `GET /users` to list user ids and roles (no passwords).
- Client may not enroll or list users (`403`).

### Project authorization (Admin)

- Admin may `POST /projects/{project}/access` with a target `userId`.
- Target user must already exist; otherwise `404`.
- After grant, that Client sees `{project}` in `GET /projects` and may list/download its versions/files.
- Admin always sees every project that appears via grants or artifacts.

### Versions and files

- Authorized caller may list versions for a project; each version includes whether it is `latest` (newest artifact upload time in the project).
- Admin may upload a multipart file to a project/version (filename may be nested relative path). Each artifact records an `uploadedAt` timestamp (updated on re-upload).
- Authorized caller may list and download files for a project/version; file listings include `uploadedAt`.
- Admin may delete a file; afterward list/download treat it as absent (`404` on download).
- Admin may delete a whole version; afterward it disappears from version lists and its files are absent.

## Error model

| Code | When |
|------|------|
| 401 | No/invalid token, or bad login credentials |
| 403 | Authenticated but insufficient role or project grant |
| 400 | Invalid body/path/multipart |
| 404 | Unknown user on grant, or missing file on download |
| 409 | Enroll with existing user id |
| 201 | User enrolled or file uploaded |
| 204 | Grant or delete succeeded with no body |
| 200 | Successful read/list/login |

## Out of scope (API)

- Password reset, self-registration, external IdP
- HTTPS termination, rate limiting
- Docker/S3 adapters (storage port exists; S3 not required by this spec)
- Revoking grants or deleting users (not required yet)

## Reference

Machine-oriented endpoint details: [`../API.md`](../API.md).
