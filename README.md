<div align="center">

# 星尘影视 · Xingchen TV

**为 Android TV、手机和平板打造的影音播放器**

支持点播与直播，兼顾电视遥控、大屏布局和移动设备操作。

[![Latest release](https://img.shields.io/github/v/release/sdoaiya/XCYS?label=最新版本)](https://github.com/sdoaiya/XCYS/releases)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84)](#安装)
[![TV](https://img.shields.io/badge/Android%20TV-Leanback-4285F4)](#安装)

[下载最新版本](https://github.com/sdoaiya/XCYS/releases/latest) · [查看更新记录](CHANGELOG.md) · [反馈问题](https://github.com/sdoaiya/XCYS/issues)

</div>

---

## 项目介绍

星尘影视是基于 FongMi / CatVod 播放生态持续二次开发的 Android 播放器。项目提供播放器、配置管理和设备同步能力，适用于 Android TV、电视盒子、手机与平板。

应用不内置影视内容或站源。安装后请导入你自己维护、合法可用的点播或直播配置。

## 功能

- **点播与直播**：支持多站点配置、选集、换源、直播频道及 EPG 信息。
- **多播放内核**：提供 ExoPlayer、IJK 和 MPV；可设置解码、字幕、音轨、倍速、缓存与预加载选项。
- **弹幕与字幕**：按需启用弹幕，调整显示样式；管理字幕轨道与字幕外观。
- **WebHome 扩展**：可为站点配置网页首页，并通过受控的 `window.fm` 接口调用原生播放、搜索、请求和缓存能力。
- **配置管理**：导入点播、直播和壁纸配置；支持 URL、文件及设备间同步，并保留配置历史。
- **设备同步**：通过局域网迁移配置、播放记录、收藏、应用设置及选择的登录态数据。
- **播放记录**：继续观看、历史与收藏支持跨配置恢复；可管理同步和 Webhook 行为。
- **播放器定制**：管理 MPV 配置、画面与音频效果、LUT、歌词、播放控制和 OSD。
- **手机与平板体验**：针对触控和大屏优化播放页；Android 15+ 平板支持 expanded PiP 比例配置。
- **本地管理服务**：可在局域网使用管理页、文件操作、同步与调试日志功能。
- **安全与稳定性**：持续加固本地服务和网页桥接，处理私网请求限制、数据迁移和配置兼容问题。

## 安装

前往 [GitHub Releases](https://github.com/sdoaiya/XCYS/releases/latest) 下载 APK。每个版本提供手机端和电视端的 universal、arm64 与 armv7 包：

| 设备 | 推荐版本 |
| --- | --- |
| Android TV / 电视盒子，架构未知 | `leanback-universal.apk` |
| Android TV / arm64 设备 | `leanback-arm64_v8a.apk` |
| Android TV / armv7 设备 | `leanback-armeabi_v7a.apk` |
| 手机或平板，架构未知 | `mobile-universal.apk` |
| 手机或平板 / arm64 设备 | `mobile-arm64_v8a.apk` |
| 手机或平板 / armv7 设备 | `mobile-armeabi_v7a.apk` |

不确定设备架构时，下载对应设备类型的 universal 包。安装后从设置页导入自己的配置源即可开始使用。

## 配置与内容来源

应用本身不提供 JSON、影视站源或直播源。用户可以通过配置 URL、配置文件或另一台设备导入自己的点播、直播和壁纸配置。使用第三方配置前，请确认来源可信且内容使用符合当地法律及版权要求。

## 项目来源

本项目在 FongMi / CatVod 开源播放器生态基础上开发，保留上游播放能力，并持续维护星尘影视自己的应用品牌、界面、播放器功能、WebHome 扩展和设备同步实现。感谢上游项目与相关开源依赖的贡献者。

## 构建

需要 JDK 21、Android SDK 37，以及 Chaquopy 所需的 Python 3.10。Release 构建还需要本地签名配置；不要将 keystore 或密码提交到仓库。

```bash
./gradlew :app:assembleMobileUniversalRelease
./gradlew :app:assembleMobileArm64_v8aRelease
./gradlew :app:assembleMobileArmeabi_v7aRelease
./gradlew :app:assembleLeanbackUniversalRelease
./gradlew :app:assembleLeanbackArm64_v8aRelease
./gradlew :app:assembleLeanbackArmeabi_v7aRelease
```

Android Studio 可直接打开此仓库。主要 Android 应用代码位于 `app/`；`catvod/`、`quickjs/` 和 `chaquo/` 提供解析、脚本和 Python 运行时支持。

## 文档

- [更新记录](CHANGELOG.md)
- [应用开发文档](docs/应用完整开发文档.md)
- [WebHome 扩展脚本指南](docs/webhome-extension/README.md)

## 反馈与贡献

请通过 [GitHub Issues](https://github.com/sdoaiya/XCYS/issues) 提交问题或功能建议。报告问题时，建议附上设备型号、Android 版本、应用版本及可复现步骤；不要在公开 issue 中粘贴账号密码、Cookie、令牌或私有配置。
