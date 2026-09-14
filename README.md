---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '83b6e61a-c025-4a97-ab58-f25c857e9fbd'
  PropagateID: '83b6e61a-c025-4a97-ab58-f25c857e9fbd'
  ReservedCode1: 'c3e97d7d-959d-4895-8ad8-9e7d8f5e4293'
  ReservedCode2: 'c3e97d7d-959d-4895-8ad8-9e7d8f5e4293'
---

# 石大课表 (SHZU Schedule)

> **非官方声明**：本项目**非石河子大学官方软件**，由在校学生利用**课余时间**出于学习目的独立开发维护，与学校官方及教务部门无任何关联。

石河子大学课表安卓应用：自动登录学校统一身份认证（CAS），抓取教务系统课表，本地离线渲染，带五周滚动窗口与开学日期自动识别。

![Platform](https://img.shields.io/badge/Platform-Android-3DDC84) ![Language](https://img.shields.io/badge/Language-Java-orange) ![UI](https://img.shields.io/badge/UI-WebView%20HTML%2FCSS%2FJS-blue)

## 功能特性

- **自动登录**：学号密码一次保存，CAS 统一认证自动续期，Cookie 持久化免频繁登录
- **MFA 兼容**：CAS 触发短信二次验证时自动停留验证页，手动验证后流程无缝继续
- **全学期课表**：一次抓取全学期汇总数据，本地按周过滤推导任意周课表
- **五周滚动窗口**：本地缓存五周课表离线秒开，窗口末尾三天通知提醒更新
- **开学日期自动识别**：通过教务教学周历接口动态计算学期周次，拒绝硬编码
- **节次时间动态提取**：作息时间表（如 1-2 节 10:00-11:40）从教务页面实时提取
- **课程详情**：点击课程卡片放大弹窗（FLIP 动画 + 课程色减淡背景），含教师/教室/周次/教学班级/教学备注
- **交互体验**：滑动切周、同课同色、今日高亮、开屏动画

## 技术架构

```
┌────────────────── Java 层（原生逻辑）──────────────────┐
│ MainActivity    CAS登录 / 状态机 / 抓取调度 / 渲染生成    │
│ ScheduleStore   五周窗口存储 / 周次计算 / 提醒时间        │
│ ReminderReceiver 窗口末尾通知                            │
└────────────────────────┬───────────────────────────────┘
                         │ addJavascriptInterface ↔ evaluateJavascript
┌────────────────────────▼───────────────────────────────┐
│              WebView 层（学校页面 + 自绘界面）              │
│  · CAS/教务页面：注入 JS 自动化操作（登录、DOM 解析课表）    │
│  · 课表展示页：Java 拼接 HTML/CSS/JS 渲染 + 交互动画       │
└─────────────────────────────────────────────────────────┘
```

- 语言：Java（零 Kotlin）
- 界面：WebView + HTML/CSS/JS（课表、弹窗、动画全部为网页实现）
- 兼容：Android 7.0+（minSdk 24），适配 Android 12+ 系统 SplashScreen

## 工作原理

1. **登录链路**：访问教务系统 → 无会话则 302 到 CAS → 注入 JS 自动填表登录 →（如触发短信验证则等待用户手动完成）→ 凭据在到达教务系统后才落盘
2. **课表入口探测**：教务首页扫描链接（href 特征 xskb/kbcx → 文字特征"课表"）→ 空结果复扫 → 超时转入预设 URL 猜测，多层兜底
3. **DOM 解析**：金智教务课表表格按 `th`（节次+时间）/`td`（7 列课程）结构提取，`font[title]` 属性区分教师/教室/周次/班级/备注等字段，支持同格多课程拆分
4. **周次推导**：教学周历接口获取第 1 周周一日期 → 当天日期换算学期周次 → `weekMatches()` 解析"3-16(周)"、"单双周"等周次表达式

## 构建

环境要求：JDK 21、Android SDK（compileSdk 34）、Gradle 8.4 + AGP 8.3.2

```bash
git clone https://github.com/<你的用户名>/SHZUSchedule.git
cd SHZUSchedule
gradle assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

> 首次构建请在 `local.properties` 中配置本机 SDK 路径（参考 `sdk.dir=F:\\AndroidEmulator\\android-sdk`，按实际路径修改）。

## 隐私说明

- 学号密码仅保存在本机 SharedPreferences，不上传任何第三方服务器
- 网络请求仅发生在与学校系统（authserver.shzu.edu.cn / jwgl.shzu.edu.cn）之间
- 本项目不含任何硬编码凭据

## 免责声明

- 本项目为**非官方**的学生业余作品，与石河子大学及其教务部门无任何关联
- 由在校学生在**业余时间**独立开发，仅供学习交流
- 请合理使用，遵守学校相关规定
- 使用本项目产生的一切后果由使用者自行承担

## License

[MIT](LICENSE)