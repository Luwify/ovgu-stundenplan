package de.luwify.stundenplanmd.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import de.luwify.stundenplanmd.R

/**
 * Trailing arrow of a button that opens a menu instead of acting (its label ends with "…"; a button that opens a
 * choice dialog gets only the "…"). Put it after the button's Text.
 */
@Composable
internal fun RowScope.MenuArrow() {
    Spacer(Modifier.width(4.dp))
    Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, Modifier.size(18.dp))
}
