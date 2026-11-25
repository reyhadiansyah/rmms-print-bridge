package co.id.rmms.printbridge.printer

import android.util.Base64
import com.samskivert.mustache.Mustache
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

enum class InlineFontScale {
    SMALL, NORMAL, TALL, WIDE, BIG
}

enum class LineAlignment {
    LEFT, CENTER, RIGHT
}

data class StyledTextSegment(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val fontScale: InlineFontScale = InlineFontScale.NORMAL
)

sealed class ParsedPrintLine {
    data class TextLine(
        val alignment: LineAlignment,
        val segments: List<StyledTextSegment>,
        val rawLine: String
    ) : ParsedPrintLine()

    data class BarcodeLine(val value: String) : ParsedPrintLine()
    data class QrCodeLine(val value: String) : ParsedPrintLine()
}

object TemplateProcessor {
    private val escPosPrefixRegex = Regex("^(\\[[LCR]])(.*)")
    private val formattingTagRegex = Regex("</?(b|i|u|s|small|normal|tall|wide|big)>", RegexOption.IGNORE_CASE)
    private val barcodeLineRegex = Regex("^\\s*(?:\\[[LCR]])?\\s*\\[BARCODE](.*?)\\[/BARCODE]\\s*$",
        RegexOption.IGNORE_CASE)
    private val qrLineRegex = Regex("^\\s*(?:\\[[LCR]])?\\s*\\[QRCODE](.*?)\\[/QRCODE]\\s*$",
        RegexOption.IGNORE_CASE)

    fun decodePayloadToJson(rawPayload: String): String {
        val trimmed = rawPayload.trim()
        return if (trimmed.startsWith("{")) {
            trimmed
        } else {
            try {
                val decodedBytes = Base64.decode(trimmed, Base64.DEFAULT or Base64.URL_SAFE)
                String(decodedBytes, Charsets.UTF_8)
            } catch (e: Exception) {
                URLDecoder.decode(trimmed, "UTF-8")
            }
        }
    }

    fun renderTemplate(templateBody: String, jsonString: String): String {
        val dataMap = jsonObjectToMap(JSONObject(jsonString))
        val compiler = Mustache.compiler().defaultValue("")
        return compiler.compile(templateBody).execute(dataMap)
    }

    fun stripFormattingTags(text: String): String {
        return formattingTagRegex.replace(text, "")
    }

    fun isBarcodeOrQrLine(line: String): Boolean {
        return barcodeLineRegex.matches(line) || qrLineRegex.matches(line)
    }

    fun formatAndWrapText(
        renderedText: String,
        maxCharsPerLine: Int,
        alignColon: Boolean,
        colonBySection: Boolean,
        indentWrappedText: Boolean,
        justifyLabelValue: Boolean,
        showColonInLeftRight: Boolean
    ): String {
        val safeMaxChars = maxCharsPerLine.coerceAtLeast(16)
        val rawLines = renderedText.replace("\r\n", "\n").lines()

        val firstNonEmpty = rawLines.firstOrNull { it.isNotBlank() }?.trim()?.uppercase() ?: ""
        if (firstNonEmpty.startsWith("SIZE") || firstNonEmpty.startsWith("CLS")) {
            return renderedText
        }

        if (justifyLabelValue) {
            val cleanedLines = rawLines.filterNot { it.trim().equals("[SECTION]", ignoreCase = true) }
            val resultLines = mutableListOf<String>()
            for (line in cleanedLines) {
                if (isBarcodeOrQrLine(line)) {
                    resultLines.add(line.trim())
                } else {
                    resultLines.addAll(
                        formatLeftRightLine(
                            line = line,
                            maxCharsPerLine = safeMaxChars,
                            showColon = showColonInLeftRight
                        )
                    )
                }
            }
            return resultLines.joinToString("\n")
        }

        val colonProcessedLines = if (alignColon) {
            if (colonBySection) {
                alignColonsPerSection(rawLines, safeMaxChars)
            } else {
                val cleaned = rawLines.filterNot { it.trim().equals("[SECTION]", ignoreCase = true) }
                alignColonsInGroup(cleaned, safeMaxChars)
            }
        } else {
            rawLines.filterNot { it.trim().equals("[SECTION]", ignoreCase = true) }
        }

        val finalWrappedLines = mutableListOf<String>()
        for (line in colonProcessedLines) {
            if (isBarcodeOrQrLine(line)) {
                finalWrappedLines.add(line.trim())
            } else {
                finalWrappedLines.addAll(
                    wrapSingleLine(
                        line = line,
                        maxCharsPerLine = safeMaxChars,
                        indentToColon = alignColon && indentWrappedText
                    )
                )
            }
        }

        return finalWrappedLines.joinToString("\n")
    }

    fun parseTemplateLines(formattedText: String): List<ParsedPrintLine> {
        return formattedText.replace("\r\n", "\n").lines().map { rawLine ->
            val barcodeMatch = barcodeLineRegex.find(rawLine)
            if (barcodeMatch != null) {
                return@map ParsedPrintLine.BarcodeLine(
                    stripFormattingTags(barcodeMatch.groupValues[1]).trim()
                )
            }

            val qrMatch = qrLineRegex.find(rawLine)
            if (qrMatch != null) {
                return@map ParsedPrintLine.QrCodeLine(
                    stripFormattingTags(qrMatch.groupValues[1]).trim()
                )
            }

            val (prefix, content) = extractEscPosPrefix(rawLine)
            val alignment = when (prefix) {
                "[C]" -> LineAlignment.CENTER
                "[R]" -> LineAlignment.RIGHT
                else -> LineAlignment.LEFT
            }

            ParsedPrintLine.TextLine(
                alignment = alignment,
                segments = parseStyledSegments(content),
                rawLine = rawLine
            )
        }
    }

    fun parseStyledSegments(content: String): List<StyledTextSegment> {
        if (content.isEmpty()) return listOf(StyledTextSegment(""))

        val segments = mutableListOf<StyledTextSegment>()
        var bold = false
        var italic = false
        var underline = false
        var strikethrough = false
        var fontScale = InlineFontScale.NORMAL

        var lastIndex = 0
        for (match in formattingTagRegex.findAll(content)) {
            if (match.range.first > lastIndex) {
                val textChunk = content.substring(lastIndex, match.range.first)
                if (textChunk.isNotEmpty()) {
                    segments.add(
                        StyledTextSegment(
                            text = textChunk,
                            bold = bold,
                            italic = italic,
                            underline = underline,
                            strikethrough = strikethrough,
                            fontScale = fontScale
                        )
                    )
                }
            }

            val tagRaw = match.value.lowercase()
            val isClosing = tagRaw.startsWith("</")
            val tagName = match.groupValues[1].lowercase()

            when (tagName) {
                "b" -> bold = !isClosing
                "i" -> italic = !isClosing
                "u" -> underline = !isClosing
                "s" -> strikethrough = !isClosing
                "small" -> fontScale = if (isClosing) InlineFontScale.NORMAL else InlineFontScale.SMALL
                "normall" -> fontScale = InlineFontScale.NORMAL
                "tall" -> fontScale = if (isClosing) InlineFontScale.NORMAL else InlineFontScale.TALL
                "wide" -> fontScale = if (isClosing) InlineFontScale.NORMAL else InlineFontScale.WIDE
                "big" -> fontScale = if (isClosing) InlineFontScale.NORMAL else InlineFontScale.BIG
            }

            lastIndex = match.range.last + 1
        }

        if (lastIndex < content.length) {
            val remaining = content.substring(lastIndex)
            if (remaining.isNotEmpty()) {
                segments.add(
                    StyledTextSegment(
                        text = remaining,
                        bold = bold,
                        italic = italic,
                        underline = underline,
                        strikethrough = strikethrough,
                        fontScale = fontScale
                    )
                )
            }
        }

        return if (segments.isEmpty()) listOf(StyledTextSegment("")) else segments
    }

    private fun formatLeftRightLine(
        line: String,
        maxCharsPerLine: Int,
        showColon: Boolean
    ): List<String> {
        if (line.isEmpty()) return listOf("")

        val (prefix, content) = extractEscPosPrefix(line)
        if (prefix == "[C]" || prefix == "[R]" || content.contains("[R]")) {
            return wrapSingleLine(line, maxCharsPerLine, indentToColon = false)
        }

        val colonIdx = findColonIndexOutsideTags(content)
        val maxAllowedKeyLen = (maxCharsPerLine * 0.7).toInt().coerceAtLeast(10)

        if (colonIdx <= 0) {
            return wrapSingleLine(line, maxCharsPerLine, indentToColon = false)
        }

        val rawKey = content.substring(0, colonIdx).trimEnd()
        val rawValue = content.substring(colonIdx + 1).trim()
        val visibleKeyLen = stripFormattingTags(rawKey).length

        if (visibleKeyLen == 0 || visibleKeyLen > maxAllowedKeyLen) {
            return wrapSingleLine(line, maxCharsPerLine, indentToColon = false)
        }

        val labelPart = if (showColon) "$rawKey:" else rawKey
        val visibleLabelLen = stripFormattingTags(labelPart).length
        val visibleValueLen = stripFormattingTags(rawValue).length

        if (visibleValueLen == 0) {
            return listOf("$prefix$labelPart")
        }

        if (visibleLabelLen + 1 + visibleValueLen <= maxCharsPerLine) {
            val spaceCount = maxCharsPerLine - visibleLabelLen - visibleValueLen
            return listOf("$prefix$labelPart${" ".repeat(spaceCount)}$rawValue")
        }

        val availableRightWidth = maxCharsPerLine - visibleLabelLen - 1
        val result = mutableListOf<String>()

        if (availableRightWidth >= 10) {
            val valueChunks = splitStyledTextToWidth(rawValue, availableRightWidth)
            valueChunks.forEachIndexed { index, chunk ->
                val chunkVisibleLen = stripFormattingTags(chunk).length
                if (index == 0) {
                    val gap = (maxCharsPerLine - visibleLabelLen - chunkVisibleLen).coerceAtLeast(1)
                    result.add("$prefix$labelPart${" ".repeat(gap)}$chunk")
                } else {
                    val padLeft = (maxCharsPerLine - chunkVisibleLen).coerceAtLeast(0)
                    result.add("$prefix${" ".repeat(padLeft)}$chunk")
                }
            }
        } else {
            result.add("$prefix$labelPart")
            val valueChunks = splitStyledTextToWidth(rawValue, maxCharsPerLine)
            valueChunks.forEach { chunk ->
                val chunkVisibleLen = stripFormattingTags(chunk).length
                val padLeft = (maxCharsPerLine - chunkVisibleLen).coerceAtLeast(0)
                result.add("$prefix${" ".repeat(padLeft)}$chunk")
            }
        }

        return result
    }

    private fun alignColonsPerSection(lines: List<String>, maxCharsPerLine: Int): List<String> {
        val result = mutableListOf<String>()
        val currentSection = mutableListOf<String>()

        fun flushSection() {
            if (currentSection.isNotEmpty()) {
                result.addAll(alignColonsInGroup(currentSection, maxCharsPerLine))
                currentSection.clear()
            }
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.equals("[SECTION]", ignoreCase = true)) {
                flushSection()
            } else if (trimmed.isEmpty() || isBarcodeOrQrLine(line)) {
                flushSection()
                result.add(if (trimmed.isEmpty()) "" else trimmed)
            } else {
                currentSection.add(line)
            }
        }
        flushSection()
        return result
    }

    private fun alignColonsInGroup(lines: List<String>, maxCharsPerLine: Int): List<String> {
        val maxAllowedKeyLen = (maxCharsPerLine * 0.6).toInt().coerceAtLeast(10)

        var maxKeyLength = 0
        for (line in lines) {
            if (isBarcodeOrQrLine(line)) continue
            val (_, content) = extractEscPosPrefix(line)
            val colonIdx = findColonIndexOutsideTags(content)
            if (colonIdx > 0) {
                val visibleKeyLen = stripFormattingTags(content.substring(0, colonIdx)).trimEnd().length
                if (visibleKeyLen in 1..maxAllowedKeyLen && visibleKeyLen > maxKeyLength) {
                    maxKeyLength = visibleKeyLen
                }
            }
        }

        if (maxKeyLength == 0) return lines

        return lines.map { line ->
            if (isBarcodeOrQrLine(line)) return@map line
            val (prefix, content) = extractEscPosPrefix(line)
            val colonIdx = findColonIndexOutsideTags(content)
            if (colonIdx > 0) {
                val rawKey = content.substring(0, colonIdx).trimEnd()
                val rawValue = content.substring(colonIdx + 1).trimStart()
                val visibleKeyLen = stripFormattingTags(rawKey).length
                if (visibleKeyLen in 1..maxAllowedKeyLen) {
                    val padCount = (maxKeyLength - visibleKeyLen).coerceAtLeast(0)
                    "$prefix$rawKey${" ".repeat(padCount)} : $rawValue"
                } else {
                    line
                }
            } else {
                line
            }
        }
    }

    private fun wrapSingleLine(
        line: String,
        maxCharsPerLine: Int,
        indentToColon: Boolean
    ): List<String> {
        if (line.isEmpty()) return listOf("")

        val (prefix, content) = extractEscPosPrefix(line)
        val visibleContentLen = stripFormattingTags(content).length
        if (visibleContentLen <= maxCharsPerLine) {
            return listOf(line)
        }

        val colonIdx = findColonIndexOutsideTags(content)
        val maxAllowedKeyLen = (maxCharsPerLine * 0.65).toInt().coerceAtLeast(10)

        if (indentToColon && colonIdx > 0) {
            val visibleKeyLen = stripFormattingTags(content.substring(0, colonIdx)).length
            if (visibleKeyLen in 1..maxAllowedKeyLen) {
                var valueStartIndex = colonIdx + 1
                while (valueStartIndex < content.length && content[valueStartIndex] == ' ') {
                    valueStartIndex++
                }

                val headerPart = content.substring(0, valueStartIndex)
                val visibleHeaderLen = stripFormattingTags(headerPart).length

                if (visibleHeaderLen < maxCharsPerLine - 8) {
                    val indentSpaces = " ".repeat(visibleHeaderLen)
                    val valueWidth = maxCharsPerLine - visibleHeaderLen
                    val rawValue = content.substring(valueStartIndex).trimEnd()

                    val chunks = splitStyledTextToWidth(rawValue, valueWidth)
                    return chunks.mapIndexed { idx, chunk ->
                        if (idx == 0) "$prefix$headerPart$chunk" else "$prefix$indentSpaces$chunk"
                    }
                }
            }
        }

        return splitStyledTextToWidth(content, maxCharsPerLine).map { "$prefix$it" }
    }

    private fun splitStyledTextToWidth(styledText: String, width: Int): List<String> {
        val safeWidth = width.coerceAtLeast(6)
        val segments = parseStyledSegments(styledText)

        data class StyledChar(val ch: Char, val style: StyledTextSegment)
        val styledChars = mutableListOf<StyledChar>()
        for (seg in segments) {
            val templateStyle = seg.copy(text = "")
            for (c in seg.text) {
                styledChars.add(StyledChar(c, templateStyle))
            }
        }

        if (styledChars.isEmpty()) return listOf("")

        val resultLines = mutableListOf<String>()
        var cursor = 0

        while (cursor < styledChars.size) {
            if (resultLines.isNotEmpty()) {
                while (cursor < styledChars.size && styledChars[cursor].ch == ' ') {
                    cursor++
                }
                if (cursor >= styledChars.size) break
            }

            val remainingCount = styledChars.size - cursor
            val endExclusive = if (remainingCount <= safeWidth) {
                styledChars.size
            } else {
                var breakPos = -1
                val limit = cursor + safeWidth
                for (i in limit downTo cursor + 1) {
                    if (styledChars[i - 1].ch == ' ') {
                        breakPos = i - 1
                        break
                    }
                }
                if (breakPos > cursor) breakPos else limit
            }

            var trimmedEnd = endExclusive
            while (trimmedEnd > cursor && styledChars[trimmedEnd - 1].ch == ' ') {
                trimmedEnd--
            }

            val sb = StringBuilder()
            var i = cursor
            while (i < trimmedEnd) {
                val currentStyle = styledChars[i].style
                val run = StringBuilder()
                while (i < trimmedEnd && styledChars[i].style == currentStyle) {
                    run.append(styledChars[i].ch)
                    i++
                }
                sb.append(wrapRunWithTags(run.toString(), currentStyle))
            }
            resultLines.add(sb.toString())

            cursor = if (endExclusive < styledChars.size && styledChars[endExclusive].ch == ' ') {
                endExclusive + 1
            } else {
                endExclusive
            }
        }

        return if (resultLines.isEmpty()) listOf("") else resultLines
    }

    private fun wrapRunWithTags(text: String, style: StyledTextSegment): String {
        var result = text
        when (style.fontScale) {
            InlineFontScale.SMALL -> result = "<small>$result</small>"
            InlineFontScale.TALL -> result = "<tall>$result</tall>"
            InlineFontScale.WIDE -> result = "<wide>$result</wide>"
            InlineFontScale.BIG -> result = "<big>$result</big>"
            InlineFontScale.NORMAL -> Unit
        }
        if (style.strikethrough) result = "<s>$result</s>"
        if (style.underline) result = "<u>$result</u>"
        if (style.italic) result = "<i>$result</i>"
        if (style.bold) result = "<b>$result</b>"
        return result
    }

    private fun findColonIndexOutsideTags(text: String): Int {
        var insideTag = false
        for (i in text.indices) {
            when (text[i]) {
                '<' -> insideTag = true
                '>' -> insideTag = false
                ':' -> if (!insideTag) return i
            }
        }
        return -1
    }

    fun generateSampleJsonForTemplate(): String {
        return JSONObject().apply {
            put("lorem_ipsum", "LOREM IPSUM DOLOR SIT AMET")
        }.toString()
    }

    private fun extractEscPosPrefix(line: String): Pair<String, String> {
        val match = escPosPrefixRegex.find(line)
        return if (match != null) {
            Pair(match.groupValues[1], match.groupValues[2])
        } else {
            Pair("", line)
        }
    }

    private fun jsonObjectToMap(jsonObject: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = jsonObject.get(key)
            map[key] = when (value) {
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> jsonArrayToList(value)
                JSONObject.NULL -> ""
                else -> value
            }
        }
        return map
    }

    private fun jsonArrayToList(jsonArray: JSONArray): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until jsonArray.length()) {
            val value = jsonArray.get(i)
            list.add(
                when (value) {
                    is JSONObject -> jsonObjectToMap(value)
                    is JSONArray -> jsonArrayToList(value)
                    JSONObject.NULL -> ""
                    else -> value
                }
            )
        }
        return list
    }
}