package com.studynotes.app.library.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studynotes.app.model.DocType
import com.studynotes.app.model.LibraryDocument
import com.studynotes.app.model.Palette
import com.studynotes.app.ui.theme.MetaTextStyle

private val ThumbnailShape = RoundedCornerShape(12.dp)
private val MenuWidth = 232.dp

/** The default highlighter colour, for the marks badge's dot. */
private val HighlighterDotColor = Color(Palette.HIGHLIGHTER_COLORS[0])

/** Keeps the light yellow dot visible on a light badge. */
private val DotOutlineColor = Color.Black.copy(alpha = 0.25f)

/** Padding around a card's content; the grid subtracts it so thumbnails keep the intended gaps. */
val CardInnerPadding = 6.dp

/** Page frame aspect: width / height of an A4-ish portrait page. */
private const val PAGE_ASPECT = 3f / 4f

/** Actions offered by a card's menu (⋮ button or long-press). */
class DocumentCardActions(
    val onOpen: (LibraryDocument) -> Unit,
    val onRename: (LibraryDocument) -> Unit,
    val onShareOriginal: (LibraryDocument) -> Unit,
    val onShareAnnotated: (LibraryDocument) -> Unit,
    val onDelete: (LibraryDocument) -> Unit,
)

/**
 * One document in the library grid: first-page thumbnail in a 3:4 page frame with a type chip and,
 * when annotated, a marks badge; the title (two lines max) and a metadata line. Tap opens;
 * long-press or ⋮ shows the menu.
 *
 * [loadThumbnail] is called again whenever the file or its mark count changes; the previous
 * thumbnail stays visible while the new one loads.
 */
@Composable
fun DocumentCard(
    document: LibraryDocument,
    loadThumbnail: suspend (LibraryDocument) -> ImageBitmap?,
    actions: DocumentCardActions,
    modifier: Modifier = Modifier,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val meta = remember(document.type, document.sizeBytes) { metaLine(document.type, document.sizeBytes) }

    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                onClickLabel = "Open",
                onLongClickLabel = "More options",
                role = Role.Button,
                onLongClick = { menuOpen = true },
                onClick = { actions.onOpen(document) },
            )
            .padding(CardInnerPadding),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PAGE_ASPECT)
                .clip(ThumbnailShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ThumbnailShape),
        ) {
            Thumbnail(document, loadThumbnail)
            TypeChip(
                type = document.type,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
            )
            if (document.annotationCount > 0) {
                MarksBadge(
                    count = document.annotationCount,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 2.dp, top = 2.dp),
            ) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = meta,
                    style = MetaTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The menu is anchored here for both the ⋮ button and the long-press.
            Box {
                TooltipIconButton(
                    icon = Icons.Rounded.MoreVert,
                    label = "More options",
                    contentDescription = "More options for ${document.title}",
                    onClick = { menuOpen = true },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DocumentMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    document = document,
                    actions = actions,
                )
            }
        }
    }
}

@Composable
private fun Thumbnail(document: LibraryDocument, loadThumbnail: suspend (LibraryDocument) -> ImageBitmap?) {
    // Not reset on key change: the old thumbnail stays until the refreshed one arrives.
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        document.fileName,
        document.lastModified,
        document.annotationCount,
    ) {
        value = loadThumbnail(document) ?: value
    }
    Crossfade(targetState = bitmap, animationSpec = tween(220), label = "thumbnail") { image ->
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    // A page is white in every theme; the bitmap is letterboxed onto it.
                    .background(Color.White),
            )
        } else {
            ThumbnailPlaceholder(document.type)
        }
    }
}

/** Tonal page with the type's glyph, shown while the thumbnail loads or if it can't be rendered. */
@Composable
private fun ThumbnailPlaceholder(type: DocType) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = type.glyph,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        )
    }
}

/** Small "PDF" / "Word" label over the bottom-left corner of the thumbnail. */
@Composable
private fun TypeChip(type: DocType, modifier: Modifier = Modifier) {
    val (container, content) = when (type) {
        DocType.PDF -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        DocType.DOCX -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(
        // The meta line already says the type; don't read it twice.
        modifier = modifier.clearAndSetSemantics { },
        shape = MaterialTheme.shapes.extraSmall,
        color = container,
        contentColor = content,
        shadowElevation = 1.dp,
    ) {
        Text(
            text = type.label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Highlighter dot + number of marks over the top-right corner of the thumbnail: the one sign that
 * a document has highlights or notes (the thumbnail only shows page 1).
 */
@Composable
private fun MarksBadge(count: Int, modifier: Modifier = Modifier) {
    val description = marksLabel(count)
    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 7.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(HighlighterDotColor, CircleShape)
                    .border(0.5.dp, DotOutlineColor, CircleShape),
            )
            Spacer(Modifier.width(4.dp))
            Text(text = marksBadgeText(count), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DocumentMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    document: LibraryDocument,
    actions: DocumentCardActions,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuItem("Rename", Icons.Rounded.DriveFileRenameOutline) {
            onDismiss()
            actions.onRename(document)
        }
        MenuItem("Share original", Icons.Rounded.Share) {
            onDismiss()
            actions.onShareOriginal(document)
        }
        // Without marks an "annotated" PDF would just be a copy of the original; a Word document
        // can still usefully be shared as a PDF.
        val shareAsPdf = when {
            document.annotationCount > 0 -> "Share annotated PDF"
            document.type == DocType.DOCX -> "Share as PDF"
            else -> null
        }
        if (shareAsPdf != null) {
            MenuItem(shareAsPdf, Icons.Rounded.IosShare) {
                onDismiss()
                actions.onShareAnnotated(document)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        MenuItem("Delete", Icons.Rounded.DeleteOutline, color = MaterialTheme.colorScheme.error) {
            onDismiss()
            actions.onDelete(document)
        }
    }
}

@Composable
private fun MenuItem(
    text: String,
    icon: ImageVector,
    color: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    val colors = if (color == Color.Unspecified) {
        MenuDefaults.itemColors()
    } else {
        MenuDefaults.itemColors(textColor = color, leadingIconColor = color)
    }
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null) },
        colors = colors,
        modifier = Modifier.width(MenuWidth),
    )
}

/** Glyph used for a document type in placeholders. */
val DocType.glyph: ImageVector
    get() = when (this) {
        DocType.PDF -> Icons.Rounded.PictureAsPdf
        DocType.DOCX -> Icons.AutoMirrored.Rounded.Article
    }
