# 盯单助手 Android APP V1.1 Alpha

目标设备为 vivo X200s（Android）。V1.1 合入红色标题栏图像分割模块。

## 已实现
- CameraX 后置摄像头预览与拍照
- 中文 ML Kit 本地 OCR
- **V1.1 红条分割**：按红色订单标题栏把整屏切成单张卡片，每张卡片计算 64-bit dHash，供后续去重
- OpenAI 兼容 `/chat/completions` 视觉接口直连
- API 地址、API Key、模型名称可在 APP 内配置并保存在本机
- 显示 OCR 原文、切卡结果（数量/占屏区间/哈希）及模型返回内容

## V1.1 红条分割的已知限制
- 只用红色标题栏作锚点；卡片内部的绿色信息条、蓝色标签不作为订单边界
- 屏幕 ROI 是 6.mp4 的初始估值（`OrderCardSegmenter.Config.screenRoi`），换机位/距离后需重新校准
- 仅矩形 ROI，尚未做四角透视矫正
- 屏幕顶部被截断的卡片不恢复；最后一张标记为 `isPartialBottom`，应滚动后重拍
- dHash 仅作辅助信号，真实去重仍需结合 OCR 文本、报价、起终点、备注

## 尚未实现（下一轮）
- 逐卡片分别上传（当前仍整屏上传一次，仅切卡结果用于提示）
- APP 内可视化 ROI 四边校准框与四角透视矫正
- 连续画面变化检测、自动扫描、多信号订单去重
- 本地订单数据库、离线队列和失败重试
- 车辆档案、动态接单策略、地图道路里程及载货公里单价
- Android Keystore 加密保存 API Key
- 针对 vivo 后台运行/省电策略的适配

## 构建
GitHub Actions 在线构建（`.github/workflows/build-apk.yml`）：push 到 main 或手动 dispatch 即出包，
产物发布到 `latest` Release，固定地址
`https://github.com/nidaye768/DingDanAssistant/releases/download/latest/DingDanAssistant.apk`。

## API 配置
默认接口基址为 `https://api.openai.com/v1`，模型名示例 `gpt-4o-mini`。
请填写你自己的、支持图像输入且兼容 OpenAI Chat Completions 格式的模型及 API Key。
不同服务商的兼容程度不同；若其不支持 `image_url` 的 data URL 格式，需要在下一轮增加对应适配器。

## 安全说明
API Key 目前存储在应用私有 SharedPreferences 中，尚未使用 Android Keystore 加密。
不要把此 Alpha 版本分发给他人，也不要在公共设备上保存正式密钥。
拍摄和测试请在停车状态下进行。
