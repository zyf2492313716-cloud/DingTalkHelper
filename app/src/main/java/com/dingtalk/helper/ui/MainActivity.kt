package com.dingtalk.helper.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dingtalk.helper.R
import com.dingtalk.helper.databinding.ActivityMainBinding
import com.dingtalk.helper.utils.ConfigManager

/**
 * 主界面
 * 提供虚拟定位配置功能
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("dingtalk_helper_prefs", Context.MODE_PRIVATE)

        initViews()
        loadSettings()
        setupListeners()
    }

    /**
     * 初始化视图
     */
    private fun initViews() {
        title = getString(R.string.app_name)

        // 检查模块是否激活
        checkModuleStatus()
    }

    /**
     * 检查模块状态
     */
    private fun checkModuleStatus() {
        val isActive = isModuleActive()
        binding.tvModuleStatus.text = if (isActive) {
            getString(R.string.module_active)
        } else {
            getString(R.string.module_inactive)
        }
        binding.tvModuleStatus.setTextColor(
            if (isActive) getColor(R.color.teal_700) else getColor(R.color.purple_700)
        )
    }

    /**
     * 检查 Xposed 模块是否激活
     */
    private fun isModuleActive(): Boolean {
        return try {
            Class.forName("com.dingtalk.helper.xposed.HookEntry")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    /**
     * 加载设置
     */
    private fun loadSettings() {
        // 模块启用状态
        binding.switchEnabled.isChecked = prefs.getBoolean("enabled", false)

        // 虚拟位置
        binding.switchFakeLocation.isChecked = prefs.getBoolean("fake_location_enabled", false)
        binding.etLatitude.setText(prefs.getString("latitude", "39.9042"))
        binding.etLongitude.setText(prefs.getString("longitude", "116.4074"))
        binding.etAltitude.setText(prefs.getString("altitude", "0"))

        // WiFi 伪造
        binding.switchFakeWifi.isChecked = prefs.getBoolean("fake_wifi_enabled", false)
        binding.etWifiSsid.setText(prefs.getString("wifi_ssid", ""))
        binding.etWifiBssid.setText(prefs.getString("wifi_bssid", ""))

        // 基站伪造
        binding.switchFakeCell.isChecked = prefs.getBoolean("fake_cell_enabled", false)
        binding.etCellId.setText(prefs.getInt("cell_id", 0).toString())
        binding.etLac.setText(prefs.getInt("lac", 0).toString())
        binding.etMcc.setText(prefs.getInt("mcc", 460).toString())
        binding.etMnc.setText(prefs.getInt("mnc", 0).toString())

        // 环境隐藏
        binding.switchHideRoot.isChecked = prefs.getBoolean("hide_root", true)
        binding.switchHideXposed.isChecked = prefs.getBoolean("hide_xposed", true)
        binding.switchHideMockLocation.isChecked = prefs.getBoolean("hide_mock_location", true)
        binding.switchHideRiskControl.isChecked = prefs.getBoolean("hide_risk_control", true)
    }

    /**
     * 设置监听器
     */
    private fun setupListeners() {
        // 保存按钮
        binding.btnSave.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "设置已保存，重启钉钉后生效", Toast.LENGTH_LONG).show()
        }

        // 重置按钮
        binding.btnReset.setOnClickListener {
            resetSettings()
            Toast.makeText(this, "设置已重置", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 保存设置
     */
    private fun saveSettings() {
        val editor = prefs.edit()

        // 模块启用状态
        editor.putBoolean("enabled", binding.switchEnabled.isChecked)

        // 虚拟位置
        editor.putBoolean("fake_location_enabled", binding.switchFakeLocation.isChecked)
        editor.putString("latitude", binding.etLatitude.text.toString())
        editor.putString("longitude", binding.etLongitude.text.toString())
        editor.putString("altitude", binding.etAltitude.text.toString())

        // WiFi 伪造
        editor.putBoolean("fake_wifi_enabled", binding.switchFakeWifi.isChecked)
        editor.putString("wifi_ssid", binding.etWifiSsid.text.toString())
        editor.putString("wifi_bssid", binding.etWifiBssid.text.toString())

        // 基站伪造
        editor.putBoolean("fake_cell_enabled", binding.switchFakeCell.isChecked)
        editor.putInt("cell_id", binding.etCellId.text.toString().toIntOrNull() ?: 0)
        editor.putInt("lac", binding.etLac.text.toString().toIntOrNull() ?: 0)
        editor.putInt("mcc", binding.etMcc.text.toString().toIntOrNull() ?: 460)
        editor.putInt("mnc", binding.etMnc.text.toString().toIntOrNull() ?: 0)

        // 环境隐藏
        editor.putBoolean("hide_root", binding.switchHideRoot.isChecked)
        editor.putBoolean("hide_xposed", binding.switchHideXposed.isChecked)
        editor.putBoolean("hide_mock_location", binding.switchHideMockLocation.isChecked)
        editor.putBoolean("hide_risk_control", binding.switchHideRiskControl.isChecked)

        editor.apply()
    }

    /**
     * 重置设置
     */
    private fun resetSettings() {
        val editor = prefs.edit()
        editor.clear()
        editor.apply()

        // 重新加载默认值
        loadSettings()
    }
}