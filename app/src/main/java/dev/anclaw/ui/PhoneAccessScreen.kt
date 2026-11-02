package dev.anclaw.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.anclaw.AppViewModel
import dev.anclaw.device.PhoneAccess

/** Grants for the agent's phone tools (the "android" MCP server). */
@Composable
fun PhoneAccessScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var refresh by remember { mutableIntStateOf(0) }
    // Re-read after returning from a system Settings screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val items = remember(refresh) { PhoneAccess.status(context) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    var enabled by remember { mutableStateOf(vm.prefs.phoneTools) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Phone tools for the agent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Switch(enabled, { enabled = it; vm.prefs.phoneTools = it; vm.restartAgent() })
                    }
                    Text(
                        "Adds the \"android\" MCP server: location, contacts, calls, gallery, apps, screen reading and " +
                            "control, lock and settings. Each tool works only with its grant below. Actions (calls, " +
                            "taps, typing, settings) always go through claw's permission prompt unless you choose Full access.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        request.launch(items.filter { !it.granted && it.key != "location_bg" }.flatMap { it.runtime }.toTypedArray())
                    }) { Text("Grant all runtime permissions") }
                }
            }
        }
        items(items, key = { it.key }) { a ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (a.granted) Icons.Default.CheckCircle else Icons.Outlined.Circle, null, Modifier.size(18.dp),
                            tint = if (a.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        Text("  ${a.title}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (!a.granted && a.manual == null) OutlinedButton(onClick = {
                            when {
                                a.runtime.isNotEmpty() -> request.launch(a.runtime.toTypedArray())
                                a.settingsIntent != null -> runCatching { context.startActivity(a.settingsIntent) }
                            }
                        }) { Text("Grant") }
                    }
                    Text(a.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (a.manual != null && !a.granted) {
                        Text(a.manual, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp))
                        TextButton(onClick = { clipboard.setText(AnnotatedString(a.manual)) }) { Text("Copy command") }
                    }
                }
            }
        }
    }
}
