# M3U8 / ZIP 转 MP4（Android）

一个全程在 Android 手机本地运行的转换工具：既可以粘贴 M3U8 链接下载并转换，也可以导入包含播放列表与视频分片的 ZIP 压缩包。视频数据不会上传到第三方服务器。

## 功能

- 粘贴 M3U8 地址，下载并转换为 MP4
- 导入包含 m3u8 与分片的 ZIP，自动解压、排序并转换
- 主播放列表自动选择最高码率
- 支持相对 URL、Referer、Cookie、User-Agent
- 支持常见 AES-128（不支持 SAMPLE-AES、Widevine 等 DRM）
- MPEG-TS 无损封装为 MP4
- fMP4 / M4S 按初始化片段合并
- 对已测试的 720×1280、缺少初始化片段的 fMP4 压缩包提供修复头
- 后台进度通知与取消任务
- 输出保存到 `Movies/M3U8转MP4`

## 已知限制

- 目前仅支持点播 M3U8，不支持持续直播录制
- 暂不支持 `#EXT-X-BYTERANGE`
- 暂不合并独立音频 rendition
- 缺少初始化片段的通用 fMP4 无法自动推导所有编码参数；内置修复仅适配已测试样本规格
- 不绕过 SAMPLE-AES、Widevine 等 DRM
- 请只处理你有权下载、转换或保存的媒体内容

## 构建

项目使用 Android Gradle Plugin 8.9.1、Gradle 8.11.1、compileSdk 35、minSdk 26。

```bash
gradle :app:assembleRelease
```

GitHub Actions 会在推送到 `main`、提交 Pull Request 或手动触发时构建、对 APK 进行 zipalign、签名并验证，然后上传构建产物。

## 签名说明

当前工作流每次构建都会生成临时测试证书，适合个人测试，但不同构建之间不能作为同一签名的更新包互相覆盖安装。正式长期使用时，应把固定 keystore 保存为 GitHub Actions Secret，并让工作流使用该固定证书；不要把正式签名私钥提交到公开仓库。
