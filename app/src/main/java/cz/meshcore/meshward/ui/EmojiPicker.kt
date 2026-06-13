package cz.meshcore.meshward.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// A small curated set of common emoji (a full picker would need an extra dependency).
private val emojis = (
    "😀 😁 😂 🤣 😊 😍 😘 😎 🤔 😴 😢 😭 😡 👍 👎 👏 🙏 💪 🙌 🤝 " +
        "👋 ✌️ 🤞 🔥 ✨ ⭐ 🎉 🎊 ❤️ 🧡 💛 💚 💙 💜 🖤 💯 ✅ ❌ ❓ ❗ " +
        "🚀 📡 🛰️ 📶 🔋 ⚡ 🌍 🗺️ 📍 🧭 ⛺ 🏔️ 🧗 🚴 🏃 ☕ 🍺 🍕 🌮 🎵"
    ).split(" ")

/** Bottom-sheet emoji grid; [onPick] gets the chosen emoji to insert into the composer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier.fillMaxWidth().height(280.dp).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            items(emojis) { e ->
                Text(
                    e,
                    fontSize = 26.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable { onPick(e) }.padding(8.dp),
                )
            }
        }
    }
}
