package com.cloudframe.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/** Browse Degoo folders with the remote and pick the one to show. */
class FolderPickerActivity : AppCompatActivity() {
    private val app get() = application as FrameApp
    private val path = mutableListOf<Pair<String, String>>()   // (id, name), root has id "0"
    private lateinit var list: RecyclerView
    private lateinit var title: TextView
    private lateinit var status: TextView

    private sealed class Row {
        object UseThis : Row()
        data class Folder(val id: String, val name: String) : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folders)
        list = findViewById(R.id.folderList)
        title = findViewById(R.id.folderTitle)
        status = findViewById(R.id.folderStatus)
        list.layoutManager = LinearLayoutManager(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (path.size > 1) { path.removeAt(path.lastIndex); load() } else finish()
            }
        })
        path += "0" to getString(R.string.degoo_root)
        load()
    }

    private fun load() {
        val (id, _) = path.last()
        title.text = path.joinToString("  ›  ") { it.second }
        status.text = getString(R.string.loading)
        status.visibility = View.VISIBLE
        list.adapter = Adapter(emptyList())
        lifecycleScope.launch {
            try {
                val rows = mutableListOf<Row>()
                if (id != "0") rows += Row.UseThis
                app.api.folders(id).forEach { rows += Row.Folder(it.id, it.name) }
                status.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                status.text = getString(R.string.no_subfolders)
                list.adapter = Adapter(rows)
                list.post { list.getChildAt(0)?.requestFocus() }
            } catch (e: Exception) {
                status.text = getString(R.string.msg_error, e.message ?: "")
            }
        }
    }

    private fun choose() {
        val prefs = app.prefs
        prefs.folderId = path.last().first
        prefs.folderName = path.drop(1).joinToString(" / ") { it.second }
        app.library.clear()
        finish()
    }

    private inner class Adapter(val rows: List<Row>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val text = holder.itemView as TextView
            when (val row = rows[position]) {
                is Row.UseThis -> {
                    text.text = getString(R.string.use_this_folder, path.last().second)
                    text.setOnClickListener { choose() }
                }
                is Row.Folder -> {
                    text.text = getString(R.string.folder_row, row.name)
                    text.setOnClickListener { path += row.id to row.name; load() }
                }
            }
        }
    }
}
