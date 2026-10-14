package com.arkacode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Self-contained, dependency-free Markdown renderer for chat bubbles
 * (M4). Supports the subset agents actually emit: fenced code blocks,
 * ATX headers, blockquotes, bullet/numbered lists, horizontal rules,
 * bold/italic/inline code/links, and plain paragraphs. Rendering happens
 * in a plain Compose Column — no WebView, no third-party markdown lib.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val scheme = MaterialTheme.colorScheme
    val baseColor = if (color == Color.Unspecified) scheme.onSurface else color
    val blocks = remember(markdown) { parseBlocks(markdown) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeBlock(block.text, scheme.surfaceContainerHigh)
                is MdBlock.Header -> HeaderBlock(block.level, block.text, baseColor)
                is MdBlock.Quote -> QuoteBlock(block.text, baseColor)
                is MdBlock.ListItem -> ListItemBlock(block.ordered, block.index, block.text, baseColor)
                is MdBlock.Paragraph -> InlineMarkdown(block.text, MaterialTheme.typography.bodyMedium, baseColor)
                MdBlock.Hr -> HorizontalDivider()
            }
        }
    }
}

// ------------------------------------------------------------- model

private sealed interface MdBlock {
    data class Code(val text: String) : MdBlock
    data class Header(val level: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class ListItem(val ordered: Boolean, val index: Int?, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data object Hr : MdBlock
}

private val BULLET_RE = Regex("^\\s*[-*+]\\s+(.*)$")
private val ORDERED_RE = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
private val HR_RE = Regex("^(-{3,}|\\*{3,}|_{3,})$")

private fun parseBlocks(md: String): List<MdBlock> {
    val lines = md.replace("\r\n", "\n").split("\n")
    val blocks = ArrayList<MdBlock>()
    var i = 0
    val n = lines.size

    fun next(start: Int): Int {
        var s = start
        while (s < n) { if (lines[s].isBlank()) s++ else break }
        return s
    }

    while (i < n) {
        if (lines[i].isBlank()) { i++; continue }
        val t = lines[i].trim()
        when {
            t.startsWith("```") -> {
                val sb = StringBuilder()
                i++
                while (i < n && !lines[i].trim().startsWith("```")) {
                    sb.appendLine(lines[i]); i++
                }
                i++
                blocks.add(MdBlock.Code(sb.toString().trimEnd('\n')))
            }
            t.startsWith("#") && t.length > t.count { it == '#' } -> {
                val level = t.takeWhile { it == '#' }.length
                blocks.add(MdBlock.Header(level, t.drop(level).trim()))
                i++
            }
            HR_RE.matches(t) -> { blocks.add(MdBlock.Hr); i++ }
            t.startsWith(">") -> {
                val sb = StringBuilder()
                while (i < n && lines[i].trimStart().startsWith(">")) {
                    sb.appendLine(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                blocks.add(MdBlock.Quote(sb.toString().trimEnd('\n')))
            }
            BULLET_RE.matches(lines[i]) -> {
                var j = i
                while (j < n) {
                    val m = BULLET_RE.find(lines[j]) ?: break
                    blocks.add(MdBlock.ListItem(false, null, m.groupValues[1]))
                    j++
                }
                i = j
            }
            ORDERED_RE.matches(lines[i]) -> {
                var j = i
                while (j < n) {
                    val m = ORDERED_RE.find(lines[j]) ?: break
                    blocks.add(MdBlock.ListItem(true, m.groupValues[1].toIntOrNull(), m.groupValues[2]))
                    j++
                }
                i = j
            }
            else -> {
                val sb = StringBuilder()
                var j = i
                while (j < n) {
                    val line = lines[j]
                    val lt = line.trim()
                    if (lt.isEmpty() || lt.startsWith("```") || HR_RE.matches(lt) ||
                        BULLET_RE.matches(line) || ORDERED_RE.matches(line)
                    ) break
                    sb.append(lt).append(' ')
                    j++
                }
                i = j
                blocks.add(MdBlock.Paragraph(sb.toString().trim()))
            }
        }
        i = next(i)
    }
    return blocks
}

// ------------------------------------------------------------- blocks

@Composable
private fun CodeBlock(text: String, background: Color) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Surface(
        color = background,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("code", text))
                    copied = true
                }) {
                    Text(
                        if (copied) I18n.s.copied else I18n.s.copy,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun HeaderBlock(level: Int, text: String, color: Color) {
    val size = when (level) {
        1 -> 22.sp
        2 -> 19.sp
        3 -> 17.sp
        else -> 15.sp
    }
    Text(
        text,
        fontSize = size,
        fontWeight = FontWeight.Bold,
        color = color,
    )
}

@Composable
private fun QuoteBlock(text: String, color: Color) {
    Row {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Box(Modifier.padding(start = 8.dp)) {
            InlineMarkdown(text, MaterialTheme.typography.bodyMedium, color)
        }
    }
}

@Composable
private fun ListItemBlock(ordered: Boolean, index: Int?, text: String, color: Color) {
    Row {
        Text(
            if (ordered) "${index ?: 1}. " else "•  ",
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
        InlineMarkdown(
            text,
            MaterialTheme.typography.bodyMedium,
            color,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

// ------------------------------------------------------------- inline

private val INLINE_RE = Regex(
    "(`[^`]+`|\\*\\*[^*]+\\*\\*|__[^_]+__|\\*[^*]+\\*|_[^_]+_|\\[[^\\]]+\\]\\([^)]+\\))",
)

@Composable
private fun InlineMarkdown(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val annotated = remember(text, scheme) {
        buildInline(text, plain = color, link = scheme.primary, codeBg = scheme.surfaceContainerHighest)
    }
    Text(
        annotated,
        style = style,
        color = color,
        modifier = modifier,
    )
}

private fun buildInline(
    raw: String,
    plain: Color,
    link: Color,
    codeBg: Color,
): AnnotatedString = buildAnnotatedString {
    var last = 0
    INLINE_RE.findAll(raw).forEach { m ->
        withStyle(SpanStyle(color = plain)) { append(raw.substring(last, m.range.first)) }
        val token = m.value
        when {
            token.startsWith("`") -> {
                val body = token.substring(1, token.length - 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(body) }
            }
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.substring(2, token.length - 2)) }
            token.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.substring(2, token.length - 2)) }
            token.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(token.substring(1, token.length - 1)) }
            token.startsWith("_") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(token.substring(1, token.length - 1)) }
            token.startsWith("[") -> {
                val split = token.indexOf("](")
                if (split > 0) {
                    val label = token.substring(1, split)
                    withStyle(SpanStyle(color = link)) { append(label) }
                }
            }
            else -> append(token)
        }
        last = m.range.last + 1
    }
    withStyle(SpanStyle(color = plain)) { append(raw.substring(last)) }
}