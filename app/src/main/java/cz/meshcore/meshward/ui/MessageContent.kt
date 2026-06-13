package cz.meshcore.meshward.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Renders chat message text with a small, chat-safe subset of Markdown — bold, italic,
 * inline `code`, fenced code blocks, links, and bullet/numbered lists (no headers/quotes
 * that would blow up the bubble) — plus highlighted, clickable `@[Name]` mentions.
 */
@Composable
fun MessageContent(
    text: String,
    enableMentions: Boolean,
    onMentionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    mentionResolved: (String) -> Boolean = { true },
) {
    val accent = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val onCodeBg = MaterialTheme.colorScheme.onSurfaceVariant
    val style = Inline(accent, codeBg, onCodeBg, enableMentions, onMentionClick, mentionResolved)

    val blocks = remember(text) { splitBlocks(text) }
    Column(modifier) {
        blocks.forEach { block ->
            when (block) {
                is Block.Code -> Surface(
                    color = codeBg,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(vertical = 2.dp),
                ) {
                    Text(
                        block.text,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is Block.Bullet -> Row {
                    Text("•  ", color = accent)
                    Text(buildAnnotatedString { appendInline(block.text, style) })
                }
                is Block.Ordered -> Row {
                    Text("${block.number}.  ", color = accent)
                    Text(buildAnnotatedString { appendInline(block.text, style) })
                }
                is Block.Paragraph -> Text(buildAnnotatedString { appendInline(block.text, style) })
            }
        }
    }
}

/**
 * In the composer, highlight `@[Name]` mentions (same accent as the bubble) without changing
 * the underlying text — so what you type is what gets sent.
 */
fun mentionInputTransformation(accent: Color): VisualTransformation = VisualTransformation { text ->
    val styled = buildAnnotatedString {
        append(text.text)
        for (m in mentionRegex.findAll(text.text)) {
            addStyle(
                SpanStyle(background = accent.copy(alpha = 0.18f), color = accent, fontWeight = FontWeight.Medium),
                m.range.first, m.range.last + 1,
            )
        }
    }
    TransformedText(styled, OffsetMapping.Identity)
}

// ---- block splitting --------------------------------------------------------

private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Bullet(val text: String) : Block
    data class Ordered(val number: Int, val text: String) : Block
    data class Code(val text: String) : Block
}

private val orderedRe = Regex("""^(\d+)\.\s+(.*)""")

private fun splitBlocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = text.split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            // Fenced code block — collect until the closing fence (or end of text).
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                if (body.isNotEmpty()) body.append("\n")
                body.append(lines[i]); i++
            }
            i++ // skip closing fence
            out += Block.Code(body.toString())
            continue
        }
        val trimmed = line.trimStart()
        when {
            trimmed.isEmpty() -> {} // collapse blank lines
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") ->
                out += Block.Bullet(trimmed.substring(2))
            orderedRe.matches(trimmed) -> orderedRe.find(trimmed)!!.let {
                out += Block.Ordered(it.groupValues[1].toInt(), it.groupValues[2])
            }
            else -> out += Block.Paragraph(line)
        }
        i++
    }
    return out
}

// ---- inline parsing ---------------------------------------------------------

private class Inline(
    val accent: Color,
    val codeBg: Color,
    val onCodeBg: Color,
    val enableMentions: Boolean,
    val onMentionClick: (String) -> Unit,
    val mentionResolved: (String) -> Boolean,
)

private val bareUrlRe = Regex("""https?://\S+""")
private val mdLinkRe = Regex("""\[([^\]]+)\]\(([^)\s]+)\)""")

/** Appends [s] to the builder, applying inline Markdown + mention styling. */
private fun AnnotatedString.Builder.appendInline(s: String, ctx: Inline) {
    var i = 0
    while (i < s.length) {
        val rest = s.substring(i)
        when {
            // inline code
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = ctx.codeBg, color = ctx.onCodeBg)) {
                        append(s.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            // mention @[Name]
            ctx.enableMentions && rest.startsWith("@[") -> {
                val close = s.indexOf(']', i + 2)
                if (close > i) {
                    val name = s.substring(i + 2, close)
                    // A mention that matches a saved contact is a real link (accent); one we can't
                    // resolve — e.g. a bridged MeshCore name not in our contacts — is muted grey so
                    // it doesn't read as an active link (tapping still explains it).
                    val color = if (ctx.mentionResolved(name)) ctx.accent else ctx.onCodeBg
                    val link = LinkAnnotation.Clickable(
                        "mention",
                        TextLinkStyles(SpanStyle(background = color.copy(alpha = 0.18f), color = color, fontWeight = FontWeight.Medium)),
                    ) { ctx.onMentionClick(name) }
                    withLink(link) { append("@$name") }
                    i = close + 1
                } else { append(s[i]); i++ }
            }
            // markdown link [text](url)
            mdLinkRe.matchesAt(s, i) -> {
                val m = mdLinkRe.matchAt(s, i)!!
                linkSpan(m.groupValues[2], m.groupValues[1], ctx)
                i += m.value.length
            }
            // bare url
            bareUrlRe.matchesAt(s, i) -> {
                val m = bareUrlRe.matchAt(s, i)!!
                linkSpan(m.value, m.value, ctx)
                i += m.value.length
            }
            // bold **text**
            rest.startsWith("**") -> {
                val end = s.indexOf("**", i + 2)
                if (end > i) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }; i = end + 2 }
                else { append(s[i]); i++ }
            }
            // italic *text* or _text_
            (s[i] == '*' || s[i] == '_') -> {
                val end = s.indexOf(s[i], i + 1)
                if (end > i) { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }; i = end + 1 }
                else { append(s[i]); i++ }
            }
            else -> { append(s[i]); i++ }
        }
    }
}

private fun AnnotatedString.Builder.linkSpan(url: String, label: String, ctx: Inline) {
    val link = LinkAnnotation.Url(
        url,
        TextLinkStyles(SpanStyle(color = ctx.accent, textDecoration = TextDecoration.Underline)),
    )
    withLink(link) { append(label) }
}
