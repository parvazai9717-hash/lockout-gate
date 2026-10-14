package com.lockout.gate

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lockout.gate.state.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(
    val appName: String,
    val packageName: String,
    val icon: Drawable,
)

class AppPickerActivity : AppCompatActivity() {

    private lateinit var store: SessionStore
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private var appList = listOf<AppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        store = SessionStore(this)
        recyclerView = findViewById(R.id.appsRecyclerView)
        progressBar = findViewById(R.id.appsProgressBar)

        val bgImageView = findViewById<ImageView>(R.id.customBackgroundImageView)
        val scrimView = findViewById<View>(R.id.backgroundScrimView)
        if (bgImageView != null) {
            BackgroundHelper.applyCustomBackground(this, bgImageView, scrimView)
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val intent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = packageManager.queryIntentActivities(intent, 0)
                resolveInfos.mapNotNull { resolveInfo ->
                    val pkg = resolveInfo.activityInfo.packageName
                    if (pkg == packageName) null // Skip LockoutGate itself
                    else {
                        val name = resolveInfo.loadLabel(packageManager).toString()
                        val icon = resolveInfo.loadIcon(packageManager)
                        AppInfo(name, pkg, icon)
                    }
                }.sortedBy { it.appName.lowercase() }
            }
            appList = apps
            progressBar.visibility = View.GONE
            recyclerView.adapter = AppAdapter()
        }
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

        inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val iconView: ImageView = itemView.findViewById(R.id.appIconImageView)
            val nameView: TextView = itemView.findViewById(R.id.appNameTextView)
            val packageView: TextView = itemView.findViewById(R.id.appPackageTextView)
            val checkBox: CheckBox = itemView.findViewById(R.id.appCheckBox)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_block, parent, false)
            return AppViewHolder(view)
        }

        override fun getItemCount(): Int = appList.size

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val app = appList[position]
            val pkg = app.packageName

            holder.nameView.text = app.appName
            holder.packageView.text = pkg
            holder.iconView.setImageDrawable(app.icon)

            val isBlocked = pkg in store.getAllBlockedPackages()

            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = isBlocked
            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                val set = store.standardBlockedPackages.toMutableSet()
                if (isChecked) set.add(pkg) else set.remove(pkg)
                store.standardBlockedPackages = set
            }
        }
    }
}
