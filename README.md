# UBcm · 北京中医药大学校园助手

> 给北京中医药大学学生用的个人校园 App：课表、通知、日程待办、一卡通、图书馆座位、空教室、校园服务入口，收敛到一屏。
>
> **非官方**、**本地优先**、**数据不出设备**。

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

## 功能

| Tab | 内容 |
|---|---|
| 首页 | 问候、通知条、4×2 可编辑小组件（校园卡余额/入口）、日程（三态高亮 + 秒级倒计时）、待办 |
| 学习 | 图书馆空位卡、周/日课表（固定 7 列、今天高亮、当前时间线、滑动跟手切周）、课程详情 |
| 校园 | 校园卡（余额/今日消费/7 天与 30 天折线/消费分布环形图）、服务入口 |
| 我的 | 头像（本地上传）、身份信息、凭证状态、数据体检、周次锚点、清理缓存、关于 |

另外：座位预约（楼层折叠 + 区域座位图）、空间预约（研讨间/静音仓/朗读亭）、空教室查询、完整待办（子待办/DDL 滚轮/完成动画）。

---

## 工程结构

```
app-prod/     正式软件（包名 com.kang.ubcm.prod，应用名 UBcm）
app-android/  测试台（早期用于验证接口/算法，保留 177 个单测，不再新增功能）
docs/         设计与接口文档（18–28 为正式版设计、预览图在 docs/previews/）
research/     早期接口调研的原始样本（已脱敏）
```

> `app-prod` 是从 `app-android` 复制演进的，两个工程各自独立。新功能只加在 `app-prod`。

---

## 构建

```bash
cd app-prod
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=$HOME/android-sdk

./gradlew :app:assembleDebug        # 产物 app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # 单元测试（234 个）
```

要求：JDK 21、Android SDK（compileSdk 37 / minSdk 29）。

---

## 隐私与合规（重要）

- **不代登录、不托管他人凭证**：密码只作为函数入参活一次，不写 SharedPreferences、不写日志、不进 State。
- **本地优先**：所有数据只存本机，不上传任何服务器。
- **不破解验证码**：学校页面的人机验证不会被绕过；需要验证的步骤交给用户本人。
- **只读优先**：写操作（预约等）遵循 prepare → commit 一次 → readback 三段式，绝不自动重试。
- 仓库内文档与样本**已脱敏**（学号/姓名/手机号/卡号/流水均为示例值）。

---

## 免责声明

本项目为个人学习与自用工具，**与北京中医药大学官方无关**，不代表学校立场。
使用者需自行遵守学校信息化管理规定；因使用本软件产生的一切后果由使用者自行承担。

---

## 许可证

[Apache License 2.0](LICENSE)
