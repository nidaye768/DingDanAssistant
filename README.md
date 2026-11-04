# 盯单助手 Android APP V1.2 Test

目标设备：vivo X200s（Android）。本版本用于双手机原型功能测试，带本机验收中心。

## V1.2 本轮内容
- 重新布局首页：工作模式、采集状态、验收中心、API 配置、测试日志分区。
- 极速模式 / 全自动模式切换入口（当前为模式 UI 与流程标识；两套完整决策流水线尚未全部接通）。
- 保留 CameraX 后置摄像头、中文 ML Kit OCR、红色订单标题栏切卡和视觉模型 API 调用。
- 本机自动验收：载货公里单价公式、未知条件保守决策、车型不符过滤、道路里程未知保护、哈希汉明距离、文本归一化。
- API 连通性测试按钮。
- 验收报告明确区分本机逻辑自检与真实业务验收。

## 当前已实现 / 未实现边界
已实现：单次拍摄、OCR、红色标题栏分割、哈希计算、视觉 API 请求、API 配置保存、本机逻辑自检。
未完成：连续自动扫描、连点器遮挡 ROI 排除、透视校正、完整多信号去重、订单数据库/离线队列、车辆档案、动态策略中心、地图道路里程、全自动业务决策、Keystore 加密。
极速/全自动按钮目前用于模式切换和界面状态提示，不能视为两套生产级自动决策流程已完成。

## 验收方式
1. 安装 APK，进入“验收中心”点击“运行本机自动验收”。
2. 检查每项通过/失败及说明。
3. 点击“测试大模型 API 连通性”验证当前服务商接口。
4. 将主手机订单列表显示在画面中，使用另一台手机拍摄，检查 OCR 与红条切卡结果。
5. 用人工标注的真实订单和录屏逐条统计切卡准确率、字段准确率、误提醒/漏提醒。单机自检不替代该步骤。

## GitHub Actions 构建
push 到 main 自动构建 Debug APK，也可在 Actions 页面手动运行。
APK Artifact 名称：DingDanAssistant-apk。
Release 固定地址（工作流成功发布后可用）：
https://github.com/nidaye768/DingDanAssistant/releases/download/latest/DingDanAssistant.apk

## API 配置
支持 OpenAI Chat Completions 风格的 `/chat/completions` 接口。填写 API 基址、Key、模型名称。
注意：当前 API Key 仍使用应用私有 SharedPreferences 保存，尚未接入 Android Keystore；不要在公共设备保存正式密钥。

## 安全
只做监测、分析和提醒，不自动抢单、不模拟点击货拉拉。测试请停车后进行。
