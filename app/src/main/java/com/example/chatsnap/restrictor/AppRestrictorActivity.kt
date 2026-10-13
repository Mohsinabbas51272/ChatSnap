package com.example.chatsnap.restrictor

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatsnap.databinding.ActivityAppRestrictorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppRestrictorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppRestrictorBinding
    private lateinit var adapter: RestrictedAppAdapter
    private val allApps = mutableListOf<RestrictedAppModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppRestrictorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupViews()
        loadInstalledApps()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionsStatus()
        updateRestrictedCount()
    }

    private fun setupViews() {
        // Master Switch
        binding.switchMasterEnable.isChecked = AppRestrictionManager.isFeatureEnabled(this)
        binding.switchMasterEnable.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!AppRestrictionManager.hasUsageStatsPermission(this) ||
                    !AppRestrictionManager.hasOverlayPermission(this)
                ) {
                    Toast.makeText(
                        this,
                        "Please grant required permissions to activate protection",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            AppRestrictionManager.setFeatureEnabled(this, isChecked)
        }

        // Grant Usage Button
        binding.btnGrantUsage.setOnClickListener {
            AppRestrictionManager.openUsageStatsSettings(this)
        }

        // Grant Overlay Button
        binding.btnGrantOverlay.setOnClickListener {
            AppRestrictionManager.openOverlaySettings(this)
        }

        // RecyclerView
        adapter = RestrictedAppAdapter(emptyList()) { appModel, isRestricted ->
            AppRestrictionManager.setRestricted(this, appModel.packageName, isRestricted)
            updateRestrictedCount()
        }
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter

        // Search text watcher
        binding.etSearchApp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun updatePermissionsStatus() {
        val hasUsage = AppRestrictionManager.hasUsageStatsPermission(this)
        val hasOverlay = AppRestrictionManager.hasOverlayPermission(this)

        if (hasUsage) {
            binding.tvUsageStatus.text = "1. Usage Access: Granted ✓"
            binding.tvUsageStatus.setTextColor(Color.parseColor("#2E7D32"))
            binding.btnGrantUsage.isEnabled = false
            binding.btnGrantUsage.text = "Granted"
        } else {
            binding.tvUsageStatus.text = "1. Usage Access: Required"
            binding.tvUsageStatus.setTextColor(Color.parseColor("#C62828"))
            binding.btnGrantUsage.isEnabled = true
            binding.btnGrantUsage.text = "Grant"
        }

        if (hasOverlay) {
            binding.tvOverlayStatus.text = "2. Display Over Other Apps: Granted ✓"
            binding.tvOverlayStatus.setTextColor(Color.parseColor("#2E7D32"))
            binding.btnGrantOverlay.isEnabled = false
            binding.btnGrantOverlay.text = "Granted"
        } else {
            binding.tvOverlayStatus.text = "2. Display Over Other Apps: Required"
            binding.tvOverlayStatus.setTextColor(Color.parseColor("#C62828"))
            binding.btnGrantOverlay.isEnabled = true
            binding.btnGrantOverlay.text = "Grant"
        }

        if (hasUsage && hasOverlay) {
            binding.cvPermissions.visibility = View.GONE
        } else {
            binding.cvPermissions.visibility = View.VISIBLE
        }
    }

    private fun updateRestrictedCount() {
        val restrictedSet = AppRestrictionManager.getRestrictedPackages(this)
        binding.tvRestrictedCount.text = "${restrictedSet.size} Restricted"
    }

    private fun loadInstalledApps() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
            val restrictedSet = AppRestrictionManager.getRestrictedPackages(this@AppRestrictorActivity)

            val list = mutableListOf<RestrictedAppModel>()
            val processedPackages = mutableSetOf<String>()

            for (resolveInfo in resolveInfos) {
                val pkgName = resolveInfo.activityInfo.packageName
                if (pkgName == packageName || processedPackages.contains(pkgName)) {
                    continue
                }
                processedPackages.add(pkgName)

                val appName = resolveInfo.loadLabel(pm).toString()
                val icon = resolveInfo.loadIcon(pm)
                val isRestricted = restrictedSet.contains(pkgName)

                list.add(RestrictedAppModel(appName, pkgName, icon, isRestricted))
            }

            // Sort: Restricted apps first, then alphabetically
            list.sortWith(compareByDescending<RestrictedAppModel> { it.isRestricted }.thenBy { it.appName.lowercase() })

            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(list)
                adapter.updateData(allApps)
                binding.progressBar.visibility = View.GONE
                updateRestrictedCount()
            }
        }
    }
}
