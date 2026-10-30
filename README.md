# 跑步模拟器 · Android

自己在地图上画路线 → 用系统 **Mock Location** 沿这条路线"跑"给你手机里的其他 App 看。
地图、路线编辑、界面全部在 WebView 里实现（自绘瓦片地图，**不需要任何地图 SDK 或 API Key**），
原生层只负责真实定位、模拟位置输出和前台服务。零第三方依赖。

![图标](app/src/main/res/mipmap-xxxhdpi/ic_launcher.png)

---

## 1. 它长什么样 / 怎么用

```
打开 App → 自动定位到你当前位置（蓝点）
        → 在地图上点几个点，画出要跑的路线（虚线 + 编号点，可拖动微调）
        → 选配速 → 开始模拟
```

- 路线点：点地图加点、按住点拖动、撤销、清空、闭合/不闭合
- 跑动：跑者沿路线按配速前进，轨迹实时画出，地图自动跟随
- 定位：真实 GPS 蓝点（带精度圈），`◎` 按钮一键回到自己位置
- 图层：标准路网 / 卫星图切换，双指缩放、拖拽平移、双击放大

## 2. 环境要求

| 项目 | 要求 |
|---|---|
| 编译 | Android Studio Koala (2024.1.1) 或更新；JDK 17+（Studio 自带） |
| Gradle / AGP | 8.7 / 8.5.2（已配好） |
| 运行 | Android 7.0 (API 24) 及以上，需要联网加载地图瓦片 |

如果 Sync 报 "requires a newer Android Studio"，把 `build.gradle` 里的
`com.android.tools.build:gradle:8.5.2` 换成 Studio 提示的版本即可。

> **国内网络**：`build.gradle` 已把阿里云 Maven 镜像放在 google() 前面。
> 如果 Android Studio 下载 SDK Platform 很慢，在 `Settings → Appearance & Behavior → System Settings → HTTP Proxy` 配代理。
> Gradle 发行包下载失败时，把 `gradle/wrapper/gradle-wrapper.properties` 里的地址换成
> `https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip`。

## 3. 编译出 APK

### 方式一：GitHub Actions（没装 Android Studio 也能出包）

把整个目录推到 GitHub → Actions 自动跑 `build-apk` → 进最近一次运行 → Artifacts → 下载 `RunSim-debug-apk` → 解压得到 `app-debug.apk`。

### 方式二：Android Studio

1. `File → Open` 选择本目录
2. 等 Gradle Sync 完成（首次要联网下载 AGP 与 Gradle）
3. 手机开 USB 调试直接 `Run ▶`，或 `Build → Build Bundle(s)/APK(s) → Build APK(s)`
4. APK 路径：`app/build/outputs/apk/debug/app-debug.apk`

> **关于 Gradle wrapper**：工程只带 `gradle-wrapper.properties`（没有二进制的 `gradle-wrapper.jar`）。
> Sync 报 *GradleWrapperMain* 时：先点 Studio 提示的 **Fix / OK** 让它自动补；
> 补不上就在 Studio 里新建一个空项目（包名 `com.runlab.runsim`、Java、minSdk 24），
> 把本工程 `app/src/main/` 覆盖进去，用新项目自带的 wrapper 编译。

## 4. 手机上必须做的设置

1. **开发者选项**：设置 → 关于手机 → 连续点「版本号」7 次
2. **选择模拟位置信息应用**：开发者选项 → 找到该项 → 选中「跑步模拟器」
   （部分机型每次重启需要重选一次）
3. 打开 App，允许**定位权限**（Android 13+ 顺便允许通知，否则看不到常驻通知）
4. 点「开始模拟」，通知栏出现常驻通知即生效；打开别的 App 看定位

## 5. 目录结构

```
RunSimAndroid/
├── build.gradle / settings.gradle / gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── .github/workflows/build.yml      自动打包 APK
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/app.html          ★ 全部界面：自绘瓦片地图 + 路线编辑 + 控件
        ├── java/com/runlab/runsim/
        │   ├── MainActivity.java    WebView 外壳 + JS 桥 + 真实定位 + 权限
        │   ├── MockRunService.java  前台服务：每秒写入模拟位置
        │   └── SimState.java        路线几何 / 里程插值 / 统计数据
        └── res/                    图标、主题、只含一个 WebView 的布局
```

## 6. 地图与坐标

- 瓦片：高德地图标准图 / 卫星图（`webrd0x.is.autonavi.com`、`webst0x.is.autonavi.com`），直接按 XYZ 拉图，不需要 Key。
- **坐标转换**：高德瓦片是 GCJ-02（火星坐标），手机 GPS 是 WGS-84。地图显示前统一做 WGS→GCJ，
  点地图取点再做 GCJ→WGS 存回，所以蓝点和街道是对齐的，导出/模拟出去的仍然是标准 WGS-84。
- 想换瓦片源：改 `app.html` 里的 `tileUrl()` 即可（天地图、腾讯、OSM 都是 XYZ 结构，改个地址就行）。

## 7. 常见问题

**Q：地图一片空白 / 灰格子**
手机没网，或瓦片域名被拦。换网络试试；也可以把 `tileUrl()` 换成别的源。

**Q：点开始后弹"没有模拟位置权限"**
没在开发者选项里选中本应用，或系统把它重置了。按提示跳过去重选。

**Q：别的 App 还是显示真实位置**
先杀掉那个 App 再打开（定位缓存）；若仍不行，多半是它用融合定位或有反模拟检测。

**Q：蓝点不出现**
没给定位权限，或室内收不到 GPS。点 `◎` 会再请求一次。

**Q：怎么彻底停掉**
通知栏点「停止模拟」；长期不用可去开发者选项取消选择模拟位置应用。

## 8. 说明

模拟位置只对本机生效，停止后立即恢复真实定位。部分运动、打卡类 App 会用
`Location.isMock()` / 加速度计等做反作弊检测，检测到会拒绝记录；本项目不包含任何绕过检测的逻辑，
使用场景请自行判断并遵守对应服务的条款。
