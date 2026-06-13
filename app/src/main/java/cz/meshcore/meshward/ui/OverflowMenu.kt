package cz.meshcore.meshward.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The standard three-dot header menu. Always offers "Settings"; [extraItems] lets a page add
 * its own entries above it (each should call the provided `dismiss` after acting).
 */
@Composable
fun OverflowMenu(
    onOpenSettings: () -> Unit,
    onOpenAbout: (() -> Unit)? = null,
    extraItems: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        extraItems { open = false }
        DropdownMenuItem(
            text = { Text("Settings") },
            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
            onClick = { open = false; onOpenSettings() },
        )
        if (onOpenAbout != null) {
            DropdownMenuItem(
                text = { Text("About") },
                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                onClick = { open = false; onOpenAbout() },
            )
        }
    }
}
