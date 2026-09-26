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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
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
    private lateinit var modeDescriptionText: TextView
    private var isStrictMode = false
    private var appList = listOf<AppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        store = SessionStore(this)
        recyclerView = findViewById(R.id.appsRecyclerView)
        progressBar = findViewById(R.id.appsProgressBar)
        modeDescriptionText = findViewById(R.id.modeDescriptionText)

        val bgImageView = findViewById<ImageView>(R.id.customBackgroundImageView)
        val scrimView = findViewById<View>(R.id.backgroundScrimView)
        if (bgImageView != null) {
            BackgroundHelper.applyCustomBackground(this, bgImageView, scrimView)
        }

        recyclerView.layoutManager = LinearLayoutManager(this)

        val toggleGroup = findViewById<MaterialButtonToggleGroup>(R.id.modeToggleGroup)
        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isStrictMode = (checkedId == R.id.btnStrictArea)
                updateModeDescription()
                recyclerView.adapter?.notifyDataSetChanged()
            }
        }

        updateModeDescription()
        loadInstalledApps()
    }

    private fun updateModeDescription() {
        if (isStrictMode) {
            modeDescriptionText.text = getString(R.string.mode_desc_strict)
            modeDescriptionText.setTextColor(getColor(R.color.status_locked))
        } else {
            modeDescriptionText.text = getString(R.string.mode_desc_standard)
            modeDescriptionText.setTextColor(getColor(R.color.text_secondary_light))
        }
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
            val badgeView: TextView = itemView.findViewById(R.id.permanentBadge)
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

            val isDefaultEntertainment = pkg in Config.ENTERTAINMENT_PACKAGES
            val isStrictLocked = pkg in store.strictBlockedPackages
            val isStandardBlocked = pkg in store.standardBlockedPackages

            // Rows are recycled between modes, so clear both listeners first.
            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.setOnClickListener(null)

            if (isDefaultEntertainment || isStrictLocked) {
                holder.checkBox.isChecked = true
                holder.checkBox.isEnabled = false
                holder.badgeView.visibility = View.VISIBLE
            } else if (!isStrictMode) {
                holder.badgeView.visibility = View.GONE
                holder.checkBox.isChecked = isStandardBlocked
                holder.checkBox.isEnabled = true
                holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                    val set = store.standardBlockedPackages.toMutableSet()
                    if (isChecked) set.add(pkg) else set.remove(pkg)
                    store.standardBlockedPackages = set
                }
            } else {
                // Strict tab only adds apps to the permanent list; standard
                // blocks are managed on the Standard tab.
                holder.badgeView.visibility = View.GONE
                holder.checkBox.isChecked = false
                holder.checkBox.isEnabled = true
                holder.checkBox.setOnClickListener {
                    if (!holder.checkBox.isChecked) return@setOnClickListener
                    promptStrictConfirmation(app) {
                        store.strictBlockedPackages = store.strictBlockedPackages + pkg
                        store.standardBlockedPackages = store.standardBlockedPackages - pkg
                        val pos = holder.adapterPosition
                        if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
                    }
                }
            }
        }
    }

    private fun promptStrictConfirmation(app: AppInfo, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.strict_confirm_title)
            .setMessage(getString(R.string.strict_confirm_message, app.appName))
            .setPositiveButton(R.string.strict_confirm_yes) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                recyclerView.adapter?.notifyDataSetChanged()
            }
            .setCancelable(false)
            .show()
    }
}
