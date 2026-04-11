package ca.pkay.rcloneexplorer.Settings

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.util.MediaFolderPolicy
import ca.pkay.rcloneexplorer.util.MediaFolderPolicyRow

class MediaFolderPolicyAdapter(
    private var rows: List<MediaFolderPolicyRow>,
    private val listener: Listener,
) : RecyclerView.Adapter<MediaFolderPolicyAdapter.RowHolder>() {

    interface Listener {
        fun onThumbnailToggled(normalizedPath: String, enabled: Boolean)
        fun onCacheToggled(normalizedPath: String, enabled: Boolean)
        fun onRemoveRow(normalizedPath: String)
    }

    fun submitRows(newRows: List<MediaFolderPolicyRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media_folder_policy_row, parent, false)
        return RowHolder(view)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val row = rows[position]
        val path = MediaFolderPolicy.normalizeFolder(row.path)
        holder.pathView.text = path
        holder.thumb.setOnCheckedChangeListener(null)
        holder.cache.setOnCheckedChangeListener(null)
        holder.thumb.isChecked = row.thumbnail
        holder.cache.isChecked = row.cache
        holder.thumb.setOnCheckedChangeListener { _, isChecked ->
            listener.onThumbnailToggled(path, isChecked)
        }
        holder.cache.setOnCheckedChangeListener { _, isChecked ->
            listener.onCacheToggled(path, isChecked)
        }
        holder.remove.setOnClickListener {
            listener.onRemoveRow(path)
        }
    }

    override fun getItemCount(): Int = rows.size

    class RowHolder(root: android.view.View) : RecyclerView.ViewHolder(root) {
        val pathView: TextView = root.findViewById(R.id.media_policy_row_path)
        val thumb: CheckBox = root.findViewById(R.id.media_policy_row_thumbnail)
        val cache: CheckBox = root.findViewById(R.id.media_policy_row_cache)
        val remove: ImageButton = root.findViewById(R.id.media_policy_row_remove)
    }
}
