# 钉钉虚拟定位助手

一个基于 LSPosed 框架的钉钉虚拟定位 Xposed 模块。

## 功能特性

### 1. GPS 位置伪造
- 自定义经纬度坐标
- 自定义海拔高度
- 隐藏模拟位置标记
- 阻止 GNSS 状态回调

### 2. WiFi 信息伪造
- 自定义 WiFi SSID
- 自定义 WiFi BSSID (MAC地址)
- 伪造 WiFi 扫描结果

### 3. 基站信息伪造
- 自定义 Cell ID
- 自定义 LAC (位置区码)
- 自定义 MCC/MNC
- 支持 GSM/LTE 基站

### 4. 风控数据拦截
- 拦截 lbswua 数据上报
- 拦截 ddsec 数据上报
- 篡改风控检测结果
- 绕过签名验证

### 5. 环境隐藏
- 隐藏 ROOT 环境 (su 文件、Magisk)
- 隐藏 Xposed/LSPosed 框架
- 隐藏开发者选项
- 隐藏分身应用检测

## 环境要求

- Android 9.0+ (API 28+)
- 已 ROOT 的设备
- Magisk 24.0+
- LSPosed (Zygisk 版本)

## 安装步骤

### 1. 安装 Magisk

```bash
# 下载 Magisk
# https://github.com/topjohnwu/Magisk/releases

# 安装 Magisk
# 1. 解压 boot.img
# 2. 使用 Magisk 修补
# 3. 刷入修补后的 boot.img
```

### 2. 安装 LSPosed

```bash
# 下载 LSPosed (Zygisk 版本)
# https://github.com/LSPosed/LSPosed/releases

# 在 Magisk 中安装模块
# 1. 打开 Magisk
# 2. 模块 -> 从本地安装
# 3. 选择 LSPosed zip 文件
# 4. 重启设备
```

### 3. 构建并安装模块

```bash
# 克隆项目
git clone <repository-url>
cd DingTalkHelper

# 构建 APK
./gradlew assembleRelease

# 安装 APK
adb install app/build/outputs/apk/release/app-release.apk
```

### 4. 启用模块

1. 打开 LSPosed 管理器
2. 模块 -> 找到"钉钉助手"
3. 勾选启用
4. 作用域选择"钉钉"
5. 重启钉钉

## 使用说明

### 配置虚拟位置

1. 打开"钉钉助手"应用
2. 启用模块
3. 启用"虚拟位置"
4. 输入目标经纬度坐标
5. 点击保存
6. 重启钉钉

### 获取目标坐标

可以使用以下方式获取目标位置的经纬度：

1. **Google Maps**
   - 打开 https://maps.google.com
   - 右键点击目标位置
   - 查看坐标信息

2. **高德地图**
   - 打开 https://www.amap.com
   - 右键点击目标位置
   - 查看坐标信息

3. **坐标拾取器**
   - https://lbs.amap.com/tools/picker

### 配置 WiFi 信息（推荐）

为了提高隐蔽性，建议同时配置目标位置的 WiFi 信息：

1. 在目标位置获取 WiFi 信息
   - SSID: WiFi 名称
   - BSSID: WiFi MAC 地址

2. 在模块中配置 WiFi 信息
3. 启用 WiFi 伪造

### 配置基站信息（可选）

如果需要更高的隐蔽性，可以配置基站信息：

1. 在目标位置获取基站信息
   - Cell ID
   - LAC
   - MCC/MNC

2. 在模块中配置基站信息
3. 启用基站伪造

## 注意事项

### 风险提示

1. **技术风险**
   - 钉钉可能更新检测机制
   - WiFi/基站/GPS 不一致可能被标记
   - 虚拟定位软件可能泄露隐私

2. **职业风险**
   - 虚拟定位打卡可能被视为考勤欺诈
   - 严重者可能被解除劳动合同

3. **法律风险**
   - 制作/销售虚拟定位工具可能触犯法律
   - 《刑法》第 285 条、第 286 条

### 使用建议

1. **保守使用**
   - 不要频繁使用
   - 不要在重要场合使用

2. **保持一致性**
   - GPS、WiFi、基站信息保持一致
   - 避免"矛盾打卡"

3. **及时更新**
   - 关注钉钉更新
   - 及时更新模块

## 开发说明

### 项目结构

```
DingTalkHelper/
├── app/
│   └── src/main/
│       ├── java/com/dingtalk/helper/
│       │   ├── ui/                    # 界面
│       │   │   └── MainActivity.kt
│       │   ├── utils/                 # 工具类
│       │   │   └── ConfigManager.kt
│       │   └── xposed/               # Xposed 模块
│       │       ├── HookEntry.kt      # 模块入口
│       │       └── hooks/            # Hook 实现
│       │           ├── LocationHooks.kt    # GPS 伪造
│       │           ├── WifiHooks.kt        # WiFi 伪造
│       │           ├── CellHooks.kt        # 基站伪造
│       │           ├── SensorHooks.kt      # 传感器伪造
│       │           ├── RiskControlHooks.kt # 风控拦截
│       │           └── EnvironmentHooks.kt # 环境隐藏
│       ├── res/                       # 资源文件
│       └── AndroidManifest.xml
├── build.gradle.kts
└── README.md
```

### 核心类说明

1. **HookEntry.kt**
   - 模块入口
   - 加载所有 Hook 处理器
   - 初始化模块上下文

2. **LocationHooks.kt**
   - Hook LocationManagerService
   - 伪造 GPS 坐标
   - 隐藏模拟位置标记

3. **RiskControlHooks.kt**
   - 拦截 lbswua/ddsec 数据
   - 篡改风控检测结果
   - 绕过签名验证

4. **EnvironmentHooks.kt**
   - 隐藏 ROOT 环境
   - 隐藏 Xposed 框架
   - 隐藏开发者选项

### 添加新功能

1. 创建新的 Hook 类
2. 实现 HookEntry.HookHandler 接口
3. 在 HookEntry 中注册

```kotlin
class NewFeatureHooks : HookEntry.HookHandler {
    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 实现 Hook 逻辑
    }
}
```

## 参考项目

- [FuckLocation](https://github.com/Mikotwa/FuckLocation)
- [XposedFakeLocation](https://github.com/noobexon1/XposedFakeLocation)
- [Portal](https://github.com/ella8192/Portal)

## 免责声明

本项目仅供学习和研究使用，不承担任何因使用本项目产生的法律责任。

## 许可证

MIT License