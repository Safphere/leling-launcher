package com.safphere.launcher.settings

import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.safphere.launcher.data.Prefs
import com.safphere.launcher.databinding.ActivityAppPickerBinding
import com.safphere.launcher.databinding.ItemAppPickerBinding

/** 白名单编辑（子女）：勾选允许老人看到的应用，保存后桌面「常用应用」只显示这些 */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private val checked = HashSet<String>()
    private var allApps: List<ResolveInfo> = emptyList()
    private var shownApps: List<ResolveInfo> = emptyList()
    private var listAdapter: RecyclerView.Adapter<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnDone.setOnClickListener {
            Prefs.saveWhitelist(checked)
            toast("已保存")
            finish()
        }

        checked.addAll(Prefs.whitelist())
        binding.appList.layoutManager = LinearLayoutManager(this)
        loadApps()

        // 快速搜索：边输边筛（名称/包名），输入框在列表外，重筛不丢焦点
        binding.appSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })
    }

    private fun applyFilter(qRaw: String) {
        val q = qRaw.trim()
        shownApps = if (q.isEmpty()) allApps else allApps.filter {
            runCatching { it.loadLabel(packageManager).toString() }.getOrDefault("")
                .contains(q, true) || it.activityInfo.packageName.contains(q, true)
        }
        binding.appCount.text = if (q.isEmpty()) "共 ${shownApps.size} 个应用"
        else "「$q」找到 ${shownApps.size} 个"
        listAdapter?.notifyDataSetChanged()
    }

    private fun loadApps() {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        allApps = pm.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString() }
        shownApps = allApps

        val adapter = object : RecyclerView.Adapter<VH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
                VH(ItemAppPickerBinding.inflate(layoutInflater, parent, false))

            override fun getItemCount(): Int = shownApps.size

            override fun onBindViewHolder(holder: VH, position: Int) {
                val ri = shownApps[position]
                val pkg = ri.activityInfo.packageName
                holder.b.appIcon.setImageDrawable(ri.loadIcon(pm))
                holder.b.appLabel.text = ri.loadLabel(pm)
                holder.b.check.isChecked = checked.contains(pkg)
                // 行与复选框都响应：子女直接点方块也生效（此前复选框吞掉点击无反应）
                val toggle = android.view.View.OnClickListener {
                    if (checked.contains(pkg)) checked.remove(pkg) else checked.add(pkg)
                    holder.b.check.isChecked = checked.contains(pkg)
                }
                holder.b.row.setOnClickListener(toggle)
                holder.b.check.setOnClickListener(toggle)
            }
        }
        listAdapter = adapter
        binding.appList.adapter = adapter
        applyFilter("")
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    class VH(val b: ItemAppPickerBinding) : RecyclerView.ViewHolder(b.root)
}
