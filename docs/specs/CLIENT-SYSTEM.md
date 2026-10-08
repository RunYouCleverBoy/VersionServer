# System Spec: Version Server Web Client

## Purpose

Give humans a browser UI at `/ui/` to sign in, browse authorized projects/versions/files, and (for Admins) enroll users, grant access, and upload/delete files—including drag-and-drop of files and folders.

## Entry

| Path | Behavior |
|------|----------|
| `/` | Redirect to `/ui/` |
| `/ui` | Redirect to `/ui/` |
| `/ui/` | Serve the SPA shell (`index.html`) |
| `/ui/styles.css`, `/ui/app.js` | Static assets (absolute paths so `/ui` without slash still works after redirect) |
| `/doc` | Redirect to `/doc/` |
| `/doc/` | Documentation hub (HTML) |
| `/doc/*.md`, `/doc/specs/*.md` | Rendered markdown docs (HTML) |

Anonymous users may load the client. All data operations go through the HTTP API with JWT auth (cookie and/or Bearer).

## Session

1. Sign-in form calls `POST /login`.
2. On success, the server sets an `HttpOnly` cookie with the JWT. Auth for the web client is that cookie.
3. Subsequent API calls use `credentials: "same-origin"` so the cookie JWT is sent on every request.
4. On page load (and after login), the client calls `GET /projects`. If the cookie JWT is valid, identity + projects come back and the user is treated as signed in. If not (`401`), show the login view.
5. Sign-out calls `POST /logout` to clear the cookie and returns to the login view.
6. Admin-only UI sections are shown only when `role === "Admin"`.
7. Enroll user, Users, Grant access, and Change password are collapsed accordion cards in the rail; at most one of those cards is open at a time.

## Client capabilities

### All signed-in users

- See session label (`userId · role`).
- Change their own password via a form that posts `POST /password` (current + new).
- List projects from `GET /projects` (filtered by server rules).
- Select a project → list versions.
- Select a version → list files; download a file (including nested paths).

### Admin-only

- **Enroll user:** form posts `POST /users` with user id, password, and role; refreshes user list from `GET /users`.
- **List users:** show enrolled user ids and roles (no passwords).
- **Grant access:** form posts `POST /projects/{project}/access`. If the user does not exist, show a failure toast (server returns `404`).
- **Upload:** require project + version; accept:
  - drag-and-drop of files and folders,
  - “Choose files”,
  - “Choose folder”.
  Folder structure is preserved as relative paths under the version. Upload each pending file via multipart `POST .../files`.
- **Delete file:** per-file delete control calling `DELETE .../files/{path}`.
- **Delete version:** per-version delete control calling `DELETE .../versions/{version}` (confirms first).

## UI invariants

1. Clients never see Admin enroll/grant/upload/delete controls.
2. Asset URLs are rooted at `/ui/...` so styles/scripts load reliably.
3. Nested file paths are encoded segment-by-segment for download/delete URLs.
4. Upload queue can hold multiple files; submitting uploads all pending items then clears the queue.
5. Errors from enroll/grant/upload surface as user-visible toasts (or login error for sign-in).

## Non-goals (client)

- Offline mode
- Editing passwords or deleting users
- Revoking grants from the UI

## Dependency

Behavior of the underlying HTTP API is defined in [`API-SYSTEM.md`](API-SYSTEM.md) and [`../API.md`](../API.md).
