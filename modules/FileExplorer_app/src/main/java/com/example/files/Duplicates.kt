@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.example.files

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest

fun md5(f: File): String {
    val md = MessageDigest.getInstance("MD5")
    f.inputStream().use { s ->
        val b = ByteArray(65536)
        while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

@Composable fun Duplicates(all: List<File>, onChange: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<List<File>>?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun scan() {
        busy = true
        scope.launch {
            groups = withContext(Dispatchers.IO) {
                val skip = File(ROOT, "Duplicate").path
                all.filter { it.length() > 0 && !it.path.startsWith(skip) }
                    .groupBy { it.length() }.values.filter { it.size > 1 }
                    .flatMap { g -> g.groupBy { x -> try { md5(x) } catch (e: Exception) { x.path } }.values.filter { it.size > 1 } }
                    .map { g -> g.sortedBy { it.lastModified() } }
                    .sortedByDescending { it[0].length() * (it.size - 1) }
            }
            busy = false
        }
    }

    fun moveAll() {
        val list = groups ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                val d = File(ROOT, "Duplicate").apply { mkdirs() }
                list.flatMap { it.drop(1) }.forEach { try { moveTo(it, d) } catch (e: Exception) {} }
            }
            Toast.makeText(c, "Moved to Duplicate folder", Toast.LENGTH_SHORT).show()
            groups = null; onChange()
        }
    }

    Column(Modifier.padding(12.dp)) {
        Text("Duplicate finder", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ scan() }, enabled = !busy) { Text(if (busy) "Scanning…" else "Scan") }
            if (!groups.isNullOrEmpty()) Button({ moveAll() }) { Text("Move to 'Duplicate'") }
        }
        groups?.let { g ->
            val extra = g.sumOf { it[0].length() * (it.size - 1) }
            Text("${g.size} groups • ${size(extra)} wasted. First (oldest) copy of each is kept.",
                Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn { items(g) { grp ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text("${grp.size} copies • ${size(grp[0].length())} each", fontWeight = FontWeight.Bold)
                    grp.forEachIndexed { i, f ->
                        Text((if (i == 0) "✔ keep: " else "↪ ") + f.path, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
            } }
        }
    }
}
