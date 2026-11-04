# 盯单助手 Android APP V1.0 Alpha

这是第一轮可编译原型工程，目标设备为 vivo X200s（Android）。

## 已实现
- CameraX 后置摄像头预览与拍照
- 中文 ML Kit 本地 OCR
- 根据 OCR 文本块纵向间距估算订单卡片区域数量
- OpenAI 兼容 `/chat/completions` 视觉接口直连
- API 地址、API Key、模型名称可在 APP 内配置并保存在本机
- 显示 OCR 原文及模型返回内容

## 尚未实现（下一轮）
- 真正按卡片边界裁剪、逐卡片上传（当前先整屏上传）
- 可视化 ROI 框选与透视/反光/摩尔纹校正
- 连续画面变化检测、自动扫描、订单去重
- 本地订单数据库、离线队列和失败重试
- 车辆档案、动态接单策略、地图道路里程及载货公里单价
- Android Keystore 加密保存 API Key
- 针对 vivo 后台运行/省电策略的适配

## 构建
1. 使用 Android Studio 打开项目目录。
2. 安装 Android SDK 35，使用 JDK 17。
3. 等待 Gradle 同步完成。
4. 连接手机，允许摄像头权限，运行 app。

## API 配置
默认接口基址为 `https://api.openai.com/v1`，模型名示例 `gpt-4o-mini`。
请填写你自己的、支持图像输入且兼容 OpenAI Chat Completions 格式的模型及 API Key。
不同服务商的兼容程度不同；若其不支持 `image_url` 的 data URL 格式，需要在下一轮增加对应适配器。

## 安全说明
API Key 目前存储在应用私有 SharedPreferences 中，尚未使用 Android Keystore 加密。
不要把此 Alpha 版本分发给他人，也不要在公共设备上保存正式密钥。
拍摄和测试请在停车状态下进行。
