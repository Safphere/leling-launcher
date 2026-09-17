# 乐龄桌面 (LeLing Launcher)

> 🌐 **项目主页（在线演示）**：https://safphere.github.io/leling-launcher/ —— 交互式体验「广告轰炸机 vs 乐龄桌面」

一款为老年人设计的 Android 防广告桌面（Launcher）。大字大图标、无广告无信息流、
子女远程可管、紧急求助直达——把智能手机变回老人能用、敢用的工具。

## 🛡 银发认证 · 一个提议

> **「未成年人有防沉迷，老年人也该有银发认证。」**
> **一款应用只要敢对老人弹广告，就不配出现在爸妈的手机上。**

银发认证是一个开放的社会倡议：不是机构、不发证书、不收费。
对老人**零广告**、不耍假“×”不吓唬人、大字少步骤不设坑——
看到 🛡，就知道这款应用对老人是干净的。

📖 倡议全文与参与方式：[docs/银发认证倡议.md](docs/银发认证倡议.md) ｜
🖥 交互演示（广告地狱 → 一键清场）：https://safphere.github.io/leling-launcher/

> 当前版本：v3.4.2（versionCode 37）· minSdk 26（Android 8.0）· targetSdk 35 · Kotlin

## 功能特性

### 桌面
- **三页结构**：联系人大头像（点照片直接拨号）· 生活页（大时钟/农历/天气/月历）· 常用应用
- **便捷页**：手电筒（整卡开关，退出自动关灯）· 家庭地址大字卡（走失求助 + geo 一键导航）
- **自定义壁纸**：相册选图，自动叠加护眼白纱罩保证可读性
- **底部快捷栏**：可从应用页拖拽更换，固定保留一键重启
- **应用搜索**：应用页内置搜索，边输边筛

### 安全防护
- **防广告桌面**：零广告 SDK、零信息流、零推送通道（开源可审计）
- **白名单模式**：桌面只显示子女勾选的应用（默认显示全部，可切换）
- **SOS 紧急求助**：按住 3 秒 → 自动向紧急联系人发带定位的短信 + 直接呼叫
- **SIM 卡检测**：拔卡/接触不良自动发现，大字提醒 + 一键重启
- **流量智能管理**：发短信查流量，余额不足自动关闭移动数据，月结日自动恢复
- **防沉迷**：单应用每日/单次时长限制、禁用时段

### 健康与关怀
- **健康提醒**：定时提醒（吃药/量血压）+ 天气关怀提醒（高温/低温/雨雪）；全屏大字 + 语音播报
- **夜间勿扰**：勿扰窗口内提醒自动推迟到窗口结束，不吵醒老人
- **提醒确认记录**：点「知道了」记录确认，供远程状态查询
- **AI 语音问答**：按住说话（兼容 OpenAI/Anthropic 接口，支持本地技能兜底）
- **天气自动定位**：可选设备定位获取所在地天气（默认关闭）

### 远程能力
- **远程状态查询（零服务器）**：子女发短信 `LLZT`，自动回复电量/SIM/流量/今日提醒确认情况
  （仅回复通讯录联系人，陌生人静默忽略，设置内可关闭）
- **Device Owner 模式**：静默一键重启、防沉迷应用隐藏等系统能力

## 构建

```bash
git clone <repo-url>
cd <project>
./gradlew assembleDebug     # Debug 构建（无需签名配置）
```

### Release 签名（可选）

在项目根目录创建 `keystore.properties`（**不要提交**，已在 .gitignore）：

```properties
storeFile=keystore/your-release.jks
storePassword=你的密码
keyAlias=你的别名
keyPassword=你的密码
```

然后：

```bash
./gradlew assembleRelease
```

没有 `keystore.properties` 时，release 构建自动回退 debug 签名（仅供本地调试）。

## Device Owner 激活（可选，解锁静默重启等能力）

在**全新恢复出厂、未登录任何账号**的设备上执行：

```bash
adb shell dpm set-device-owner com.safphere.launcher/.admin.ElderDeviceAdminReceiver
```

## 子女设置入口

桌面底部页面指示点 **连点 5 次**（3 秒内）→ 输入 PIN（默认 1234，请立即修改）。

## 远程状态查询

用子女手机（号码需在父母手机通讯录中）向父母手机发送短信：

```
LLZT
```

父母手机将自动回复：电量、SIM 卡状态、流量剩余、今日提醒确认情况。
可在子女设置中关闭该功能。

## 目录结构

```
app/src/main/java/com/safphere/launcher/
├── home/        # 桌面主界面（三页 + 便捷页 + 壁纸 + 搜索）
├── reminder/    # 健康提醒（定时/天气/勿扰/确认）
├── alert/       # 全屏警报（SIM/地震/洪水/健康提醒共用）
├── call/        # 拨号与 SOS
├── contacts/    # 联系人编辑
├── sms/         # 短信编写与远程状态查询
├── flow/        # 流量查询与管理
├── sim/         # SIM 检测与开机/每日自检
├── guard/       # 防沉迷
├── agent/       # 无障碍服务与 AI 悬浮球
├── ai/          # AI 语音问答
├── weather/     # 天气（Open-Meteo + 自动定位）
├── settings/    # 子女设置（PIN 保护）
├── perm/        # 权限自检
├── reboot/      # 一键重启（三级降级）
├── a11y/        # 无障碍服务（电源菜单重启降级）
├── admin/       # Device Admin 接收器
├── integration/ # 同签名应用集成（警报推送/状态查询）
├── data/        # Prefs 本地存储（零第三方依赖）
├── tts/ util/   # 语音播报 / 工具
docs/            # 银发认证倡议与演示主页
tools/           # 测试与回归脚本（Python）
```

## 隐私承诺

- 无广告 SDK、无追踪器、无分析 SDK、无推送通道、无 WebView（可审计，见依赖清单）
- 联系人/设置全部存储于本地，无云端账号体系
- 联网仅限：Open-Meteo 天气、子女自行配置的 AI 接口
- 位置信息仅用于天气与 SOS 短信（本地使用，默认关闭自动定位）

## 项目主页（GitHub Pages）

`docs/` 目录同时是项目主页（GitHub Pages）：交互式演示「广告轰炸机 vs 乐龄桌面」、
四大场景亲手操作、真机截图墙，以及「🛡 银发认证·一个提议」倡议页。

**上线 GitHub Pages**：仓库推送到 GitHub 后，Settings → Pages → Source 选
「Deploy from a branch」→ Branch 选 `main`、文件夹选 `/docs` → Save 即可。

发布方式：仓库 Settings → Pages → Source 选择对应分支的 `/website`（或 `/docs`）目录即可。
本地预览：`python -m http.server -d website 8000`，浏览器打开 http://localhost:8000

## License

Apache-2.0，见 [LICENSE](LICENSE)。
