package app.inkproof.core.imports

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

enum class ImportKind {
    PDF,
    IMAGE,
    TEXT,
    HTML,
    RTF,
    CSV,
    DOCX,
    XLSX,
    PPTX,
    SVG,
    LEGACY_OFFICE,
    UNSUPPORTED,
}

data class ExtractedText(val text: String, val kind: ImportKind, val note: String = "")

fun classifyImport(name: String, mime: String?): ImportKind {
    val lower = name.lowercase()
    val m = mime?.lowercase().orEmpty()
    return when {
        lower.endsWith(".pdf") || m == "application/pdf" -> ImportKind.PDF
        lower.endsWith(".svg") || m == "image/svg+xml" -> ImportKind.SVG
        lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".webp") || lower.endsWith(".bmp") || m.startsWith("image/") -> ImportKind.IMAGE
        lower.endsWith(".html") || lower.endsWith(".htm") || m == "text/html" -> ImportKind.HTML
        lower.endsWith(".rtf") || m == "application/rtf" || m == "text/rtf" -> ImportKind.RTF
        lower.endsWith(".csv") || m == "text/csv" -> ImportKind.CSV
        lower.endsWith(".md") || lower.endsWith(".txt") || m.startsWith("text/") -> ImportKind.TEXT
        lower.endsWith(".docx") || m.contains("wordprocessingml") -> ImportKind.DOCX
        lower.endsWith(".xlsx") || m.contains("spreadsheetml") -> ImportKind.XLSX
        lower.endsWith(".pptx") || m.contains("presentationml") -> ImportKind.PPTX
        lower.endsWith(".doc") || lower.endsWith(".xls") || lower.endsWith(".ppt") ||
            m == "application/msword" || m.contains("ms-excel") || m.contains("ms-powerpoint") -> ImportKind.LEGACY_OFFICE
        else -> ImportKind.UNSUPPORTED
    }
}

fun extractByKind(name: String, mime: String?, bytes: ByteArray): ExtractedText {
    if (bytes.isEmpty()) return ExtractedText("", classifyImport(name, mime), "The file is empty.")
    return when (val kind = classifyImport(name, mime)) {
        ImportKind.TEXT, ImportKind.CSV -> ExtractedText(bytes.toString(Charsets.UTF_8).take(20_000), kind)
        ImportKind.HTML -> ExtractedText(stripHtml(bytes.toString(Charsets.UTF_8)).take(20_000), kind)
        ImportKind.RTF -> ExtractedText(stripRtf(bytes.toString(Charsets.UTF_8)).take(20_000), kind)
        ImportKind.DOCX -> ExtractedText(extractDocx(bytes).take(20_000), kind)
        ImportKind.XLSX -> ExtractedText(extractXlsx(bytes).take(20_000), kind)
        ImportKind.PPTX -> ExtractedText(extractPptx(bytes).take(20_000), kind)
        ImportKind.SVG -> ExtractedText(extractSvgText(bytes.toString(Charsets.UTF_8)).take(20_000), kind)
        ImportKind.LEGACY_OFFICE -> {
            if (isOleCompound(bytes)) {
                ExtractedText(
                    "",
                    kind,
                    "Binary .doc, .xls, and .ppt are not edited in place. Save as DOCX, XLSX, PPTX, PDF, or TXT and import that.",
                )
            } else {
                ExtractedText("", kind, "This office file could not be read.")
            }
        }
        ImportKind.PDF -> ExtractedText("", kind, "PDF is rendered as pages, not converted to editable text.")
        ImportKind.IMAGE -> ExtractedText("", kind, "Image import places the picture on the page.")
        ImportKind.UNSUPPORTED -> ExtractedText("", kind, "Unsupported file. InkProof accepts PDF, images, text, HTML, RTF, CSV, DOCX, XLSX, PPTX, and simple SVG.")
    }
}

fun isOleCompound(bytes: ByteArray): Boolean {
    if (bytes.size < 8) return false
    return bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() && bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()
}

fun stripHtml(html: String): String {
    val noScript = html.replace(Regex("(?is)<script.*?>.*?</script>"), " ")
        .replace(Regex("(?is)<style.*?>.*?</style>"), " ")
    val withBreaks = noScript.replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("(?i)</p>"), "\n")
    val stripped = withBreaks.replace(Regex("<[^>]+>"), " ")
    return decodeEntities(stripped).replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
}

fun stripRtf(rtf: String): String {
    val sb = StringBuilder()
    var i = 0
    var depth = 0
    while (i < rtf.length) {
        val c = rtf[i]
        when {
            c == '{' -> { depth++; i++ }
            c == '}' -> { depth = (depth - 1).coerceAtLeast(0); i++ }
            c == '\\' -> {
                i++
                if (i >= rtf.length) break
                val n = rtf[i]
                if (n == '\\' || n == '{' || n == '}') {
                    sb.append(n)
                    i++
                } else if (n == '\'') {
                    if (i + 2 < rtf.length) {
                        val hex = rtf.substring(i + 1, i + 3)
                        val code = hex.toIntOrNull(16)
                        if (code != null) sb.append(code.toChar())
                        i += 3
                    } else break
                } else {
                    while (i < rtf.length && rtf[i].isLetter()) i++
                    if (i < rtf.length && rtf[i] == '-') i++
                    while (i < rtf.length && rtf[i].isDigit()) i++
                    if (i < rtf.length && rtf[i] == ' ') i++
                }
            }
            c == '\r' || c == '\n' -> { i++ }
            else -> { sb.append(c); i++ }
        }
    }
    return sb.toString().replace(Regex("[ \\t]+"), " ").trim()
}

fun decodeEntities(text: String): String = text
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")

fun extractDocx(bytes: ByteArray): String {
    val xml = zipEntry(bytes, "word/document.xml") ?: return ""
    return xmlText(xml, "t")
}

fun extractXlsx(bytes: ByteArray): String {
    val shared = zipEntry(bytes, "xl/sharedStrings.xml")?.let { xmlText(it, "t") }?.lines()?.filter { it.isNotBlank() }.orEmpty()
    val sheet = zipEntry(bytes, "xl/worksheets/sheet1.xml") ?: return shared.joinToString("\n")
    val values = Regex("<v[^>]*>(.*?)</v>").findAll(sheet).map { it.groupValues[1] }.toList()
    val rows = values.map { token ->
        val idx = token.toIntOrNull()
        if (idx != null && idx in shared.indices && sheet.contains("t=\"s\"")) shared[idx] else token
    }
    return if (rows.isEmpty()) shared.joinToString("\n") else rows.joinToString("\n")
}

fun extractPptx(bytes: ByteArray): String {
    val names = zipNames(bytes).filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }.sorted()
    return names.mapNotNull { zipEntry(bytes, it) }.joinToString("\n") { xmlText(it, "t") }
}

fun extractSvgText(svg: String): String = Regex("<text[^>]*>(.*?)</text>", RegexOption.DOT_MATCHES_ALL)
    .findAll(svg)
    .joinToString("\n") { decodeEntities(it.groupValues[1].replace(Regex("<[^>]+>"), "")) }

data class SvgPrimitive(val kind: String, val x: Float, val y: Float, val w: Float, val h: Float)

fun parseSimpleSvg(svg: String): List<SvgPrimitive> {
    val out = ArrayList<SvgPrimitive>()
    fun attr(tag: String, name: String): String? =
        Regex("""$name\s*=\s*"([^"]+)"""").find(tag)?.groupValues?.get(1)
    for (m in Regex("<(rect|circle|ellipse|line)\\b[^>]*>").findAll(svg)) {
        val tag = m.value
        val kind = m.groupValues[1]
        when (kind) {
            "rect" -> out.add(
                SvgPrimitive(
                    "rect",
                    attr(tag, "x")?.toFloatOrNull() ?: 0f,
                    attr(tag, "y")?.toFloatOrNull() ?: 0f,
                    attr(tag, "width")?.toFloatOrNull() ?: 0f,
                    attr(tag, "height")?.toFloatOrNull() ?: 0f,
                ),
            )
            "circle" -> {
                val r = attr(tag, "r")?.toFloatOrNull() ?: 0f
                val cx = attr(tag, "cx")?.toFloatOrNull() ?: 0f
                val cy = attr(tag, "cy")?.toFloatOrNull() ?: 0f
                out.add(SvgPrimitive("circle", cx - r, cy - r, r * 2, r * 2))
            }
            "ellipse" -> {
                val rx = attr(tag, "rx")?.toFloatOrNull() ?: 0f
                val ry = attr(tag, "ry")?.toFloatOrNull() ?: 0f
                val cx = attr(tag, "cx")?.toFloatOrNull() ?: 0f
                val cy = attr(tag, "cy")?.toFloatOrNull() ?: 0f
                out.add(SvgPrimitive("ellipse", cx - rx, cy - ry, rx * 2, ry * 2))
            }
            "line" -> {
                val x1 = attr(tag, "x1")?.toFloatOrNull() ?: 0f
                val y1 = attr(tag, "y1")?.toFloatOrNull() ?: 0f
                val x2 = attr(tag, "x2")?.toFloatOrNull() ?: 0f
                val y2 = attr(tag, "y2")?.toFloatOrNull() ?: 0f
                out.add(SvgPrimitive("line", x1, y1, x2 - x1, y2 - y1))
            }
        }
    }
    return out
}

private fun xmlText(xml: String, local: String): String {
    val pattern = Regex("<(?:[A-Za-z0-9]+:)?$local[^>]*>(.*?)</(?:[A-Za-z0-9]+:)?$local>", RegexOption.DOT_MATCHES_ALL)
    return pattern.findAll(xml).joinToString("") { decodeEntities(it.groupValues[1]) }
        .replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
        .trim()
}

fun zipEntry(bytes: ByteArray, name: String): String? {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == name) {
                return zip.readBytes().toString(Charsets.UTF_8)
            }
            entry = zip.nextEntry
        }
    }
    return null
}

fun zipNames(bytes: ByteArray): List<String> {
    val names = ArrayList<String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            names.add(entry.name)
            entry = zip.nextEntry
        }
    }
    return names
}

fun minimalZip(entries: Map<String, String>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(out).use { zip ->
        for ((name, text) in entries) {
            zip.putNextEntry(java.util.zip.ZipEntry(name))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

fun corruptSafe(block: () -> ExtractedText): ExtractedText = try {
    block()
} catch (e: Exception) {
    ExtractedText("", ImportKind.UNSUPPORTED, "The file looks corrupt and was not imported (${e.javaClass.simpleName}).")
}
