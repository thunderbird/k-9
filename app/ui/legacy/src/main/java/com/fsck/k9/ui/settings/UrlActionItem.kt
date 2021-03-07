package com.fsck.k9.ui.settings

import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.fsck.k9.ui.R
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.drag.IDraggable
import com.mikepenz.fastadapter.items.AbstractItem

internal class UrlActionItem(
    override var identifier: Long,
    val text: String,
    val url: String,
    val icon: Int
) : AbstractItem<UrlActionItem.ViewHolder>(), IDraggable {
    override val type = R.id.settings_list_url_item

    override val layoutRes = R.layout.text_icon_list_item

    override var isDraggable = false

    fun withIsDraggable(draggable: Boolean): UrlActionItem {
        this.isDraggable = draggable
        return this
    }

    override fun getViewHolder(v: View) = ViewHolder(v)

    class ViewHolder(view: View) : FastAdapter.ViewHolder<UrlActionItem>(view) {
        val text: TextView = view.findViewById(R.id.text)
        val icon: ImageView = view.findViewById(R.id.icon)

        override fun bindView(item: UrlActionItem, payloads: List<Any>) {
            text.text = item.text

            val outValue = TypedValue()
            icon.context.theme.resolveAttribute(item.icon, outValue, true)
            icon.setImageResource(outValue.resourceId)
        }

        override fun unbindView(item: UrlActionItem) {
            text.text = null
            icon.setImageDrawable(null)
        }
    }
}
