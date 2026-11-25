package com.example.files

import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.content.SharedPreferences
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppI(val label: String, val pkg: String, val icon: ImageBitmap)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AppsScreen(prefs: SharedPreferences) {
    val c = LocalContext.current
    var apps by remember { mutableStateOf<List<AppI>?>(null) }
    val fav = remember { mutableStateListOf<String>().apply { addAll(prefs.getStringSet("favapps", emptySet())!!) } }
    var q by remember { mutableStateOf("") }
    var favOnly by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = c.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { AppI(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm).toBitmap(96, 96).asImageBitmap()) }
                .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
        }
    }
    Column {
        Text("Installed apps (A-Z)", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 8.dp), placeholder = { Text("Search apps") }, singleLine = true)
        Row(Modifier.padding(8.dp)) {
            FilterChip(!favOnly, { favOnly = false }, { Text("All") }); Spacer(Modifier.width(6.dp))
            FilterChip(favOnly, { favOnly = true }, { Text("★ Favourites") })
        }
        val list = apps?.filter { (!favOnly || it.pkg in fav) && it.label.contains(q, true) }
        if (list == null) Text("Loading…", Modifier.padding(12.dp)) else LazyColumn {
            items(list, key = { it.pkg }) { a ->
                Row(Modifier.fillMaxWidth().clickable { c.packageManager.getLaunchIntentForPackage(a.pkg)?.let { c.startActivity(it) } }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Image(a.icon, null, Modifier.size(44.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(a.label, Modifier.weight(1f), maxLines = 1)
                    TextButton({ if (!fav.remove(a.pkg)) fav.add(a.pkg); prefs.edit().putStringSet("favapps", fav.toSet()).apply() }) { Text(if (a.pkg in fav) "★" else "☆") }
                }
            }
        }
    }
}
