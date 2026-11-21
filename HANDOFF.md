# HANDOFF · 接手指令

> 把这一整段复制给下一个接手的 Agent 即可。更新时间：2026-11-21 晚。

---

你是 ubcm 项目的接棒开发者。工作目录：`/Users/kang/Desktop/My Projects/ubcm`（**已经是 git 仓库**）

## 项目是什么
给北京中医药大学学生做的校园 App（Android 优先，Kotlin + Compose，**零第三方运行时依赖**）。
目标：把课表、通知、待办、成绩、考试、一卡通余额/流水、图书馆座位收敛到一屏。
形态已定：**本地优先的个人工具** —— 数据只存本机，不代登录、不集中托管、不对外提供服务。

## 开工先读（按顺序，别自己重新调研）
1. `AGENTS.md` ← **硬约束**：日志规则、提交规则、红线、9 条易踩的坑
2. `DEVLOG.md` ← 逐轮日志，看清上一棒停在哪、踩过什么
3. `docs/11-交接文档.md` ← 项目全景
4. 按需：`docs/12`（首页/课表）、`docs/13`（图书馆）、`docs/10`（一卡通）

## 已经打通的东西（**不要重复做**）

| 模块 | 状态 | 关键接口 |
|---|---|---|
| 门户 i.bucm.edu.cn | ✅ | `POST /apim/sems-tp-nup/**`，`Authorization: Bearer <JWT>`（只认 header） |
| 教务 jw.bucm.edu.cn | ✅ | 课表 `POST /jsxsd/xskb/loadSkzccx`（body `zc=`）→ **JSON 不是 HTML** |
| 一卡通 ykt.bucm.edu.cn | ✅ | CAS → servicehall cookie → `POST /business/thirdQuerSelfTrade` |
| 图书馆 kjyy.bucm.edu.cn | ✅ | `GET /ic-web/reserve?resvDates=&roomIds=&sysKind=8`（座位+坐标+预约时段） |

已完成的功能：首页四卡（下一节课/余额/今日消费/未读待办）、平板适配、登录器四目标（一次登录拿全 5 条凭证）、图书馆空位/我的预约、**找空桌聚类 + 座位缩略图 + App 内预约（三段式）**。

## 四个必须知道的实现细节
- **一卡通响应 AES 加密**：`{"data":"<前16位是key><密文>"}`，AES/ECB/PKCS5
- **教务 TLS 缺中间证书**：`jw.bucm.edu.cn` 只发 1 张证书，已内置补链（`Tls.kt`），别删
- **WebView 不能放 Compose `AndroidView`**：真机白屏，已改传统 `setContentView`
- **图书馆 `resvCode="0"`**：预约**不需要验证码**，直接 `POST /ic-web/reserve/seat`

## 绝对不要再踩的坑
- 周次必须 `Math.floorDiv`，`/` 会向零取整
- `CookieManager.getCookie(url)` 要传**真实域 + path**（`accessToken` 的 path 是 `/tp_nup`）
- 座位聚类**必须按 `roomId` 分组**，各区域坐标系独立
- 座位接口**限流**：间隔 <300ms 被拒，≥350ms
- 图书馆移动端路由**没有** `/ic/` 前缀（是 `#/seatReserve`）
- 学校页面**冷启动不认 URL hash**，要加载完延迟 2.5s 再 `$router.push()`
- JWT 4h / CASTGC 数小时 / servicehall 更久 —— **不能用布尔表达登录态**

## 构建与测试
```bash
cd app-android
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=$HOME/android-sdk
./gradlew :app:assembleDebug        # 首次 ~3 分，增量 ~10 秒
./gradlew :app:testDebugUnitTest    # 纯逻辑单测，秒级，改逻辑必跑
```
平板（USB，50MB/s）：`adb -s 098A2C60099C3540 install -r app/build/outputs/apk/debug/app-debug.apk`
模拟器：`$ANDROID_HOME/emulator/emulator -avd duedo -no-snapshot-load -no-boot-anim -gpu host &`

## 下一步优先级
1. **凭证加密存储**（P1）— 现在明文 SharedPreferences。换 `EncryptedSharedPreferences` 需引入 androidx.security，**会破坏"零依赖"，先做决策**
2. **课表周视图**（P0）— `Timetable.kt` 数据够画了。3–5 节的课不能按"两节一组"分组，要按节次高度绝对排版
3. **周次锚点自动化**（P1）— `Timetable.DEFAULT_FIRST_WEEK_MONDAY` 是常量，**换学期不改进度会静默算错**
4. **图书馆补完**（P1）— 只扫五层（全馆 16 区约 8–10 秒）、日期固定明天、缩略图每桌归一化看不出真实长度比、研讨间/静音仓没做
5. **成绩/考试**（P2）— 等期末有数据

## 红线（动写操作前必读 docs/05）
- 🔴 不代登录、不存密码、不托管别人凭证（有刑事判例）
- 🔴 **写操作必须三段式**：prepare 复查 → commit 只发一次 → readback 核对，未知**不升级为成功**
- 🔴 **不破解验证码、不做脚本抢座**。学校加人机验证是有意的，绕过它不属于"技术攻坚"
- ✅ 只读自己的数据，数据不出设备
- 预约是真占座：用户不去就是违约。**测试写操作要格外小心，宁可让用户自己点第一次**

## 工作方式
- **每轮收工必须追加 `DEVLOG.md` 一节**（格式见 `AGENTS.md` §三），并在索引表加一行
- 提交前 `git status` 确认无 build 产物、无凭据
- 改纯逻辑（周次/聚类/时段冲突）**先写单测**——这几个都出过真 bug，都是测试抓到的
- 中文输出，先给结论再给证据
- 判断"某功能有没有"：菜单 → 首页组件 → 接口探测，三层都要查
- 浏览器只读采集用独立调试实例，用完 `pkill`
