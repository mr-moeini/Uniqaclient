package com.mrmoeini.smsforwarder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** iOS-style card row: title, detail text and an on/off switch. Used by all three rule lists. */
class SimpleRuleAdapter<T>(
    private val items: List<T>,
    private val title: (T) -> String,
    private val detail: (T) -> String,
    private val enabled: (T) -> Boolean,
    private val onToggle: (T, Boolean) -> Unit,
    private val onEdit: (T) -> Unit
) : RecyclerView.Adapter<SimpleRuleAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvTitle)
        val detail: TextView = v.findViewById(R.id.tvDetail)
        val switch: CompoundButton = v.findViewById(R.id.swEnabled)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_rule, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = title(item)
        holder.detail.text = detail(item)
        holder.switch.setOnCheckedChangeListener(null)
        holder.switch.isChecked = enabled(item)
        holder.switch.setOnCheckedChangeListener { _, checked -> onToggle(item, checked) }
        holder.itemView.setOnClickListener { onEdit(item) }
    }

    override fun getItemCount(): Int = items.size
}
