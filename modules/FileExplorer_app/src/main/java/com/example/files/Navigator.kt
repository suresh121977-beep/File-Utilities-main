@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.example.files

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun Navigator(mode: String) {
    val c = LocalContext.current
    var d by remember { mutableStateOf(ROOT) }
    var sc by remember { mutableStateOf<File?>(null) }
    var list by remember { mutableStateOf(emptyList<File>()) }
    LaunchedEffect(d) {
        list = withContext(Dispatchers.IO) {
            d.listFiles()?.filter { !it.name.startsWith(".") }?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }) ?: emptyList()
        }
    }
    BackHandler(d != ROOT) { d = d.parentFile ?: ROOT }
    Column {
        Text(if (mode == "view") "Quick view – tap folder to enter, file to open" else "Shortcut maker – tap file (or long-press folder) to make a shortcut",
            Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall)
        Text(d.path, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton({ d = d.parentFile ?: ROOT }, enabled = d != ROOT) { Text("⬆ Up") }
            if (mode != "view") TextButton({ sc = d }) { Text("Shortcut for this folder") }
        }
        LazyColumn {
            items(list, key = { it.path }) { f ->
                Text((if (f.isDirectory) "📁 " else "📄 ") + f.name,
                    Modifier.fillMaxWidth().combinedClickable(
                        onClick = { if (f.isDirectory) d = f else if (mode == "view") openFile(c, f) else sc = f },
                        onLongClick = { if (mode != "view") sc = f }).padding(12.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    sc?.let { ShortcutDialog(it) { sc = null } }
}

fun defaultIcon(e: String): Bitmap {
    val b = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
    val p = Paint(Paint.ANTI_ALIAS_FLAG); p.textSize = 130f; p.textAlign = Paint.Align.CENTER
    Canvas(b).drawText(e, 96f, 140f, p)
    return b
}

@Composable fun ShortcutDialog(f: File, onDismiss: () -> Unit) {
    val c = LocalContext.current
    var label by remember { mutableStateOf(f.name) }
    var icon by remember { mutableStateOf<Bitmap?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) icon = try {
            c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) }?.let { Bitmap.createScaledBitmap(it, 192, 192, true) }
        } catch (e: Exception) { null }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Create home-screen shortcut") },
        text = { Column {
            Text(f.path, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(label, { label = it }, singleLine = true)
            TextButton({ pick.launch("image/*") }) { Text(if (icon == null) "Choose my icon image (optional)" else "Icon chosen ✓") }
        } },
        confirmButton = { TextButton({
            if (ShortcutManagerCompat.isRequestPinShortcutSupported(c)) {
                val bmp = icon ?: defaultIcon(if (f.isDirectory) "📁" else "📄")
                val i = Intent(c, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("path", f.path)
                val info = ShortcutInfoCompat.Builder(c, "sc" + f.path.hashCode()).setShortLabel(label.ifBlank { f.name })
                    .setIcon(IconCompat.createWithBitmap(bmp)).setIntent(i).build()
                ShortcutManagerCompat.requestPinShortcut(c, info, null)
            } else Toast.makeText(c, "Your launcher doesn't support shortcuts", Toast.LENGTH_LONG).show()
            onDismiss()
        }) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
