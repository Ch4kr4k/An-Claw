package dev.anclaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Block-level Markdown, parsed line by line (enough for model replies). */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class ListItem(val marker: String, val text: String, val indent: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

object MdParser {
    private val listRe = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
    private val headingRe = Regex("^(#{1,6})\\s+(.*)$")

    fun parse(text: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val lines = text.replace("\r\n", "\n").split('\n')
        var i = 0
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
            para.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    flush()
                    val fence = trimmed.take(3)
                    val lang = trimmed.drop(3).trim()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                        code.append(lines[i]).append('\n'); i++
                    }
                    out += MdBlock.Code(lang, code.toString().trimEnd('\n'))
                }
                headingRe.matches(trimmed) -> {
                    flush()
                    val m = headingRe.find(trimmed)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trimEnd('#', ' '))
                }
                trimmed.matches(Regex("^([-*_])\\s*(\\1\\s*){2,}$")) -> { flush(); out += MdBlock.Rule }
                trimmed.startsWith("|") && i + 1 < lines.size && lines[i + 1].trim().matches(Regex("^\\|?[\\s:|-]+\\|?$")) -> {
                    flush()
                    val rows = mutableListOf<List<String>>()
                    rows += cells(trimmed)
                    i += 2
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) { rows += cells(lines[i].trim()); i++ }
                    out += MdBlock.Table(rows)
                    continue
                }
                listRe.matches(line) -> {
                    flush()
                    val m = listRe.find(line)!!
                    out += MdBlock.ListItem(m.groupValues[2], m.groupValues[3], m.groupValues[1].length / 2)
                }
                trimmed.startsWith(">") -> {
                    flush()
                    val q = StringBuilder()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        q.append(lines[i].trimStart().removePrefix(">").trimStart()).append('\n'); i++
                    }
                    out += MdBlock.Quote(q.toString().trim())
                    continue
                }
                trimmed.isEmpty() -> flush()
                else -> {
                    // A list item's continuation line joins it.
                    val last = out.lastOrNull()
                    if (para.isEmpty() && last is MdBlock.ListItem && line.startsWith("  ")) {
                        out[out.lastIndex] = last.copy(text = last.text + " " + trimmed)
                    } else para.append(line).append('\n')
                }
            }
            i++
        }
        flush()
        return out
    }

    private fun cells(row: String) = row.trim().trim('|').split('|').map { it.trim() }
}

/** Inline Markdown: **bold**, *italic*, `code`, ~~strike~~, [links](url), bare URLs. */
@Composable
fun inline(text: String): AnnotatedString {
    val codeBg = CodeColors.inline()
    val link = MaterialTheme.colorScheme.primary
    return remember(text, codeBg, link) { buildInline(text, codeBg, link) }
}

fun buildInline(text: String, codeBg: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val linkStyles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    var i = 0
    fun closing(token: String, from: Int) = text.indexOf(token, from).takeIf { it > from }
    while (i < text.length) {
        val c = text[i]
        when {
            c == '`' -> {
                val end = closing("`", i + 1)
                if (end != null) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 13.sp)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(c); i++ }
            }
            text.startsWith("**", i) || text.startsWith("__", i) -> {
                val token = text.substring(i, i + 2)
                val end = closing(token, i + 2)
                if (end != null) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(buildInline(text.substring(i + 2, end), codeBg, linkColor)) }
                    i = end + 2
                } else { append(token); i += 2 }
            }
            text.startsWith("~~", i) -> {
                val end = closing("~~", i + 2)
                if (end != null) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append("~~"); i += 2 }
            }
            (c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace() &&
                (i == 0 || !text[i - 1].isLetterOrDigit()) -> {
                val end = closing(c.toString(), i + 1)
                if (end != null && !text[end - 1].isWhitespace()) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(buildInline(text.substring(i + 1, end), codeBg, linkColor)) }
                    i = end + 1
                } else { append(c); i++ }
            }
            c == '[' -> {
                val close = text.indexOf("](", i)
                val endParen = if (close > 0) text.indexOf(')', close) else -1
                if (close > 0 && endParen > 0 && !text.substring(i, close).contains('\n')) {
                    val url = text.substring(close + 2, endParen)
                    withLink(LinkAnnotation.Url(url, linkStyles)) { append(text.substring(i + 1, close)) }
                    i = endParen + 1
                } else { append(c); i++ }
            }
            text.startsWith("https://", i) || text.startsWith("http://", i) -> {
                var end = i
                while (end < text.length && !text[end].isWhitespace() && text[end] !in "<>\"'`") end++
                while (end > i && text[end - 1] in ".,;:!?)") end--
                val url = text.substring(i, end)
                withLink(LinkAnnotation.Url(url, linkStyles)) { append(url) }
                i = end
            }
            else -> { append(c); i++ }
        }
    }
}

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { MdParser.parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> Text(
                    inline(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                is MdBlock.Paragraph -> Text(inline(block.text), style = MaterialTheme.typography.bodyLarge)
                is MdBlock.ListItem -> Row(Modifier.padding(start = (block.indent * 14).dp)) {
                    val bullet = if (block.marker[0].isDigit()) block.marker else "•"
                    Text(bullet, Modifier.width(22.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    Text(inline(block.text), style = MaterialTheme.typography.bodyLarge)
                }
                is MdBlock.Quote -> Row {
                    Box(Modifier.width(3.dp).size(3.dp, 20.dp).background(MaterialTheme.colorScheme.outline))
                    Text(
                        inline(block.text), Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is MdBlock.Code -> CodeBlock(block.code, block.lang)
                is MdBlock.Table -> TableBlock(block.rows)
                MdBlock.Rule -> HorizontalDivider()
            }
        }
    }
}

@Composable
fun CodeBlock(code: String, lang: String = "", maxLines: Int = Int.MAX_VALUE) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CodeColors.background())
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lang.ifEmpty { "text" }, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val wrap = LocalUi.current.wrapCode
        SelectionContainer {
            Text(
                code,
                (if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                fontFamily = FontFamily.Monospace, fontSize = 12.5.sp * LocalUi.current.fontScale, lineHeight = 17.sp * LocalUi.current.fontScale,
                maxLines = maxLines, softWrap = wrap,
            )
        }
    }
}

@Composable
private fun TableBlock(rows: List<List<String>>) {
    val cols = rows.maxOfOrNull { it.size } ?: 0
    val widths = (0 until cols).map { c -> rows.maxOf { it.getOrNull(c)?.length ?: 0 }.coerceIn(3, 40) }
    Column(
        Modifier.clip(RoundedCornerShape(6.dp)).background(CodeColors.background())
            .horizontalScroll(rememberScrollState()).padding(8.dp)
    ) {
        rows.forEachIndexed { r, row ->
            Row {
                for (c in 0 until cols) {
                    Text(
                        inline(row.getOrElse(c) { "" }),
                        Modifier.width((widths[c] * 7.5 + 16).dp).padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            if (r == 0) HorizontalDivider()
        }
    }
}
