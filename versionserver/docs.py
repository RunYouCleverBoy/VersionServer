import logging
import os
import re
from html import escape
from pathlib import Path
from urllib.parse import unquote

import markdown

logger = logging.getLogger("versionserver.docs")

_DOC_PAGES = (
    {
        "path": "API.md",
        "title": "HTTP API",
        "summary": "Endpoints, authentication, roles, and examples.",
    },
    {
        "path": "specs/API-SYSTEM.md",
        "title": "API system spec",
        "summary": "Invariants and capabilities for the HTTP API.",
    },
    {
        "path": "specs/CLIENT-SYSTEM.md",
        "title": "Client system spec",
        "summary": "Web client session, admin controls, and UI rules.",
    },
)

_LINK = re.compile(r"\[([^\]]+)\]\(([^)]+\.md)\)")
_PACKAGE_ROOT = Path(__file__).resolve().parent


def docs_directory() -> Path:
    override = os.environ.get("VERSION_SERVER_DOCS")
    if override:
        return Path(override)
    packaged = _PACKAGE_ROOT / "doc_content"
    if (packaged / "API.md").is_file():
        return packaged
    return _PACKAGE_ROOT.parent / "docs"


def stylesheet_text() -> str | None:
    path = _PACKAGE_ROOT / "static" / "doc" / "styles.css"
    if not path.is_file():
        return None
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return None


def is_safe_doc_path(relative: str) -> bool:
    if relative.startswith("/") or "\x00" in relative:
        return False
    parts = relative.split("/")
    if not parts:
        return False
    return all(part and part not in {".", ".."} for part in parts)


def rewrite_doc_links(source: str, current_path: str) -> str:
    current_dir = current_path.rsplit("/", 1)[0] if "/" in current_path else ""

    def replace(match: re.Match[str]) -> str:
        label = match.group(1)
        href = match.group(2)
        if href.startswith("http://") or href.startswith("https://") or href.startswith("/"):
            return match.group(0)
        resolved = _normalize_relative_path(current_dir, href)
        if resolved is None:
            return match.group(0)
        return f"[{label}](/doc/{resolved})"

    return _LINK.sub(replace, source)


def markdown_to_html(source: str) -> str:
    return markdown.markdown(source, extensions=["tables", "fenced_code"])


def read_markdown(relative: str) -> str | None:
    if not is_safe_doc_path(relative) or not relative.endswith(".md"):
        return None
    path = docs_directory() / relative
    if not path.is_file():
        return None
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return None


def decode_doc_path(raw: str) -> str:
    return unquote(raw)


def docs_index_html() -> str:
    cards: list[str] = []
    for page in _DOC_PAGES:
        cards.append(
            "<li><a href=\"/doc/{path}\"><h2>{title}</h2><p>{summary}</p>"
            "<span class=\"path\">{path}</span></a></li>".format(
                path=escape(page["path"]),
                title=escape(page["title"]),
                summary=escape(page["summary"]),
            )
        )
    body = (
        '<header class="doc-hero"><p class="eyebrow">Version Server</p>'
        "<h1>Documentation</h1>"
        '<p class="lede">API reference and system specs for operators and clients.</p></header>'
        '<ul class="doc-cards">'
        + "".join(cards)
        + "</ul>"
    )
    return _shell(title="Documentation", active_path=None, body=body)


def docs_page_html(relative_path: str) -> str | None:
    source = read_markdown(relative_path)
    if source is None:
        return None
    title = relative_path.removesuffix(".md")
    for page in _DOC_PAGES:
        if page["path"] == relative_path:
            title = page["title"]
            break
    body = (
        '<article class="doc-article">'
        f'<p class="doc-path">{escape(relative_path)}</p>'
        f"{markdown_to_html(rewrite_doc_links(source, relative_path))}"
        "</article>"
    )
    return _shell(title=title, active_path=relative_path, body=body)


def _normalize_relative_path(current_dir: str, href: str) -> str | None:
    joined = href if current_dir == "" else f"{current_dir}/{href}"
    parts: list[str] = []
    for part in joined.split("/"):
        if part in {"", "."}:
            continue
        if part == "..":
            if not parts:
                return None
            parts.pop()
            continue
        parts.append(part)
    result = "/".join(parts)
    if not is_safe_doc_path(result):
        return None
    return result


def _shell(title: str, active_path: str | None, body: str) -> str:
    nav = [_nav_item("/doc/", "Overview", active_path is None)]
    for page in _DOC_PAGES:
        nav.append(_nav_item(f"/doc/{page['path']}", page["title"], active_path == page["path"]))
    return (
        "<!DOCTYPE html><html lang=\"en\"><head>"
        '<meta charset="utf-8" />'
        '<meta name="viewport" content="width=device-width, initial-scale=1" />'
        f"<title>{escape(title)} · Version Server</title>"
        '<link rel="icon" href="/favicon.svg" type="image/svg+xml" />'
        '<link rel="preconnect" href="https://fonts.googleapis.com" />'
        '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />'
        '<link href="https://fonts.googleapis.com/css2?family=Fraunces:opsz,wght@9..144,600;9..144,700'
        '&family=Source+Sans+3:wght@400;600&family=Source+Code+Pro:wght@400;600&display=swap" '
        'rel="stylesheet" />'
        '<link rel="stylesheet" href="/doc/styles.css" />'
        '</head><body><div class="doc-app">'
        '<aside class="doc-nav">'
        '<a class="brand" href="/doc/">Version Server</a>'
        '<p class="nav-label">Docs</p><nav><ul>'
        + "".join(nav)
        + "</ul></nav>"
        '<div class="nav-foot"><a href="/ui/">Open app</a></div>'
        "</aside>"
        '<main class="doc-main">'
        + body
        + "</main></div></body></html>"
    )


def _nav_item(href: str, label: str, active: bool) -> str:
    css = ' class="active"' if active else ""
    return f'<li><a{css} href="{href}">{escape(label)}</a></li>'
