# DingTalkHelper 构建说明

## 快速开始

### 1. 解压项目
```bash
tar -xzf DingTalkHelper.tar.gz
cd DingTalkHelper
```

### 2. 环境要求
- JDK 17+
- Android SDK (API 34)
- Gradle 8.2+

### 3. 构建命令

```bash
# 调试版本
./gradlew assembleDebug

# 发布版本
./gradlew assembleRelease

# 输出位置
# app/build/outputs/apk/debug/app-debug.apk
# app/build/outputs/apk/release/app-release.apk
```

### 4. 如果没有 Android SDK

```bash
# 安装 Android SDK (Ubuntu/Debian)
sudo apt update
sudo apt install -y sdkmanager

# 或使用 Android Command Line Tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip
mkdir -p ~/android-sdk/cmdline-tools
mv cmdline-tools ~/android-sdk/cmdline-tools/latest

export ANDROID_HOME=~/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin

# 安装必要的 SDK 组件
yes | sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

### 5. Docker 构建 (推荐)

```dockerfile
FROM gradle:8.2-jdk17

# 安装 Android SDK
ENV ANDROID_HOME=/opt/android-sdk
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_HOME}/cmdline-tools && \
    mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools

RUN yes | sdkmanager --licenses && \
    sdkmanager "platforms;android-34" "build-tools;34.0.0"

WORKDIR /app
COPY . .

RUN chmod +x gradlew
RUN ./gradlew assembleRelease

# 输出 APK
CMD ["cp", "app/build/outputs/apk/release/app-release.apk", "/output/"]
```

```bash
# Docker 构建命令
docker build -t dingtalk-helper .
docker run -v $(pwd)/output:/output dingtalk-helper
```

### 6. GitHub Actions 自动构建

创建 `.github/workflows/build.yml`:

```yaml
name: Build APK

on:
  push:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
    - uses: actions/checkout@v3

    - name: Set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'

    - name: Setup Android SDK
      uses: android-actions/setup-android@v2

    - name: Grant execute permission for gradlew
      run: chmod +x gradlew

    - name: Build with Gradle
      run: ./gradlew assembleRelease

    - name: Upload APK
      uses: actions/upload-artifact@v3
      with:
        name: app-release
        path: app/build/outputs/apk/release/app-release.apk
```

## 项目结构

```
DingTalkHelper/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── java/com/dingtalk/helper/
│       │   ├── ui/MainActivity.kt
│       │   ├── utils/ConfigManager.kt
│       │   └── xposed/
│       │       ├── HookEntry.kt
│       │       ├── utils/
│       │       │   ├── Constants.kt
│       │       │   └── HookUtils.kt
│       │       └── hooks/
│       │           ├── LocationHooks.kt
│       │           ├── WifiHooks.kt
│       │           ├── CellHooks.kt
│       │           ├── SensorHooks.kt
│       │           ├── GnssHooks.kt
│       │           ├── EnvironmentHooks.kt
│       │           ├── AppHidingHooks.kt
│       │           ├── RomCompatibilityHooks.kt
│       │           └── RiskControlHooks.kt
│       ├── res/
│       └── AndroidManifest.xml
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/
```

## 输出文件

构建成功后，APK 文件位于:
```
app/build/outputs/apk/release/app-release.apk
```

## 使用方法

1. 将 APK 传输到已 ROOT 的 Android 设备
2. 安装 APK
3. 在 LSPosed 中启用模块
4. 作用域选择"钉钉"
5. 重启钉钉

## 注意事项

- 需要 Android 9.0+ (API 28+)
- 需要 Magisk + LSPosed 环境
- 首次构建需要下载依赖，可能较慢
