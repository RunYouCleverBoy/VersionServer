import logging
import re
import tempfile
import zipfile
from pathlib import Path
from urllib.parse import unquote

from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from starlette.background import BackgroundTask
from starlette.datastructures import UploadFile
from starlette.formparsers import MultiPartException

from versionserver.config import AppConfig
from versionserver.database import JsonDatabase
from versionserver.docs import (
    decode_doc_path,
    docs_index_html,
    docs_page_html,
    is_safe_doc_path,
    stylesheet_text,
)
from versionserver.files import FilesystemFileStore, FileStore
from versionserver.middleware import AccessLogMiddleware
from versionserver.models import Role
from versionserver.sync import sync_artifacts_from_disk
from versionserver.timefmt import EPOCH
from versionserver.tokens import JwtTokenService

app_log = logging.getLogger("versionserver")
upload_log = logging.getLogger("versionserver.upload")
client_log = logging.getLogger("versionserver.client")

_UI_DIR = Path(__file__).resolve().parent / "static" / "ui"
_FILENAME_STAR = re.compile(r"filename\*=(?:UTF-8|utf-8)''([^;]+)")
_FILENAME = re.compile(r'filename="((?:\\.|[^"\\])*)"|filename=([^;\s]+)', re.IGNORECASE)


def create_app(config: AppConfig, file_store: FileStore | None = None) -> FastAPI:
    database = JsonDatabase(config.json_store_path)
    store = file_store or FilesystemFileStore(config.storage_root)
    sync_artifacts_from_disk(database, store)
    if config.signing_key() is None:
        return FastAPI(redirect_slashes=False, docs_url=None, redoc_url=None, openapi_url=None)
    tokens = JwtTokenService(config)
    cookie_name = config.jwt_cookie_name

    app = FastAPI(redirect_slashes=False, docs_url=None, redoc_url=None, openapi_url=None)
    app.add_middleware(AccessLogMiddleware)

    @app.exception_handler(Exception)
    async def unhandled(request: Request, exc: Exception) -> Response:
        if isinstance(exc, MultiPartException) and _multipart_too_large(exc):
            app_log.warning("multipart too large %s %s : %s", request.method, request.url.path, exc)
            return Response(status_code=413)
        app_log.exception("unhandled %s %s : %s", request.method, request.url.path, exc)
        return Response(status_code=500)

    def jwt_from_request(request: Request) -> str | None:
        header = request.headers.get("authorization")
        if header is not None and header.startswith("Bearer "):
            bearer = header.removeprefix("Bearer ")
            if bearer.strip() != "":
                return bearer
        cookie = request.cookies.get(cookie_name)
        if cookie is not None and cookie.strip() != "":
            return cookie
        return None

    def require_user(request: Request):
        token = jwt_from_request(request)
        if token is None:
            return Response(status_code=401)
        user_id = tokens.user_id_for(token)
        if user_id is None:
            return Response(status_code=401)
        user = database.user(user_id)
        if user is None:
            return Response(status_code=401)
        return user

    def require_admin(request: Request):
        user = require_user(request)
        if isinstance(user, Response):
            return user
        if user.role != Role.Admin:
            return Response(status_code=403)
        return user

    def require_project(request: Request, project: str):
        user = require_user(request)
        if isinstance(user, Response):
            return user
        if not database.is_authorized(user, project):
            return Response(status_code=403)
        return user

    @app.post("/client-log")
    async def post_client_log(request: Request) -> Response:
        raw = await request.body()
        text = raw.decode("utf-8", errors="replace")[:8192]
        client_log.info("%s", text)
        return Response(status_code=204)

    @app.get("/")
    async def root() -> Response:
        return RedirectResponse("/ui/", status_code=302)

    @app.get("/ui")
    async def ui_redirect() -> Response:
        return RedirectResponse("/ui/", status_code=302)

    @app.get("/doc")
    async def doc_redirect() -> Response:
        return RedirectResponse("/doc/", status_code=302)

    @app.get("/doc/")
    async def doc_index() -> Response:
        return HTMLResponse(docs_index_html())

    @app.get("/doc/{doc_path:path}")
    async def doc_page(doc_path: str) -> Response:
        relative = decode_doc_path(doc_path)
        if relative.strip() == "":
            return RedirectResponse("/doc/", status_code=302)
        if relative == "styles.css":
            css = stylesheet_text()
            if css is None:
                return Response("Not found", status_code=404, media_type="text/plain")
            return Response(css, media_type="text/css")
        page = docs_page_html(relative)
        if page is None:
            return Response("Not found", status_code=404, media_type="text/plain")
        return HTMLResponse(page)

    @app.post("/login")
    async def login(request: Request) -> Response:
        payload = await _json_object(request)
        if isinstance(payload, Response):
            return payload
        user_id = payload.get("userId")
        password = payload.get("password")
        if not isinstance(user_id, str) or not isinstance(password, str):
            return Response(status_code=400)
        user = database.authenticate(user_id, password)
        if user is None:
            return Response(status_code=401)
        issued = tokens.issue(user.id, user.role.value)
        response = JSONResponse(
            {"token": issued, "userId": user.id, "role": user.role.value},
        )
        response.set_cookie(
            key=cookie_name,
            value=issued,
            max_age=max(1, config.jwt_validity_seconds),
            path="/",
            httponly=True,
            samesite="lax",
        )
        return response

    @app.post("/logout")
    async def logout() -> Response:
        response = Response(status_code=204)
        response.delete_cookie(key=cookie_name, path="/", httponly=True, samesite="lax")
        return response

    @app.post("/password")
    async def change_password(request: Request) -> Response:
        user = require_user(request)
        if isinstance(user, Response):
            return user
        payload = await _json_object(request)
        if isinstance(payload, Response):
            return payload
        current_password = payload.get("currentPassword")
        new_password = payload.get("newPassword")
        if not isinstance(current_password, str) or not isinstance(new_password, str):
            return Response(status_code=400)
        changed = database.change_password(user.id, current_password, new_password)
        if changed is None:
            app_log.warning("password change rejected: empty new password userId=%s", user.id)
            return Response(status_code=400)
        if changed is False:
            app_log.warning("password change rejected: wrong current password userId=%s", user.id)
            return Response(status_code=401)
        app_log.info("password change ok userId=%s", user.id)
        return Response(status_code=204)

    @app.get("/projects")
    async def list_projects(request: Request) -> Response:
        user = require_user(request)
        if isinstance(user, Response):
            return user
        return JSONResponse(
            {
                "userId": user.id,
                "role": user.role.value,
                "projects": database.projects_for(user),
            }
        )

    @app.get("/users")
    async def list_users(request: Request) -> Response:
        admin = require_admin(request)
        if isinstance(admin, Response):
            return admin
        return JSONResponse(
            {
                "users": [
                    {"userId": user.id, "role": user.role.value}
                    for user in database.users()
                ]
            }
        )

    @app.post("/users")
    async def enroll_user(request: Request) -> Response:
        admin = require_admin(request)
        if isinstance(admin, Response):
            return admin
        payload = await _json_object(request)
        if isinstance(payload, Response):
            return payload
        user_id = payload.get("userId")
        password = payload.get("password")
        role = _parse_role(payload.get("role"))
        if not isinstance(user_id, str) or user_id.strip() == "" or not isinstance(password, str) or password == "":
            return Response(status_code=400)
        if role is None:
            return Response(status_code=400)
        if database.user(user_id) is not None:
            return Response(status_code=409)
        created = database.add_user(user_id, password, role)
        if created is None:
            return Response(status_code=400)
        return JSONResponse(
            status_code=201,
            content={"userId": created.id, "role": created.role.value},
        )

    @app.delete("/users/{user_id}")
    async def delete_user(user_id: str, request: Request) -> Response:
        admin = require_admin(request)
        if isinstance(admin, Response):
            return admin
        if user_id.strip() == "":
            return Response(status_code=400)
        if user_id == admin.id:
            return Response(status_code=409)
        if database.remove_user(user_id) is None:
            return Response(status_code=404)
        return Response(status_code=204)

    @app.post("/projects/{project}/access")
    async def grant_access(project: str, request: Request) -> Response:
        admin = require_admin(request)
        if isinstance(admin, Response):
            return admin
        if project.strip() == "":
            return Response(status_code=400)
        payload = await _json_object(request)
        if isinstance(payload, Response):
            return payload
        user_id = payload.get("userId")
        if not isinstance(user_id, str) or user_id.strip() == "":
            return Response(status_code=400)
        if not database.add_grant(user_id, project):
            return Response(status_code=404)
        return Response(status_code=204)

    @app.get("/projects/{project}/versions")
    async def list_versions(project: str, request: Request) -> Response:
        if project.strip() == "":
            return Response(status_code=400)
        user = require_project(request, project)
        if isinstance(user, Response):
            return user
        versions = database.versions_for(project)
        return JSONResponse(
            {"versions": [{"name": item.name, "latest": item.latest} for item in versions]}
        )

    @app.post("/projects/{project}/versions/{version}/files")
    async def upload_file(project: str, version: str, request: Request) -> Response:
        admin = require_admin(request)
        if isinstance(admin, Response):
            return admin
        if project.strip() == "" or version.strip() == "":
            upload_log.warning("upload rejected: blank project/version project=%s version=%s", project, version)
            return Response(status_code=400)
        try:
            form = await request.form(
                max_files=20,
                max_fields=20,
                max_part_size=config.multipart_form_field_limit_bytes,
            )
        except MultiPartException as error:
            if _multipart_too_large(error):
                upload_log.warning(
                    "upload rejected: multipart limit project=%s version=%s limitBytes=%s error=%s",
                    project,
                    version,
                    config.multipart_form_field_limit_bytes,
                    error,
                )
                return Response(status_code=413)
            raise
        file_name = None
        upload: UploadFile | None = None
        part_count = 0
        for name, value in form.multi_items():
            part_count += 1
            if isinstance(value, UploadFile):
                file_name = _original_filename(value)
                upload = value
                upload_log.info(
                    "upload part file project=%s version=%s partName=%s originalFileName=%s contentType=%s",
                    project,
                    version,
                    name,
                    file_name,
                    value.content_type,
                )
            else:
                upload_log.info(
                    "upload part form project=%s version=%s partName=%s value=%s",
                    project,
                    version,
                    name,
                    str(value)[:256],
                )
        if file_name is None or file_name.strip() == "" or upload is None:
            upload_log.warning(
                "upload rejected: missing file part project=%s version=%s parts=%s fileName=%s",
                project,
                version,
                part_count,
                file_name,
            )
            return Response(status_code=400)
        upload.file.seek(0)
        if not store.store(project, version, file_name, upload.file):
            upload_log.warning(
                "upload rejected: store failed project=%s version=%s fileName=%s",
                project,
                version,
                file_name,
            )
            return Response(status_code=400)
        database.add_artifact(project, version, file_name)
        upload_log.info("upload ok project=%s version=%s fileName=%s", project, version, file_name)
        return Response(status_code=201)

    @app.get("/projects/{project}/versions/{version}/files")
    async def list_files(project: str, version: str, request: Request) -> Response:
        if project.strip() == "" or version.strip() == "":
            return Response(status_code=400)
        user = require_project(request, project)
        if isinstance(user, Response):
            return user
        meta = {artifact.file_name: artifact for artifact in database.artifacts(project, version)}
        files = []
        for name in sorted(store.list(project, version)):
            artifact = meta.get(name)
            files.append(
                {
                    "name": name,
                    "uploadedAt": artifact.uploaded_at if artifact is not None else EPOCH,
                }
            )
        return JSONResponse({"files": files})

    @app.get("/projects/{project}/versions/{version}/archive")
    async def download_archive(project: str, version: str, request: Request) -> Response:
        if project.strip() == "" or version.strip() == "":
            return Response(status_code=400)
        user = require_project(request, project)
        if isinstance(user, Response):
            return user
        prefix = normalize_archive_prefix(request.query_params.get("prefix"))
        if prefix is None:
            return Response(status_code=400)
        names = sorted(
            name for name in store.list(project, version) if matches_archive_prefix(name, prefix)
        )
        if not names:
            return Response(status_code=404)
        archive_path = _write_archive(store, project, version, names, prefix)
        return FileResponse(
            archive_path,
            media_type="application/zip",
            filename=archive_download_name(version, prefix),
            background=BackgroundTask(_unlink, archive_path),
        )

    @app.get("/projects/{project}/versions/{version}/files/{file_name:path}")
    async def download_file(project: str, version: str, file_name: str, request: Request) -> Response:
        if project.strip() == "" or version.strip() == "" or file_name.strip() == "":
            return Response(status_code=400)
        user = require_project(request, project)
        if isinstance(user, Response):
            return user
        path = store.resolve(project, version, file_name)
        if path is None or not path.is_file():
            return Response(status_code=404)
        return FileResponse(path, media_type="application/octet-stream")

    @app.delete("/projects/{project}/versions/{version}/files/{file_name:path}")
    async def delete_file(project: str, version: str, file_name: str, request: Request) -> Response:
        admin = require_admin(request)
        if isinstance(admin, Response):
            return admin
        if project.strip() == "" or version.strip() == "" or file_name.strip() == "":
            return Response(status_code=400)
        store.delete(project, version, file_name)
        database.remove_artifact(project, version, file_name)
        return Response(status_code=204)

    @app.delete("/projects/{project}/versions/{version}")
    async def delete_version(project: str, version: str, request: Request) -> Response:
        admin = require_admin(request)
        if isinstance(admin, Response):
            return admin
        if project.strip() == "" or version.strip() == "":
            return Response(status_code=400)
        store.delete_version(project, version)
        database.remove_version(project, version)
        return Response(status_code=204)

    if _UI_DIR.is_dir():
        app.mount("/ui", StaticFiles(directory=_UI_DIR, html=True), name="ui")
    return app


def normalize_archive_prefix(raw: str | None) -> str | None:
    if raw is None or raw.strip() == "":
        return ""
    normalized = raw.replace("\\", "/").strip("/")
    if normalized == "":
        return ""
    segments = normalized.split("/")
    if any(segment == "" or segment in {".", ".."} for segment in segments):
        return None
    return normalized


def matches_archive_prefix(file_name: str, prefix: str) -> bool:
    if prefix == "":
        return True
    return file_name == prefix or file_name.startswith(f"{prefix}/")


def archive_entry_name(file_name: str, prefix: str) -> str:
    if prefix == "":
        return file_name
    if file_name == prefix:
        return file_name.rsplit("/", 1)[-1]
    return file_name.removeprefix(f"{prefix}/")


def archive_download_name(version: str, prefix: str) -> str:
    if prefix == "":
        return f"{version}.zip"
    return f"{prefix.rsplit('/', 1)[-1]}.zip"


async def _json_object(request: Request) -> dict | Response:
    try:
        payload = await request.json()
    except Exception:
        return Response(status_code=400)
    if not isinstance(payload, dict):
        return Response(status_code=400)
    return payload


def _parse_role(value: object) -> Role | None:
    if not isinstance(value, str):
        return None
    try:
        return Role(value)
    except ValueError:
        return None


def _original_filename(upload: UploadFile) -> str | None:
    header = upload.headers.get("content-disposition", "")
    starred = _FILENAME_STAR.search(header)
    if starred is not None:
        return unquote(starred.group(1))
    matched = _FILENAME.search(header)
    if matched is not None:
        quoted = matched.group(1)
        if quoted is not None:
            return quoted.replace('\\"', '"').replace("\\\\", "\\")
        raw = matched.group(2)
        if raw is not None:
            return raw.strip().strip('"')
    return upload.filename


def _multipart_too_large(error: Exception) -> bool:
    message = str(error).lower()
    return "exceed" in message or "too large" in message or "maximum" in message or "limit" in message


def _write_archive(
    store: FileStore,
    project: str,
    version: str,
    names: list[str],
    prefix: str,
) -> Path:
    handle = tempfile.NamedTemporaryFile(prefix="version-archive-", suffix=".zip", delete=False)
    path = Path(handle.name)
    handle.close()
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in names:
            source = store.resolve(project, version, name)
            if source is None or not source.is_file():
                continue
            archive.write(source, arcname=archive_entry_name(name, prefix))
    return path


def _unlink(path: Path) -> None:
    path.unlink(missing_ok=True)
