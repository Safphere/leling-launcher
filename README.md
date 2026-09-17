<div align="center">

<img src="docs/logo.svg" width="88" alt="乐龄桌面">

# 乐龄桌面 LeLing Launcher

**老人友好的防广告 Android 桌面 —— 零广告 · 零信息流 · 大字大图标 · 子女远程可管**

[![在线演示](https://img.shields.io/badge/在线演示-safphere.github.io-E8722A)](https://safphere.github.io/leling-launcher/)
[![API](https://img.shields.io/badge/minSdk-26-blue)](#)
[![License](https://img.shields.io/badge/License-Apache_2.0-green)](LICENSE)
[![English](https://img.shields.io/badge/English-README_EN-4A5160)](README_EN.md)

</div>

---

## 🛡 银发认证 · 一个提议

> **「未成年人有防沉迷，老年人也该有银发认证。」**
> **一款应用只要敢对老人弹广告，就不配出现在爸妈的手机上。**

银发认证是一个开放的社会倡议：不是机构、不发证书、不收费。
对老人**零广告**、不耍假“×”不吓唬人、大字少步骤不设坑——
看到 🛡，就知道这款应用对老人是干净的。

📖 倡议全文与参与方式：[docs/银发认证倡议.md](docs/银发认证倡议.md)

---

## 这是什么

一款为老年人设计的 Android 防广告桌面（Launcher）。把智能手机变回老人能用、敢用的工具：

- **看得清**：大字大图标大头像，联系人点一下直接拨号
- **没广告**：桌面零广告零信息流；白名单模式让广告类应用在老人手机上彻底消失
- **有人管**：子女模式集中配置，紧急情况一键直达

**🌐 在线交互演示（不用装 App）**：[safphere.github.io/leling-launcher](https://safphere.github.io/leling-launcher/)
打开就是一台“手机”——重演老人被广告围猎的日常，点「受够了吗」一键清场，体验银发认证后的清净桌面。

---

## ✨ 功能特性

### 桌面三页 + 便捷页
- **联系人页**：大头像便捷呼叫，通讯录导入 / 相册选头像，点错有确认
- **生活页**：时钟、天气（自动定位+语音播报）、农历、月历
- **应用页**：白名单大图标网格，搜索边输边筛，应用秒开
- **便捷页**：手电筒、家庭地址卡（走失求助，大字卡 + 一键导航）

### 健康提醒与守护
- **健康提醒**：吃药 / 量血压 / 天气关怀，到点**全屏大字 + 语音播报**，确认记录子女可查
- **夜间勿扰**：22:00~07:00 提醒自动推迟到早上，SOS 不受影响
- **电话卡检测**：每天定时检测 SIM，卡松了自动提醒 + **一键重启**
- **流量守门员**：自动查流量，余额不足自动关流量防扣费，月结自动恢复
- **SOS 求助**：按住 3 秒，自动发带定位的短信 + 直接呼叫紧急联系人
- **LLZT 报平安**：子女发一条短信，自动回复电量 / SIM / 流量 / 提醒确认

### 子女模式（PIN 保护）
- 隐藏入口（连点页面指示 5 次），默认密码 1234
- 联系人、白名单、提醒、勿扰、SIM 检测、流量阈值集中管理
- 隐藏应用（解决“看见什么”）与**防沉迷**（解决“用多久”：每天/每次/时段）分开处理
- 新装应用即时上报，防远程诱导装 App

### AI 语音问答
- 按住说话即可提问，内置 GLM 大模型接口（配置一个 API Key 即用，应用内零广告零追踪）

---

## 🚀 快速开始

### 环境要求
Android Studio（含 JDK 17）｜ minSdk 26（Android 8.0）｜ targetSdk 35

### 构建

```bash
# 调试版（含演示数据预置与调试钩子）
./gradlew assembleDebug

# 正式版（R8 混淆，约 2.4MB）
./gradlew assembleRelease
```

正式签名从仓库外的 `keystore.properties` 读取（**不要提交**，已在 .gitignore）：

```properties
storeFile=keystore/your-release.jks
storePassword=你的密码
keyAlias=your-alias
keyPassword=你的密码
```

没有该文件时，release 构建自动回退 debug 签名（仅供本地调试）。

### 安装与激活

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

1. 首次启动进入配置向导：设为默认桌面 → 一键授权 → 添加联系人，共 3 步
2. 可选：激活设备管理员，解锁“硬管控”（隐藏违规应用）：

```bash
adb shell dpm set-device-owner com.safphere.launcher/.admin.ElderDeviceAdminReceiver
```

---

## 📁 目录结构

```
docs/            # 银发认证倡议与演示主页（GitHub Pages）
app/src/main/java/com/safphere/launcher/
├── home/        # 桌面主界面（三页 + 便捷页 + 壁纸 + 搜索）
├── reminder/    # 健康提醒（定时/天气/勿扰/确认）
├── alert/       # 全屏警报（SIM/地震/洪水/健康提醒共用）
├── call/        # 拨号与 SOS
├── contacts/    # 联系人编辑
├── sms/         # 短信编写与远程状态查询（LLZT）
├── guard/       # 防沉迷与应用管控（软/硬管控）
├── agent/       # 悬浮球与无障碍服务
├── ai/          # AI 语音问答（GLM 接口）
├── flow/        # 流量守门员
├── sim/ reboot/ perm/ weather/ tts/ settings/ setup/ data/ util/
tools/           # 测试工具与验证脚本
```

---

## 🔐 隐私承诺

- 桌面本体**零广告、零信息流、零用户追踪**，依赖仅官方库，源码可审计
- 联系人、提醒、配置全部**本地存储**，不上云、不回传
- LLZT 只回复**通讯录联系人**，陌生号码静默忽略；子女模式可一键关闭
- AI 问答默认关闭，需子女配置 Key 才启用

---

## 🤖 Powered by Z.ai

<img src="docs/assets/zai-logo.png" width="56" align="left" alt="Z.ai" style="margin-right:14px">

**本项目由 Z.ai 的 GLM-5.3-Flash 模型 × ZCode Harness 完成全部开发。**

从桌面架构、健康提醒工作流、防沉迷引擎，到这个演示主页与银发认证倡议——
产品、代码、测试、文案，均由人与 AI 结对完成。
AI 时代的意义，不只是花哨的技能，而是让“给家里人做点实在的东西”变得触手可及。

模型：[GLM-5.3-Flash](https://docs.z.ai/) ｜ 开发工具：ZCode Harness ｜ 组织：[zai-org](https://github.com/zai-org)

---

## 📄 License

[Apache-2.0](LICENSE)

<div align="center">

[English](README_EN.md) ｜ 建议反馈请提 [Issue](https://github.com/Safphere/leling-launcher/issues)

</div>
