package com.unredo.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import com.unredo.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.enableButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val enabled = isAccessibilityServiceEnabled()
        if (enabled) {
            binding.statusText.text = getString(R.string.status_enabled)
            binding.statusText.setTextColor(getColor(R.color.status_ok))
            binding.enableButton.text = getString(R.string.enable_accessibility_button)
        } else {
            binding.statusText.text = getString(R.string.status_disabled)
            binding.statusText.setTextColor(getColor(R.color.status_warn))
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager =
            getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }
}
