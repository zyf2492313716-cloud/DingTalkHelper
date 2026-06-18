package com.dingtalk.helper.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dingtalk.helper.R
import com.dingtalk.helper.databinding.ActivityMainBinding
import com.dingtalk.helper.xposed.utils.Constants

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

        prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

        initViews()
        loadSettings()
        setupListeners()
    }

    private fun initViews() {
        title = getString(R.string.app_name)
        checkModuleStatus()
    }

    /**
     * 检查模块是否激活
     * 在 Xposed 环境中，HookEntry 类会被加载到目标进程中，
     * 但本进程（模块配置 App）中无法直接检测，
     * 这里使用文件标记方式判断
     */
    private fun checkModuleStatus() {
        val isActive = prefs.getBoolean("module_active", false)
        binding.tvModuleStatus.text = if (isActive) {
            getString(R.string.module_active)
        } else {
            getString(R.string.module_inactive)
        }
        binding.tvModuleStatus.setTextColor(
            if (isActive) getColor(R.color.teal_700) else getColor(R.color.purple_700)
        )
    }

    private fun loadSettings() {
        // 模块启用状态
        binding.switchEnabled.isChecked = prefs.getBoolean(Constants.KEY_ENABLED, false)

        // 虚拟位置
        binding.switchFakeLocation.isChecked = prefs.getBoolean(Constants.KEY_FAKE_LOCATION_ENABLED, false)
        binding.etLatitude.setText(prefs.getString(Constants.KEY_LATITUDE, Constants.DEFAULT_LATITUDE.toString()))
        binding.etLongitude.setText(prefs.getString(Constants.KEY_LONGITUDE, Constants.DEFAULT_LONGITUDE.toString()))
        binding.etAltitude.setText(prefs.getString(Constants.KEY_ALTITUDE, Constants.DEFAULT_ALTITUDE.toString()))

        // WiFi 伪造
        binding.switchFakeWifi.isChecked = prefs.getBoolean(Constants.KEY_FAKE_WIFI_ENABLED, false)
        binding.etWifiSsid.setText(prefs.getString(Constants.KEY_WIFI_SSID, ""))
        binding.etWifiBssid.setText(prefs.getString(Constants.KEY_WIFI_BSSID, ""))

        // 基站伪造
        binding.switchFakeCell.isChecked = prefs.getBoolean(Constants.KEY_FAKE_CELL_ENABLED, false)
        binding.etCellId.setText(prefs.getInt(Constants.KEY_CELL_ID, 0).toString())
        binding.etLac.setText(prefs.getInt(Constants.KEY_LAC, 0).toString())
        binding.etMcc.setText(prefs.getInt(Constants.KEY_MCC, Constants.DEFAULT_MCC).toString())
        binding.etMnc.setText(prefs.getInt(Constants.KEY_MNC, Constants.DEFAULT_MNC).toString())

        // 环境隐藏
        binding.switchHideRoot.isChecked = prefs.getBoolean(Constants.KEY_HIDE_ROOT, true)
        binding.switchHideXposed.isChecked = prefs.getBoolean(Constants.KEY_HIDE_XPOSED, true)
        binding.switchHideMockLocation.isChecked = prefs.getBoolean(Constants.KEY_HIDE_MOCK_LOCATION, true)
        binding.switchHideRiskControl.isChecked = prefs.getBoolean(Constants.KEY_HIDE_RISK_CONTROL, true)
    }

    private fun setupListeners() {
        binding.btnSave.setOnClickListener {
            if (validateInput()) {
                saveSettings()
                Toast.makeText(this, "设置已保存，重启钉钉后生效", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnReset.setOnClickListener {
            resetSettings()
            Toast.makeText(this, "设置已重置", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 验证输入
     */
    private fun validateInput(): Boolean {
        val lat = binding.etLatitude.text.toString().toDoubleOrNull()
        val lng = binding.etLongitude.text.toString().toDoubleOrNull()

        if (lat == null || lat < -90 || lat > 90) {
            binding.etLatitude.error = "纬度范围: -90 ~ 90"
            return false
        }
        if (lng == null || lng < -180 || lng > 180) {
            binding.etLongitude.error = "经度范围: -180 ~ 180"
            return false
        }

        // WiFi BSSID 格式验证（非空时）
        val bssid = binding.etWifiBssid.text.toString()
        if (bssid.isNotEmpty() && !bssid.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))) {
            binding.etWifiBssid.error = "格式: XX:XX:XX:XX:XX:XX"
            return false
        }

        return true
    }

    private fun saveSettings() {
        prefs.edit().apply {
            // 模块启用状态
            putBoolean(Constants.KEY_ENABLED, binding.switchEnabled.isChecked)

            // 虚拟位置
            putBoolean(Constants.KEY_FAKE_LOCATION_ENABLED, binding.switchFakeLocation.isChecked)
            putString(Constants.KEY_LATITUDE, binding.etLatitude.text.toString())
            putString(Constants.KEY_LONGITUDE, binding.etLongitude.text.toString())
            putString(Constants.KEY_ALTITUDE, binding.etAltitude.text.toString())

            // WiFi 伪造
            putBoolean(Constants.KEY_FAKE_WIFI_ENABLED, binding.switchFakeWifi.isChecked)
            putString(Constants.KEY_WIFI_SSID, binding.etWifiSsid.text.toString())
            putString(Constants.KEY_WIFI_BSSID, binding.etWifiBssid.text.toString())

            // 基站伪造
            putBoolean(Constants.KEY_FAKE_CELL_ENABLED, binding.switchFakeCell.isChecked)
            putInt(Constants.KEY_CELL_ID, binding.etCellId.text.toString().toIntOrNull() ?: 0)
            putInt(Constants.KEY_LAC, binding.etLac.text.toString().toIntOrNull() ?: 0)
            putInt(Constants.KEY_MCC, binding.etMcc.text.toString().toIntOrNull() ?: Constants.DEFAULT_MCC)
            putInt(Constants.KEY_MNC, binding.etMnc.text.toString().toIntOrNull() ?: Constants.DEFAULT_MNC)

            // 环境隐藏
            putBoolean(Constants.KEY_HIDE_ROOT, binding.switchHideRoot.isChecked)
            putBoolean(Constants.KEY_HIDE_XPOSED, binding.switchHideXposed.isChecked)
            putBoolean(Constants.KEY_HIDE_MOCK_LOCATION, binding.switchHideMockLocation.isChecked)
            putBoolean(Constants.KEY_HIDE_RISK_CONTROL, binding.switchHideRiskControl.isChecked)

            apply()
        }
    }

    private fun resetSettings() {
        prefs.edit().clear().apply()
        loadSettings()
    }
}
