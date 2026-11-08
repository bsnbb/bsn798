package com.qingquan.ilife

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.qingquan.ilife.core.Account

/**
 * 账号列表适配器
 */
class AccountAdapter(
    private val data: List<Account>,
    private val onClick: (Account) -> Unit
) : RecyclerView.Adapter<AccountAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvName)
        val tvScore: TextView = v.findViewById(R.id.tvScore)
        val tvStatus: TextView = v.findViewById(R.id.tvStatus)
        val tvGain: TextView = v.findViewById(R.id.tvGain)
        val tvMission: TextView = v.findViewById(R.id.tvMission)
        val dot: View = v.findViewById(R.id.dot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_account, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = data[position]
        holder.tvName.text = a.name
        holder.tvScore.text = "${a.score} 分"

        // 状态
        val st = a.status
        holder.tvStatus.text = st
        val color = when {
            st == "完成" || st == "已完成" -> 0xFF00E676.toInt()
            st.contains("限流") -> 0xFFFFB300.toInt()
            st.contains("失效") || st.contains("失败") -> 0xFFFF5252.toInt()
            else -> 0xFF8B949E.toInt()
        }
        holder.tvStatus.setTextColor(color)
        holder.dot.setBackgroundColor(color)

        // 今日增益
        if (a.todayGain > 0) {
            holder.tvGain.text = "今日 +${a.todayGain}"
            holder.tvGain.setTextColor(0xFF00E676.toInt())
            holder.tvGain.visibility = View.VISIBLE
        } else {
            holder.tvGain.visibility = View.GONE
        }

        // 任务进度
        if (a.missionTotal > 0) {
            holder.tvMission.text = "任务 ${a.missionDone}/${a.missionTotal}"
            holder.tvMission.visibility = View.VISIBLE
        } else {
            holder.tvMission.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick(a) }
    }

    override fun getItemCount(): Int = data.size
}
