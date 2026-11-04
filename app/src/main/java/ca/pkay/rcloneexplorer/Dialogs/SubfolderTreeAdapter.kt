package ca.pkay.rcloneexplorer.Dialogs

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ca.pkay.rcloneexplorer.R

/**
 * Flat visible list of tree rows: expand toggles children; checkbox edits [selectedPaths] only
 * (no auto-check of descendants).
 */
class SubfolderTreeAdapter(
    private val selectedPaths: MutableSet<String>,
    private val onExpandClick: (Node) -> Unit,
) : RecyclerView.Adapter<SubfolderTreeAdapter.Holder>() {

    data class Node(
        val relativePath: String,
        val name: String,
        val depth: Int,
        var expanded: Boolean = false,
        var loading: Boolean = false,
        var childrenLoaded: Boolean = false,
        var children: List<Node> = emptyList(),
    )

    private val visible = ArrayList<Node>()

    fun rebuildVisibleFromRoots(roots: List<Node>) {
        visible.clear()
        fun walk(nodes: List<Node>) {
            for (node in nodes) {
                visible.add(node)
                if (node.expanded) {
                    walk(node.children)
                }
            }
        }
        walk(roots)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subfolder_tree_row, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val node = visible[position]
        val density = holder.itemView.resources.displayMetrics.density
        holder.indent.layoutParams = holder.indent.layoutParams.apply {
            width = (node.depth * 20 * density).toInt()
        }

        holder.name.text = node.name
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = node.relativePath in selectedPaths
        holder.check.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedPaths.add(node.relativePath)
            } else {
                selectedPaths.remove(node.relativePath)
            }
        }

        holder.loading.visibility = if (node.loading) View.VISIBLE else View.GONE
        holder.expand.rotation = if (node.expanded) 90f else 0f
        holder.expand.isEnabled = !node.loading
        holder.expand.setOnClickListener { onExpandClick(node) }
        holder.name.setOnClickListener { holder.check.toggle() }
    }

    override fun getItemCount(): Int = visible.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val indent: View = itemView.findViewById(R.id.subfolder_tree_indent)
        val expand: ImageButton = itemView.findViewById(R.id.subfolder_tree_expand)
        val check: CheckBox = itemView.findViewById(R.id.subfolder_tree_check)
        val name: TextView = itemView.findViewById(R.id.subfolder_tree_name)
        val loading: ProgressBar = itemView.findViewById(R.id.subfolder_tree_loading)
    }
}
