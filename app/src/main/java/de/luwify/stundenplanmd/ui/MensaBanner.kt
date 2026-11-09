package de.luwify.stundenplanmd.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.update.BannerState
import de.luwify.stundenplanmd.core.update.CompanionPolicy
import de.luwify.stundenplanmd.data.update.CompanionLauncher
import de.luwify.stundenplanmd.ui.theme.planColors

/**
 * The note about Mensa MD at the top of the settings: "Open" if it is installed, else "Download", which opens its release page
 * in the browser. Nothing when hidden.
 */
@Composable
fun MensaBanner(companion: CompanionLauncher, hidden: Boolean, onHide: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val installed by companion.installed.collectAsStateWithLifecycle()
    val state = CompanionPolicy.banner(hidden, installed)
    if (state == BannerState.HIDDEN) return

    val colors = planColors()
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardContainer),
        border = BorderStroke(colors.cardBorderWidth, colors.cardBorder),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                androidx.compose.foundation.Image(painterResource(R.drawable.ic_mensa), null, Modifier.size(44.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp, top = 2.dp)) {
                    Text(stringResource(R.string.mensa_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.mensa_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onHide, Modifier.size(40.dp)) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.mensa_hide), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.padding(end = 12.dp, top = 8.dp)) {
                when (state) {
                    BannerState.OPEN -> ButtonRow {
                        Button(onClick = { companion.open(context) }) { Text(stringResource(R.string.mensa_open)) }
                    }
                    BannerState.DOWNLOAD -> ButtonRow {
                        Button(onClick = { companion.openReleasePage(context) }) { Text(stringResource(R.string.mensa_download)) }
                    }
                    BannerState.HIDDEN -> {}
                }
            }
        }
    }
}

@Composable
private fun ButtonRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}
