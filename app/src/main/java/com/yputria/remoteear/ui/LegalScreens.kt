package com.yputria.remoteear.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yputria.remoteear.R
import com.yputria.remoteear.theme.LocalPalette
import com.yputria.remoteear.theme.RemoteEarTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the overflow menu can go.
 *
 * There is no settings screen and there is not going to be one - the design excludes it, and every
 * setting this app could have is either the phone's business (volume) or already on the main screen
 * (noise reduction). These three are documents.
 */
enum class MenuItem(val titleRes: Int) {
    PrivacyPolicy(R.string.menu_privacy),
    TermsOfUse(R.string.menu_terms),
    About(R.string.menu_about),
    ;

    /** Null for [About], which is composed rather than read from a file. */
    val assetPath: String?
        get() = when (this) {
            PrivacyPolicy -> "legal/privacy-policy.md"
            TermsOfUse -> "legal/terms-of-use.md"
            About -> null
        }
}

/**
 * A document, shipped in the APK and read from `assets/legal/`.
 *
 * The text is not duplicated into `strings.xml`, and that is deliberate: the same file is what a
 * reader sees in the repository and what the Play listing can link to, so the privacy claim the app
 * makes on screen is byte-identical to the one anybody can audit. An app that says "nothing leaves
 * this phone" should not keep three drifting copies of the sentence.
 *
 * Read off the main thread, because it is file I/O however small the file is.
 */
@Composable
fun DocumentScreen(item: MenuItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val path = item.assetPath ?: return
    val text by produceState(initialValue = "", path) {
        value = withContext(Dispatchers.IO) { readAsset(context, path) }
    }

    ScreenScaffold(title = stringResource(item.titleRes), onBack = onBack) {
        MarkdownText(text)
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit, onOpen: (MenuItem) -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    ScreenScaffold(title = stringResource(MenuItem.About.titleRes), onBack = onBack) {
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 28.sp,
            color = palette.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.about_tagline),
            fontSize = 16.sp,
            lineHeight = 25.sp,
            color = palette.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.about_version, version),
            fontSize = 14.sp,
            color = palette.statusLabel,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.about_no_internet),
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = palette.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.about_licence),
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = palette.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        listOf(MenuItem.PrivacyPolicy, MenuItem.TermsOfUse).forEach { item ->
            Text(
                text = stringResource(item.titleRes),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = palette.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpen(item) }
                    .padding(vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    val backLabel = stringResource(R.string.cd_back)

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.surface)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = backLabel },
                contentAlignment = Alignment.Center,
            ) {
                BackArrow()
            }
            Spacer(Modifier.size(4.dp))
            Text(text = title, fontSize = 20.sp, color = palette.onSurface)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun BackArrow() {
    val color = LocalPalette.current.onSurface
    Canvas(Modifier.size(20.dp)) {
        val w = 2.dp.toPx()
        val midY = size.height / 2f
        drawLine(color, Offset(size.width, midY), Offset(0f, midY), w)
        drawLine(color, Offset(0f, midY), Offset(size.width * 0.4f, midY - size.height * 0.35f), w)
        drawLine(color, Offset(0f, midY), Offset(size.width * 0.4f, midY + size.height * 0.35f), w)
    }
}

/**
 * The overflow menu, sitting on the wordmark row so the designed layout keeps its proportions -
 * there is no app bar to hang it from, and adding one for three documents would cost 56 dp of the
 * screen that matters.
 */
@Composable
fun OverflowMenu(onSelect: (MenuItem) -> Unit) {
    val palette = LocalPalette.current
    val moreLabel = stringResource(R.string.cd_more)
    var open by remember { mutableStateOf(false) }

    Box {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(50))
                .clickable { open = true }
                .semantics { contentDescription = moreLabel },
            contentAlignment = Alignment.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) {
                    Box(
                        Modifier
                            .size(3.dp)
                            .clip(RoundedCornerShape(50))
                            .background(palette.footnote),
                    )
                }
            }
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(stringResource(item.titleRes)) },
                    onClick = {
                        open = false
                        onSelect(item)
                    },
                )
            }
        }
    }
}

// ── A very small markdown renderer ───────────────────────────────────────────────────────────────

private fun readAsset(context: Context, path: String): String =
    runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }
        .getOrElse { "" }

internal sealed interface Block {
    data class Title(val text: String) : Block
    data class Heading(val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Bullet(val text: String) : Block
}

/**
 * Handles exactly what the legal documents use: `#`, `##`, `-` bullets, blank-line paragraphs and
 * `**bold**`. A markdown library would be a third-party runtime dependency, which
 * docs/privacy.md argues against on the grounds that every dependency is a claim the user cannot
 * audit - and it would be a strange thing to add in order to display the privacy policy.
 */
internal fun parseMarkdown(source: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val buffer = StringBuilder()
    var inBullet = false

    fun flush() {
        if (buffer.isNotEmpty()) {
            val text = buffer.toString().trim()
            blocks += if (inBullet) Block.Bullet(text) else Block.Paragraph(text)
            buffer.clear()
        }
        inBullet = false
    }

    source.lines().forEach { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() -> flush()
            line.startsWith("## ") -> {
                flush()
                blocks += Block.Heading(line.removePrefix("## "))
            }
            line.startsWith("# ") -> {
                flush()
                blocks += Block.Title(line.removePrefix("# "))
            }
            line.startsWith("- ") -> {
                flush()
                inBullet = true
                buffer.append(line.removePrefix("- "))
            }
            // A continuation line. The documents are hard-wrapped at 100 columns, so consecutive
            // non-empty lines belong to whatever block is open - and **that includes a bullet**.
            //
            // It did not, until a release build put the privacy policy on screen with a bullet
            // ending mid-sentence and its second half sitting underneath as an unindented
            // paragraph. Of all the screens to look broken on, that one makes a claim about
            // trustworthiness.
            else -> {
                if (buffer.isNotEmpty()) buffer.append(' ')
                buffer.append(line)
            }
        }
    }
    flush()
    return blocks
}

/** `**bold**` and `` `code` `` - the only inline marks the documents use. */
@Composable
private fun inline(text: String) = buildAnnotatedString {
    var rest = text
    while (rest.isNotEmpty()) {
        val bold = rest.indexOf("**")
        val code = rest.indexOf('`')
        val next = listOf(bold, code).filter { it >= 0 }.minOrNull() ?: -1
        if (next < 0) {
            append(rest)
            break
        }
        append(rest.substring(0, next))
        rest = rest.substring(next)
        if (rest.startsWith("**")) {
            val end = rest.indexOf("**", startIndex = 2)
            if (end < 0) {
                append(rest)
                break
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                append(rest.substring(2, end))
            }
            rest = rest.substring(end + 2)
        } else {
            val end = rest.indexOf('`', startIndex = 1)
            if (end < 0) {
                append(rest)
                break
            }
            withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) {
                append(rest.substring(1, end))
            }
            rest = rest.substring(end + 1)
        }
    }
}

@Composable
private fun MarkdownText(source: String) {
    val palette = LocalPalette.current
    val blocks = remember(source) { parseMarkdown(source) }

    blocks.forEach { block ->
        when (block) {
            is Block.Title -> {
                Text(
                    text = block.text,
                    fontSize = 26.sp,
                    lineHeight = 34.sp,
                    color = palette.onSurface,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            is Block.Heading -> {
                Text(
                    text = block.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.onSurface,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                )
            }
            is Block.Paragraph -> {
                Text(
                    text = inline(block.text),
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                    color = palette.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            is Block.Bullet -> {
                Row(modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(
                        text = "•",
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                        color = palette.onSurfaceVariant,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    Text(
                        text = inline(block.text),
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                        color = palette.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private const val PHONE = "spec:width=412dp,height=892dp"

@Preview(name = "Privacy policy", device = PHONE)
@Composable
private fun PreviewPrivacy() = RemoteEarTheme { DocumentScreen(MenuItem.PrivacyPolicy) {} }

@Preview(name = "Terms of use", device = PHONE)
@Composable
private fun PreviewTerms() = RemoteEarTheme { DocumentScreen(MenuItem.TermsOfUse) {} }

@Preview(name = "About", device = PHONE)
@Composable
private fun PreviewAbout() = RemoteEarTheme { AboutScreen(onBack = {}, onOpen = {}) }
