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

/** Trailing arrow of the detail sheet's "Ausblenden" button, which opens a menu instead of acting. After its Text. */
@Composable
internal fun RowScope.MenuArrow() {
    Spacer(Modifier.width(4.dp))
    Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, Modifier.size(18.dp))
}
