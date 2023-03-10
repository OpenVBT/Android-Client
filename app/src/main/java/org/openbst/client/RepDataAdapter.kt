package org.openbst.client

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import kotlinx.android.synthetic.main.row_repdata.view.*


class RepDataAdapter(
    private val items: List<RepData>,
    private val onClickListener: ((repData: RepData) -> Unit)
) : RecyclerView.Adapter<RepDataAdapter.ViewHolder>() {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RepDataAdapter.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.row_repdata,
            parent,
            false
        )
        return ViewHolder(view, onClickListener)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    class ViewHolder(
        private val view: View,
        private val onClickListener: ((repData: RepData) -> Unit)
    ) : RecyclerView.ViewHolder(view) {

        fun bind(repData: RepData) {
            view.max_velocity.text  = "%.2f".format(repData.max_velocity)
            view.min_velocity.text  = "%.2f".format(repData.min_velocity)
            view.max_accel.text     = "%.2f".format(repData.max_accel)
            view.min_accel.text     = "%.2f".format(repData.min_accel)

            view.setOnClickListener { onClickListener.invoke(repData) }
        }
    }
}