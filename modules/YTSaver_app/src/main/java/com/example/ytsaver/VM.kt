package com.example.ytsaver

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VM(app: Application) : AndroidViewModel(app) {
    var ready by mutableStateOf(false)
    var status by mutableStateOf("Starting engine…")
    var busy by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var results by mutableStateOf(listOf<Engine.Item>())
    var selected by mutableStateOf<Engine.Item?>(null)
    var stream by mutableStateOf<Engine.Stream?>(null)
    var saved by mutableStateOf(listOf<Engine.Saved>())

    private val ctx: Context get() = getApplication()

    private fun task(msg: String, block: () -> String) {
        viewModelScope.launch(Dispatchers.IO) {
            busy = true; progress = 0f; status = msg
            status = try {
                block()
            } catch (e: Throwable) {
                "Error: " + (e.message ?: e.toString()).take(300)
            }
            busy = false
        }
    }

    init {
        task("Starting engine…") { Engine.init(ctx); ready = true
            runCatching { Engine.update(ctx) }
            "Engine ready. Search something." }
    }

    fun search(q: String) {
        val t = q.trim()
        if (t.isBlank()) return
        Engine.videoId(t)?.let { open(Engine.Item(it, "Loading…", "")); return }
        task("Searching…") { results = Engine.search(q); "${results.size} results" }
    }

    fun open(i: Engine.Item) {
        selected = i
        stream = null
        task("Loading video…") {
            val s = Engine.stream(i.id)
            stream = s
            selected = selected?.copy(title = s.title)
            "Ready"
        }
    }

    fun update() = task("Updating engine…") { Engine.update(ctx); "Engine updated" }

    fun download(i: Engine.Item, spec: Engine.Spec) = task("Downloading…") {
        "Saved to Downloads/YTSaver: " + Engine.download(ctx, i.id, spec) { p, _ -> progress = p }
    }

    fun frame(sec: Int) {
        val u = stream?.video ?: return
        val t = selected?.title ?: "frame"
        task("Saving frame…") { "Saved to Downloads/YTSaver: " + Engine.frame(ctx, u, sec, t) }
    }

    fun refreshSaved() {
        viewModelScope.launch(Dispatchers.IO) { saved = Engine.listSaved(ctx) }
    }
}
