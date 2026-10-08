package com.playground.versionserver

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private val docPages = listOf(
    DocPage(
        path = "API.md",
        title = "HTTP API",
        summary = "Endpoints, authentication, roles, and examples.",
    ),
    DocPage(
        path = "specs/API-SYSTEM.md",
        title = "API system spec",
        summary = "Invariants and capabilities for the HTTP API.",
    ),
    DocPage(
        path = "specs/CLIENT-SYSTEM.md",
        title = "Client system spec",
        summary = "Web client session, admin controls, and UI rules.",
    ),
)

private data class DocPage(
    val path: String,
    val title: String,
    val summary: String,
)

fun Route.documentationRoutes() {
    get("/doc") {
        call.respondRedirect("/doc/")
    }

    get("/doc/") {
        call.respondText(docsIndexHtml(), ContentType.Text.Html)
    }

    get("/doc/{path...}") {
        val relative = call.parameters.getAll("path")
            ?.joinToString("/")
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            .orEmpty()
        if (relative.isBlank()) {
            call.respondRedirect("/doc/")
            return@get
        }
        if (relative == "styles.css") {
            val css = readClasspathText("doc/styles.css")
            if (css == null) {
                call.respondText("Not found", status = HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(css, ContentType.Text.CSS)
            return@get
        }
        if (!isSafeDocPath(relative) || !relative.endsWith(".md")) {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return@get
        }
        val markdown = readClasspathText("doc-content/$relative")
        if (markdown == null) {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return@get
        }
        val title = docPages.firstOrNull { it.path == relative }?.title
            ?: relative.removeSuffix(".md")
        call.respondText(
            docsPageHtml(
                title = title,
                relativePath = relative,
                bodyHtml = markdownToHtml(rewriteDocLinks(markdown, relative)),
            ),
            ContentType.Text.Html,
        )
    }
}

internal fun isSafeDocPath(relative: String): Boolean {
    if (relative.startsWith("/") || relative.contains('\u0000')) return false
    val parts = relative.split('/')
    return parts.isNotEmpty() && parts.none { it.isEmpty() || it == "." || it == ".." }
}

internal fun rewriteDocLinks(markdown: String, currentPath: String): String {
    val currentDir = currentPath.substringBeforeLast('/', missingDelimiterValue = "")
    return markdown.replace(Regex("""\[([^\]]+)\]\(([^)]+\.md)\)""")) { match ->
        val label = match.groupValues[1]
        val href = match.groupValues[2]
        if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("/")) {
            match.value
        } else {
            val resolved = normalizeRelativePath(currentDir, href) ?: return@replace match.value
            "[$label](/doc/$resolved)"
        }
    }
}

internal fun markdownToHtml(source: String): String {
    val flavour = GFMFlavourDescriptor()
    val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(source)
    return HtmlGenerator(source, tree, flavour).generateHtml()
}

private fun normalizeRelativePath(currentDir: String, href: String): String? {
    val joined = if (currentDir.isEmpty()) href else "$currentDir/$href"
    val parts = mutableListOf<String>()
    for (part in joined.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> {
                if (parts.isEmpty()) return null
                parts.removeAt(parts.lastIndex)
            }
            else -> parts.add(part)
        }
    }
    val result = parts.joinToString("/")
    return result.takeIf { isSafeDocPath(it) }
}

private fun readClasspathText(resourcePath: String): String? {
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)
        ?: DocumentationResource::class.java.classLoader.getResourceAsStream(resourcePath)
        ?: return null
    return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
}

private class DocumentationResource

private fun docsIndexHtml(): String = docsShell(
    title = "Documentation",
    activePath = null,
    body = buildString {
        append("""<header class="doc-hero"><p class="eyebrow">Version Server</p>""")
        append("""<h1>Documentation</h1>""")
        append("""<p class="lede">API reference and system specs for operators and clients.</p></header>""")
        append("""<ul class="doc-cards">""")
        for (page in docPages) {
            append("""<li><a href="/doc/${page.path}">""")
            append("""<h2>${escapeHtml(page.title)}</h2>""")
            append("""<p>${escapeHtml(page.summary)}</p>""")
            append("""<span class="path">${escapeHtml(page.path)}</span>""")
            append("</a></li>")
        }
        append("</ul>")
    },
)

private fun docsPageHtml(title: String, relativePath: String, bodyHtml: String): String = docsShell(
    title = title,
    activePath = relativePath,
    body = buildString {
        append("""<article class="doc-article">""")
        append("""<p class="doc-path">${escapeHtml(relativePath)}</p>""")
        append(bodyHtml)
        append("</article>")
    },
)

private fun docsShell(title: String, activePath: String?, body: String): String = buildString {
    append("<!DOCTYPE html><html lang=\"en\"><head>")
    append("<meta charset=\"utf-8\" />")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />")
    append("<title>${escapeHtml(title)} · Version Server</title>")
    append("<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\" />")
    append("<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin />")
    append(
        "<link href=\"https://fonts.googleapis.com/css2?family=Fraunces:opsz,wght@9..144,600;9..144,700" +
            "&family=Source+Sans+3:wght@400;600&family=Source+Code+Pro:wght@400;600&display=swap\" " +
            "rel=\"stylesheet\" />",
    )
    append("<link rel=\"stylesheet\" href=\"/doc/styles.css\" />")
    append("</head><body><div class=\"doc-app\">")
    append("<aside class=\"doc-nav\">")
    append("<a class=\"brand\" href=\"/doc/\">Version Server</a>")
    append("<p class=\"nav-label\">Docs</p><nav><ul>")
    append(navItem("/doc/", "Overview", activePath == null))
    for (page in docPages) {
        append(navItem("/doc/${page.path}", page.title, activePath == page.path))
    }
    append("</ul></nav>")
    append("<div class=\"nav-foot\">")
    append("<a href=\"/ui/\">Open app</a>")
    append("</div></aside>")
    append("<main class=\"doc-main\">")
    append(body)
    append("</main></div></body></html>")
}

private fun navItem(href: String, label: String, active: Boolean): String {
    val cls = if (active) """ class="active"""" else ""
    return """<li><a$cls href="$href">${escapeHtml(label)}</a></li>"""
}

private fun escapeHtml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
