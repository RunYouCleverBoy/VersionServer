# Version Server API

Base URL (default): `http://localhost:8080`

Web client: [`/ui/`](http://localhost:8080/ui/) (`/` and `/ui` redirect there).

Rendered docs (HTML): [`/doc/`](http://localhost:8080/doc/) (`/doc` redirects there).

System specs:

- [`specs/API-SYSTEM.md`](specs/API-SYSTEM.md) — API behavior and invariants
- [`specs/CLIENT-SYSTEM.md`](specs/CLIENT-SYSTEM.md) — web client behavior

## Data storage

Runtime data lives under the process working directory (defaults below). The `data/` tree is gitignored and is created on first use.

| What | Default path | Layout / notes |
|------|--------------|----------------|
| JSON database | `data/store.json` | Users (`passwordHash` only), project grants, and artifact index (`project` / `version` / `fileName` / `uploadedAt`). Auto-created with seeded users if missing or empty. |
| File bytes | `data/files/` | `{project}/{version}/{relative-file-path}` via the filesystem `FileStore`. |

On startup the server walks the file storage root and adds any on-disk files that are missing from the artifact index (existing rows are left unchanged; `uploadedAt` comes from the file's last-modified time when available).

Override with `VERSION_SERVER_JSON` and `VERSION_SERVER_STORAGE` (see [Configuration](#configuration)). Paths may be absolute.

## Authentication

Auth is **JWT-based** (HMAC-signed). `POST /login` issues a JWT and stores the same value in an `HttpOnly` cookie (`VS_TOKEN` by default). Protected endpoints accept either:

```http
Authorization: Bearer <jwt>
```

or the `VS_TOKEN` cookie (no in-memory server session map).

| Status | Meaning |
|--------|---------|
| `401 Unauthorized` | Missing or invalid JWT; bad login credentials |
| `403 Forbidden` | Authenticated, but role/project access denied |
| `400 Bad Request` | Missing/invalid path or body |
| `404 Not Found` | Unknown user on grant; missing file |
| `409 Conflict` | Enroll with an existing user id |

### Roles

| Role | Capabilities |
|------|----------------|
| **Admin** | Enroll users; list users; grant project access; upload/delete files; see **every** project; list/download any project's versions and files |
| **Client** | See only projects they were granted; list/download those projects' versions and files |

### Seeded users

Created automatically when the JSON store file is missing, blank, or has no users (typical fresh install; `data/` is not in git):

| User id | Password | Role |
|---------|----------|------|
| `admin` | `admin-pass` | Admin |
| `alice` | `alice-pass` | Client |
| `bob` | `bob-pass` | Client |

---

## Endpoints

### `POST /login`

Public. Issues a JWT.

**Request** (`application/json`):

```json
{
  "userId": "admin",
  "password": "admin-pass"
}
```

**Response** `200 OK`:

```json
{
  "token": "<jwt>",
  "userId": "admin",
  "role": "Admin"
}
```

Also sets `Set-Cookie: VS_TOKEN=<jwt>; Path=/; HttpOnly; SameSite=Lax` (cookie name configurable).

`role` is `"Admin"` or `"Client"`. The JWT subject is the user id; a `role` claim is included. Tokens remain valid across process restarts when the same JWT secret is used.

**Errors:** `401` if credentials are wrong.

---

### `POST /logout`

Public. Clears the auth cookie (`VS_TOKEN` max-age 0). Does not require a body.

**Response:** `204 No Content`.

---

### `POST /password`

Requires JWT (Bearer or cookie; any role).

Changes the caller's password. Requires the current password.

**Request** (`application/json`):

```json
{
  "currentPassword": "alice-pass",
  "newPassword": "alice-new"
}
```

**Response:** `204 No Content`

**Errors:** `401` (missing/invalid token, or wrong current password), `400` (empty new password).

After a successful change, the previous password no longer works for login. Existing tokens remain valid until the process restarts or the client signs out.

---

### `GET /users`

Requires **Admin**.

Lists enrolled users (no passwords).

**Response** `200 OK`:

```json
{
  "users": [
    { "userId": "admin", "role": "Admin" },
    { "userId": "alice", "role": "Client" }
  ]
}
```

**Errors:** `401`, `403`.

---

### `POST /users`

Requires **Admin**.

Enrolls a new user.

**Request** (`application/json`):

```json
{
  "userId": "carol",
  "password": "carol-pass",
  "role": "Client"
}
```

`role` must be `"Admin"` or `"Client"`.

**Response** `201 Created`:

```json
{
  "userId": "carol",
  "role": "Client"
}
```

**Errors:** `401`, `403`, `400` (blank user id or empty password), `409` (user id already exists).

---

### `GET /projects`

Requires JWT (Bearer or cookie).

Returns the caller’s identity plus projects visible to them (all projects for Admin; granted projects for Client). The web client uses a successful response as “signed in.”

**Response** `200 OK`:

```json
{
  "userId": "alice",
  "role": "Client",
  "projects": ["alpha", "beta"]
}
```

---

### `POST /projects/{project}/access`

Requires **Admin**.

Grants an **existing** user access to `{project}`. Creates the project name in the authorization filter even before files exist.

**Request** (`application/json`):

```json
{
  "userId": "alice"
}
```

**Response:** `204 No Content`

**Errors:** `401`, `403` (non-admin), `400` (blank project or user id), `404` (user id not enrolled).

---

### `GET /projects/{project}/versions`

Requires project access (Admin always; Client if granted).

Returns versions for the project. `latest` is `true` for the version whose newest artifact `uploadedAt` is the most recent in the project.

**Response** `200 OK`:

```json
{
  "versions": [
    { "name": "1.0", "latest": false },
    { "name": "2.0", "latest": true }
  ]
}
```

---

### `POST /projects/{project}/versions/{version}/files`

Requires **Admin**.

Uploads one file for that project/version. Multipart form field name: `file`.

The part's filename may be a simple name (`app.bin`) or a **relative path** under the version (`lib/util.bin`). Path segments like `.` / `..` are rejected.

```bash
curl -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@./util.bin;filename=lib/util.bin" \
  "http://localhost:8080/projects/alpha/versions/1.0/files"
```

**Response:** `201 Created`

**Errors:** `401`, `403`, `400` (missing part, invalid name, or store rejection).

A version may contain many files.

---

### `GET /projects/{project}/versions/{version}/files`

Requires project access.

Lists file paths for that version (including nested relative paths), each with an ISO-8601 `uploadedAt` timestamp from the artifact index.

**Response** `200 OK`:

```json
{
  "files": [
    { "name": "app.bin", "uploadedAt": "2026-10-08T10:15:30Z" },
    { "name": "lib/util.bin", "uploadedAt": "2026-10-08T10:16:01Z" }
  ]
}
```

---

### `GET /projects/{project}/versions/{version}/archive`

Requires project access.

Downloads a ZIP of files for that version. Optional query `prefix` limits the archive to one file or directory tree (e.g. `prefix=lib` → entries relative to `lib/`).

```bash
curl -OJ -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/projects/alpha/versions/1.0/archive?prefix=lib"
```

**Response** `200 OK` with `Content-Type: application/zip` and `Content-Disposition: attachment`.

**Errors:** `401`, `403`, `400` (invalid prefix), `404` if no files match.

---

### `GET /projects/{project}/versions/{version}/files/{fileName...}`

Requires project access.

Downloads file bytes. `{fileName...}` may include `/` for nested paths (e.g. `lib/util.bin`).

**Response** `200 OK` with `Content-Type: application/octet-stream` and the raw bytes.

**Errors:** `401`, `403`, `404` if the file is missing.

---

### `DELETE /projects/{project}/versions/{version}/files/{fileName...}`

Requires **Admin**.

Deletes the file from storage and metadata.

**Response:** `204 No Content`

**Errors:** `401`, `403`, `400`.

---

### `DELETE /projects/{project}/versions/{version}`

Requires **Admin**.

Deletes the entire version: all files under it in storage, and all artifact metadata for that project/version. Idempotent if the version is already absent.

**Response:** `204 No Content`

**Errors:** `401`, `403`, `400`.

---

## Configuration

| Variable | Default | Purpose |
|----------|---------|---------|
| `VERSION_SERVER_STORAGE` | `data/files` | Root directory for published file bytes (`{project}/{version}/...`) |
| `VERSION_SERVER_JSON` | `data/store.json` | JSON database path (users, grants, artifact index; SHA-256 `passwordHash` only). Created with seeded users if missing or empty. |
| `VERSION_SERVER_JWT_SECRET` | `dev-only-change-me` | HMAC secret used to sign/verify JWTs |
| `VERSION_SERVER_LOG_DIR` | `logs` | Directory for rolling server logs (`versionserver.log`) |
| `VERSION_SERVER_UPLOAD_MAX_BYTES` | `1073741824` (1 GiB) | Max size of a single multipart upload part. Oversize uploads return `413`. |
| `VERSION_SERVER_TLS_CERT` | unset | PEM certificate, or a path to a PEM, PKCS#12, or JKS file. A value containing `-----BEGIN` is the certificate text. |
| `VERSION_SERVER_TLS_KEY` | unset | PEM private key, or a path to it. Required for a PEM certificate. |
| `VERSION_SERVER_TLS_KEY_PASSWORD` | unset | Password for an encrypted PEM private key. |
| `VERSION_SERVER_TLS_KEYSTORE` | unset | Path to a PKCS#12 or JKS keystore. Alternative to the PEM pair. |
| `VERSION_SERVER_TLS_KEYSTORE_PASSWORD` | unset | Password for `VERSION_SERVER_TLS_KEYSTORE` or a `.p12` / `.jks` path in `VERSION_SERVER_TLS_CERT`. |
| `VERSION_SERVER_TLS_KEY_ALIAS` | first key entry | Alias of the private key inside a keystore. |
| `VERSION_SERVER_HTTPS_PORT` | `8443` | Port for HTTPS after a certificate is configured. |

Listen port: `8080` for HTTP. HTTPS is off until a certificate is set; startup then logs how to create one. Do not commit certificate files or copy them into the Docker image. In Docker, set `VERSION_SERVER_TLS_DIR` to the host directory that should be mounted at `/certs`.

---

## Quick example

```bash
# Login
TOKEN=$(curl -s -X POST http://localhost:8080/login \
  -H 'Content-Type: application/json' \
  -d '{"userId":"admin","password":"admin-pass"}' | jq -r .token)

# Enroll a client
curl -s -X POST http://localhost:8080/users \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"userId":"carol","password":"carol-pass","role":"Client"}'

# Grant carol access to alpha
curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"userId":"carol"}' \
  http://localhost:8080/projects/alpha/access

# Upload
curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@README.md;filename=docs/README.md" \
  http://localhost:8080/projects/alpha/versions/1.0/files

# List (as carol)
CAROL=$(curl -s -X POST http://localhost:8080/login \
  -H 'Content-Type: application/json' \
  -d '{"userId":"carol","password":"carol-pass"}' | jq -r .token)

curl -s -H "Authorization: Bearer $CAROL" \
  http://localhost:8080/projects/alpha/versions/1.0/files
```
