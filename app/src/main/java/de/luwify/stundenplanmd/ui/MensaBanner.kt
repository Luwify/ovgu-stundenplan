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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import de.luwify.stundenplanmd.data.update.CompanionDownload
import de.luwify.stundenplanmd.data.update.CompanionInstaller
import de.luwify.stundenplanmd.data.update.phase
import de.luwify.stundenplanmd.ui.theme.planColors

/**
 * The note about Mensa MD at the top of the settings: "Open" if it is installed, else "Download" (installed by the
 * updater's checked download), with progress and, if anything fails, a way to the release page. Nothing when hidden.
 */
@Composable
fun MensaBanner(companion: CompanionInstaller, hidden: Boolean, onHide: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val installed by companion.installed.collectAsStateWithLifecycle()
    val download by companion.download.collectAsStateWithLifecycle()
    val state = CompanionPolicy.banner(hidden, installed, download.phase())
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
                if (state != BannerState.BUSY) {
                    IconButton(onClick = onHide, Modifier.size(40.dp)) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.mensa_hide), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(Modifier.padding(end = 12.dp, top = 8.dp)) {
                when (state) {
                    BannerState.OPEN -> ButtonRow {
                        Button(onClick = { companion.open(context) }) { Text(stringResource(R.string.mensa_open)) }
                    }
                    BannerState.DOWNLOAD -> ButtonRow {
                        Button(onClick = companion::install) { Text(stringResource(R.string.mensa_download)) }
                    }
                    BannerState.BUSY -> {
                        val running = download as? CompanionDownload.Running
                        val percent = running?.takeIf { it.size > 0 }?.let { percent(it.bytes, it.size) }
                        Text(
                            when {
                                running != null -> stringResource(R.string.mensa_downloading) + (percent?.let { " " + stringResource(R.string.update_percent, it) } ?: "")
                                download is CompanionDownload.Verifying -> stringResource(R.string.mensa_verifying)
                                else -> stringResource(R.string.mensa_installing)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (percent != null) {
                            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                        ButtonRow {
                            if (download is CompanionDownload.Installing) {
                                // Android may never report back (e.g. Play Protect stops the install): never trap the user here.
                                TextButton(onClick = companion::forgetInstall) { Text(stringResource(R.string.close)) }
                            } else {
                                TextButton(onClick = companion::cancel) { Text(stringResource(R.string.cancel)) }
                            }
                        }
                    }
                    BannerState.FAILED -> {
                        (download as? CompanionDownload.Failed)?.let {
                            Text(failureText(it.failure), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        ButtonRow {
                            OutlinedButton(onClick = { companion.openReleasePage(context) }) { Text(stringResource(R.string.mensa_github)) }
                            TextButton(onClick = { companion.dismissFailure(); companion.install() }) { Text(stringResource(R.string.mensa_retry)) }
                        }
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

/** The "install unknown apps" question of a Mensa MD install, wherever the banner has been scrolled to. */
@Composable
fun MensaDialogs(companion: CompanionInstaller) {
    val permission by companion.permission.collectAsStateWithLifecycle()
    UnknownSourcesDialog(permission, onHandled = companion::permissionHandled, onDeclined = companion::permissionDeclined)
}
