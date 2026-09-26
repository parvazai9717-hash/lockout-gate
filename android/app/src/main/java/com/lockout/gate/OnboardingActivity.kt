package com.lockout.gate

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.lockout.gate.state.SessionStore

/**
 * Step-by-step interactive setup wizard for first-time users.
 * Guides click-by-click through app capabilities, Accessibility permission,
 * Usage Statistics access, blocklist configuration, and final launch.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var store: SessionStore
    private lateinit var viewPager: ViewPager2
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var stepBadge: TextView
    private lateinit var skipButton: MaterialButton
    private lateinit var backButton: MaterialButton
    private lateinit var nextButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        store = SessionStore(this)

        viewPager = findViewById(R.id.onboardingViewPager)
        progressBar = findViewById(R.id.onboardingProgressBar)
        stepBadge = findViewById(R.id.onboardingStepBadge)
        skipButton = findViewById(R.id.onboardingSkipButton)
        backButton = findViewById(R.id.onboardingBackButton)
        nextButton = findViewById(R.id.onboardingNextButton)

        viewPager.adapter = OnboardingAdapter()
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateNavigationState(position)
            }
        })

        skipButton.setOnClickListener { finishOnboarding() }
        backButton.setOnClickListener {
            if (viewPager.currentItem > 0) {
                viewPager.currentItem -= 1
            }
        }
        nextButton.setOnClickListener {
            if (viewPager.currentItem < TOTAL_STEPS - 1) {
                viewPager.currentItem += 1
            } else {
                finishOnboarding()
            }
        }

        val initialPosition = savedInstanceState?.getInt(KEY_CURRENT_STEP, 0) ?: 0
        viewPager.setCurrentItem(initialPosition, false)
        updateNavigationState(initialPosition)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_STEP, viewPager.currentItem)
    }

    override fun onResume() {
        super.onResume()
        // Refresh permission status badges across all slides when returning from system settings
        viewPager.adapter?.notifyDataSetChanged()
    }

    private fun updateNavigationState(position: Int) {
        val step = position + 1
        val progress = (step * 100) / TOTAL_STEPS
        progressBar.setProgressCompat(progress, true)
        stepBadge.text = "STEP $step OF $TOTAL_STEPS"

        backButton.visibility = if (position > 0) View.VISIBLE else View.INVISIBLE
        nextButton.text = if (position == TOTAL_STEPS - 1) "Get Started 🎉" else "Next Step →"
        skipButton.visibility = if (position == TOTAL_STEPS - 1) View.GONE else View.VISIBLE
    }

    private fun finishOnboarding() {
        store.isFirstRun = false
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }

    private inner class OnboardingAdapter : RecyclerView.Adapter<OnboardingViewHolder>() {
        override fun getItemCount(): Int = TOTAL_STEPS

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OnboardingViewHolder {
            val layoutId = when (viewType) {
                0 -> R.layout.item_onboarding_welcome
                1 -> R.layout.item_onboarding_accessibility
                2 -> R.layout.item_onboarding_usage
                3 -> R.layout.item_onboarding_apps
                else -> R.layout.item_onboarding_finish
            }
            val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
            return OnboardingViewHolder(view, viewType)
        }

        override fun onBindViewHolder(holder: OnboardingViewHolder, position: Int) {
            holder.bind()
        }
    }

    private inner class OnboardingViewHolder(val view: View, val viewType: Int) : RecyclerView.ViewHolder(view) {
        fun bind() {
            when (viewType) {
                1 -> bindAccessibilitySlide()
                2 -> bindUsageSlide()
                3 -> bindAppsSlide()
                4 -> bindFinishSlide()
            }
        }

        private fun isAccessibilityServiceEnabled(): Boolean {
            val expected = ComponentName(this@OnboardingActivity, AppBlockAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == expected
            }
        }

        private fun bindAccessibilitySlide() {
            val isEnabled = isAccessibilityServiceEnabled()
            val badge = view.findViewById<TextView>(R.id.onboardingAccessibilityBadge)
            val grantBtn = view.findViewById<Button>(R.id.onboardingGrantAccessibilityButton)

            if (isEnabled) {
                badge?.text = "Status: Granted ✅"
                badge?.setTextColor(getColor(R.color.status_unlocked))
                badge?.setBackgroundResource(R.drawable.bg_status_badge_unlocked)
                grantBtn?.text = "Service Enabled ✓"
                grantBtn?.isEnabled = false
            } else {
                badge?.text = "Status: Not Granted ❌"
                badge?.setTextColor(getColor(android.R.color.holo_red_dark))
                grantBtn?.text = "Grant Accessibility Permission"
                grantBtn?.isEnabled = true
                grantBtn?.setOnClickListener {
                    // Google Play requires this disclosure before the user enables the service.
                    AlertDialog.Builder(this@OnboardingActivity)
                        .setTitle(R.string.accessibility_disclosure_title)
                        .setMessage(R.string.accessibility_disclosure_message)
                        .setPositiveButton(R.string.accessibility_disclosure_agree) { _, _ ->
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        .setNegativeButton(R.string.accessibility_disclosure_decline, null)
                        .show()
                }
            }
        }

        private fun bindUsageSlide() {
            val hasPermission = UsageTracker.hasUsageAccess(this@OnboardingActivity)
            val badge = view.findViewById<TextView>(R.id.onboardingUsageBadge)
            val grantBtn = view.findViewById<Button>(R.id.onboardingGrantUsageButton)

            if (hasPermission) {
                badge?.text = "Status: Granted ✅"
                badge?.setTextColor(getColor(R.color.status_unlocked))
                badge?.setBackgroundResource(R.drawable.bg_status_badge_unlocked)
                grantBtn?.text = "Usage Tracking Active ✓"
                grantBtn?.isEnabled = false
            } else {
                badge?.text = "Status: Not Granted ❌"
                badge?.setTextColor(getColor(android.R.color.holo_red_dark))
                grantBtn?.text = "Grant Usage Access Permission"
                grantBtn?.isEnabled = true
                grantBtn?.setOnClickListener {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }
        }

        private fun bindAppsSlide() {
            view.findViewById<Button>(R.id.onboardingManageAppsButton)?.setOnClickListener {
                startActivity(Intent(this@OnboardingActivity, AppPickerActivity::class.java))
            }
        }

        private fun bindFinishSlide() {
            view.findViewById<Button>(R.id.onboardingFinishButton)?.setOnClickListener {
                finishOnboarding()
            }
        }
    }

    companion object {
        private const val TOTAL_STEPS = 5
        private const val KEY_CURRENT_STEP = "onboarding_current_step"
    }
}
