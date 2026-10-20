package de.luwify.stundenplanmd.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Line icons drawn from scratch (24 grid, black; tint where used). The gear is ic_settings.xml (NOTICE). */
private fun lineIcon(name: String, width: Float = 1.8f, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()

private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
    moveTo(l + rad, t)
    horizontalLineTo(r - rad)
    curveTo(r - rad / 2, t, r, t + rad / 2, r, t + rad)
    verticalLineTo(b - rad)
    curveTo(r, b - rad / 2, r - rad / 2, b, r - rad, b)
    horizontalLineTo(l + rad)
    curveTo(l + rad / 2, b, l, b - rad / 2, l, b - rad)
    verticalLineTo(t + rad)
    curveTo(l, t + rad / 2, l + rad / 2, t, l + rad, t)
    close()
}

/** Week grid: a sheet with five day columns. */
val WeekIcon: ImageVector by lazy {
    lineIcon("Week") {
        roundRect(3f, 4.5f, 21f, 20f, 2f)
        moveTo(3f, 9f); horizontalLineTo(21f)
        moveTo(8.5f, 9f); verticalLineTo(20f)
        moveTo(12f, 9f); verticalLineTo(20f)
        moveTo(15.5f, 9f); verticalLineTo(20f)
    }
}

/** Calendar page with a list: the Today list. */
val TodayIcon: ImageVector by lazy {
    lineIcon("Today") {
        roundRect(3.5f, 4.5f, 20.5f, 20f, 2f)
        moveTo(8f, 3f); verticalLineTo(6f)
        moveTo(16f, 3f); verticalLineTo(6f)
        moveTo(7.5f, 11f); horizontalLineTo(16.5f)
        moveTo(7.5f, 15f); horizontalLineTo(13f)
    }
}

/** Arrow into a tray: import a file. */
val ImportIcon: ImageVector by lazy {
    lineIcon("Import") {
        moveTo(12f, 3.5f); verticalLineTo(14f)
        moveTo(7.5f, 9.5f); lineTo(12f, 14f); lineTo(16.5f, 9.5f)
        moveTo(4f, 14.5f); verticalLineTo(18f)
        curveTo(4f, 19.1f, 4.9f, 20f, 6f, 20f)
        horizontalLineTo(18f)
        curveTo(19.1f, 20f, 20f, 19.1f, 20f, 18f)
        verticalLineTo(14.5f)
    }
}

/** Pencil: edit. */
val EditIcon: ImageVector by lazy {
    lineIcon("Edit") {
        moveTo(4f, 20f); lineTo(4.5f, 16f); lineTo(15.5f, 5f)
        curveTo(16.3f, 4.2f, 17.7f, 4.2f, 18.5f, 5f)
        lineTo(19f, 5.5f)
        curveTo(19.8f, 6.3f, 19.8f, 7.7f, 19f, 8.5f)
        lineTo(8f, 19.5f); close()
        moveTo(13.5f, 7f); lineTo(17f, 10.5f)
    }
}

/** Arrow pointing left, for the back button. */
val BackIcon: ImageVector by lazy {
    lineIcon("Back", 2f) {
        moveTo(19f, 12f); horizontalLineTo(5f)
        moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
    }
}


/** A check mark (selected entry of a drop-down menu or color shade). */
val CheckIcon: ImageVector by lazy { lineIcon("Check", 2f) { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) } }

/** A chevron pointing down (drop-down field); turned 180° while the menu is open. */
val ChevronDownIcon: ImageVector by lazy { lineIcon("ChevronDown", 2f) { moveTo(6f, 9.5f); lineTo(12f, 15.5f); lineTo(18f, 9.5f) } }

/** A calendar page with a cross: the date is cancelled (for me). */
val CancelDateIcon: ImageVector by lazy {
    lineIcon("CancelDate", 2f) {
        roundRect(3.5f, 4.5f, 20.5f, 20f, 2f)
        moveTo(8f, 3f); verticalLineTo(6f)
        moveTo(16f, 3f); verticalLineTo(6f)
        moveTo(9.5f, 11f); lineTo(14.5f, 16f)
        moveTo(14.5f, 11f); lineTo(9.5f, 16f)
    }
}

/** An eye, struck through: hide. */
val HideIcon: ImageVector by lazy {
    lineIcon("Hide", 2f) {
        moveTo(2.5f, 12f)
        curveTo(4.5f, 8f, 8f, 5.5f, 12f, 5.5f)
        curveTo(16f, 5.5f, 19.5f, 8f, 21.5f, 12f)
        curveTo(19.5f, 16f, 16f, 18.5f, 12f, 18.5f)
        curveTo(8f, 18.5f, 4.5f, 16f, 2.5f, 12f); close()
        moveTo(15f, 12f)
        curveTo(15f, 13.7f, 13.7f, 15f, 12f, 15f)
        curveTo(10.3f, 15f, 9f, 13.7f, 9f, 12f)
        curveTo(9f, 10.3f, 10.3f, 9f, 12f, 9f)
        curveTo(13.7f, 9f, 15f, 10.3f, 15f, 12f); close()
        moveTo(4f, 4f); lineTo(20f, 20f)
    }
}

/** Three dots in a row: more actions. */
val MoreIcon: ImageVector by lazy {
    lineIcon("More", 3f) {
        moveTo(5.5f, 12f); lineTo(5.5f, 12f)
        moveTo(12f, 12f); lineTo(12f, 12f)
        moveTo(18.5f, 12f); lineTo(18.5f, 12f)
    }
}

/** A sheet with a folded corner and two lines: an own note. */
val NoteIcon: ImageVector by lazy {
    lineIcon("Note", 2f) {
        moveTo(14f, 20f); horizontalLineTo(6f)
        curveTo(4.9f, 20f, 4f, 19.1f, 4f, 18f)
        verticalLineTo(6f)
        curveTo(4f, 4.9f, 4.9f, 4f, 6f, 4f)
        horizontalLineTo(18f)
        curveTo(19.1f, 4f, 20f, 4.9f, 20f, 6f)
        verticalLineTo(14f); lineTo(14f, 20f); verticalLineTo(14f); horizontalLineTo(20f)
        moveTo(8f, 8.5f); horizontalLineTo(16f)
        moveTo(8f, 12f); horizontalLineTo(12f)
    }
}
