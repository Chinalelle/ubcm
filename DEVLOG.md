# 开发日志（DEVLOG）

> 这个文件是**按轮次追加**的工作记录：每一轮谁干了什么、关键发现、踩到的坑、留下的尾巴。
>
> **给所有接手的 Agent：动手之前先读这个文件，收工之前必须在末尾追加一节。**
> 具体规则见 [`AGENTS.md`](AGENTS.md)。不要修改历史轮次（除了修正事实性错误并注明）。
>
> 时间一律用北京时间（Asia/Shanghai）。

---

## 轮次索引

| # | 时间 | 一句话 | 提交 |
|---|---|---|---|
| 0 | 2026-11-19 | 调研 + 最小可执行框架 + 一卡通逆向（上一棒） | `025ad8a` 之前的基线 |
| 1 | 2026-11-20 夜 | 首页聚合卡片 + 课表领域逻辑 + 20 个单测 | 同基线 |
| 2 | 2026-11-21 凌晨 | 小米平板真机测试，修 5 个只在真机出现的 bug | 同基线 |
| 3 | 2026-11-21 凌晨 | 图书馆模块（空位/预约/登录）+ 破解 doAuth「URL 缓存」 | 同基线 |
| 4 | 2026-11-21 凌晨 | 「找空桌」几何聚类 + 座位图接口破解 | 同基线 |
| 5 | 2026-11-21 凌晨 | 完整流程：时段 → 空桌 → 简略图 → 预约 | 同基线 |
| 6 | 2026-11-21 凌晨 | 座位缩略图（Canvas 真实坐标） | 同基线 |
| 7 | 2026-11-21 晚 | **建立 git 仓库 + 本开发日志** | `e68173a` `d6f1c54` |
| 8 | 2026-11-21 夜 | **课表周视图（P0）+ 周次锚点手动设置 + 修 `null` 显示 bug** | 见本节 |

---

## 轮次 0 · 2026-11-19 · 调研与最小可执行框架（上一棒）

**做了什么**
- 163 个应用 / 979 项服务的可达性调研，四套数据源打通（门户 API、教务、一卡通、图书馆座位）
- Kotlin + Compose 零第三方依赖框架，18 个数据探针
- 一卡通 AES 解密（key 在密文前 16 位）
- WebVPN 主机名编码（wEngine 标准）

**产出**：`docs/00-10`、`app-android/`

**留给下一棒**：登录流程正式化、首页聚合卡片、课表周视图

---

## 轮次 1 · 2026-11-20 23:32 → 11-21 00:33 · 首页聚合卡片

**做了什么**
- 新增 `Timetable.kt`：周次计算、**大节→小节时间推算**（均分）、下一节课、今日课程。纯 Kotlin，不依赖 Android
- 新增 `TimetableStore.kt`：课表落盘，冷启动/离线先读盘
- 新增 `HomeViewModel.kt` + `HomeScreen.kt`：四张卡（下一节课倒计时 / 卡余额 / 今日消费 / 未读待办）
- 新增 `Ui.kt` 共享配色；`MainActivity` 改成两页（首页 / 数据体检）
- 加 **20 个单元测试**（`TimetableTest`），夹具由真实课表 `courses.json` 生成
- 派 teammate 用 REA 静态分析门户前端

**关键发现**
1. 🔴 **纠正既有错误认知**：`/task/getTaskInfo` 的 `message_remind` 返回 118353 条是**全校消息量级**，不是个人待办。真正的未读数在 `POST /weChat/getCardCenterUnReadInfo` → `data.CNT`（`tab_type="5"` 为待办）。顺手修了体检页里那个误导人的探针。
2. 大节表均分推算小节的模型成立：大节 1-2 = 08:30–10:00 / 2 = 45 分钟，与官方作息逐条一致（有单测断言）。
3. `is_read` 参数前端从未传过，不要用它过滤。

**坑**
1. **`weekOf` 向零取整**：开学前 6 天被算成「第 1 周」。改 `Math.floorDiv`。测试抓到。
2. **刷新会丢余额**：`refreshCard(force)` 先 `clearEcard`，但 servicehall 比 CASTGC 活得久，一点刷新就把还能用的会话扔掉。
3. **「已登录」≠「没过期」**：JWT 只有 4 小时。首页改成三态（已登录 / 门户过期 / 一卡通在线）。
4. 三种凭证寿命不同：JWT 4h / CASTGC 数小时~天 / servicehall 更长。**不能用一个布尔表达登录态**。

**未验证**：`getCardCenterUnReadInfo` 的真实返回、`loadSkzccx` 整条联网刷新——当时所有学校登录态都过期了。

---

## 轮次 2 · 2026-11-21 00:33 → 00:43 · 小米平板真机测试

**做了什么**
- 用 USB（50 MB/s）装到小米平板 9 Pro Max（Android 17 / HyperOS）
- 用户通过 App 内 WebView 完成登录，**一次拿全五条凭证**（access_token / castgc / jw_jsessionid / ecard_session / ecard_openid）

**关键发现（全部只有真机才暴露）**
1. **大屏布局**：标题被状态栏压住、卡片拉成 3408px 横条 → 加 `WindowInsets.safeDrawing` + 限宽居中
2. **登录器缺「教务系统」目标**：课表刷新必须有 jw 域 JSESSIONID，原来只有 3 个目标
3. 🔴 **轮询查错了 cookie 域**：`CookieManager.getCookie(url)` 必须传 cookie **真正所在的域 + path**。`accessToken` 的 path 是 `/tp_nup`，查 `i.bucm.edu.cn/` 永远返回 null → **登录成功也一条都存不下来**
4. 🔴 **教务站 TLS 证书链不完整**：`jw.bucm.edu.cn` 只发 1 张叶子证书（缺中间证书），Android 不会像浏览器那样补链 → `SSLHandshakeException`。修法：内置中间证书，系统校验失败时补链重试（**不是跳过校验**）
5. 🔴 **WebView 白屏**：Compose 的 `AndroidView` 包 WebView 在这台设备上整片白（DOM 正常、CDP 截图超时）。`setLayerType(SOFTWARE)` 无效，**改传统 `setContentView` 布局解决**

**验证结果**：首页四张卡真实数据全通；`getCardCenterUnReadInfo` → `data.CNT=0` 首次实测；`loadSkzccx` → `sessions=34 slots=12` 首次实测。

**产出**：`Tls.kt`、`SchoolWebActivity.kt`、`docs/12`

---

## 轮次 3 · 2026-11-21 00:43 → 00:54 · 图书馆模块

**做了什么**
- `LibraryViewModel` + `LibraryScreen`：全馆状态、4 层可展开、18 个区域明细、违约记录
- 「我的预约」：`GET /ic-web/reserve/resvInfo`（17 条记录，含座位号/区域/时段）
- 顶部加图书馆入口

**关键发现**
1. 🔴 **破解 doAuth「URL 缓存」**（交接文档里的已知缺口）：
   ```
   GET /authcenter/toLoginPage?redirectUrl=.../ic-web//auth/token?uuid=<32hex>&extInfo=<前10位>
   ```
   这一步把 redirectUrl 按 uuid 缓存到服务端，之后 CAS 才能回跳 `doAuth`。随机 id 由前端生成。
2. 移动端 Vue Router **没有 `/ic/` 前缀**：正确的是 `#/seatReserve`、`#/reserveRecord`
3. `resvInfo` 时间带 `+00:00`，**要 +8 小时**才是北京时间
4. **不要用 `resvStatus=1217` 猜状态**：只有一种样本。改用「有没有签到/有没有结束/时间关系」推

**坑**
- 学校页面**冷启动不认 URL 里的 hash**，会落回首页；必须加载完再用 `$router.push()` 切，且要**延迟 2.5 秒**（`onPageFinished` 时 Vue 还没 mount）

**产出**：`docs/13` §1-7

---

## 轮次 4 · 2026-11-21 00:54 → 01:20 · 找空桌

**用户需求**：图书馆大桌（4/6 连座）和单人桌混排，人少时想找一张**整桌空着**的大桌。

**做了什么**
- 破解座位图接口：`GET /ic-web/reserve?resvDates=yyyyMMdd&roomIds=<单区id>&sysKind=8`
  - 参数名是**复数**，从前端 `SeatReserve.handleGetSeat()` 源码读出
  - 返回每个座位的 `coordinate`（百分比坐标）和 `resvInfo`（预约时段）
- `DeskFinder.kt`：**几何聚类**——按 y 分行，行内按 x 排序、间距 > 2.4 断开 = 一张桌。阈值来自实测（同桌 1.94–2.17，桌间 2.6–3.3）
- 12 个单测，夹具是真实 164 座

**关键发现**
- 五层南区 = 5 张大桌（13/9/8/8/5 连座）+ 一堆单人桌
- `resvInfo` 就是时段维度，能算"某时段是否空闲"

**坑**
1. 🔴 **跨区域坐标混算**：把 5 个区域座位合并聚类，切出「5S077–5WB267」跨区假桌子。**每个区域的坐标是独立坐标系**，必须 `groupBy { roomId }`
2. **服务端限流**：间隔 <300ms 返回「请求频繁」。改成 350ms + 失败重试
3. `nameRange` 按 x 排序会输出倒序（5S157–5S164），要按**座位号**排

---

## 轮次 5 · 2026-11-21 01:20 → 01:30 · 完整预约流程

**用户需求**：输入时段 → 检查空桌 → 空桌列表 → 点桌进 4/6 座简略图 → 点座位自动填时段并完成预约。

**做了什么**
- 时段选择（上午/下午/晚上/全天），按时段筛空桌（区间重叠判断）
- 简略图 + 点座位预约
- 三段式写操作（`LibraryViewModel.reserveSeat`）：prepare 复查 → commit 一次 → readback 核对，未知不升级为成功

**关键发现（推翻上一轮的判断）**
- 🔴 **不需要验证码**！上轮看进 `#/seatReserve` 会调 `captcha/get` 就下了结论，是错的。
  `handleSubmit` 里：`resvCode==="1"` 图形、`"2"` 滑块、**其他直接提交**。学校实测 `resvCode === "0"` → **直接提交**
- 提交接口：`POST /ic-web/reserve/seat`
  - `appAccNo` **不是学号**（53247 ≠ 20260000000），用 `/ic-web/auth/userInfo` 拿
  - `resvDev` 必须是**字符串数组**，传数字报「请求参数错误」

**没做的**
- **没有替用户提交任何一次真实预约**（会占座，不去就是违约）。界面和接口用无效座位号验证过，不会占座。

---

## 轮次 6 · 2026-11-21 凌晨 · 座位缩略图

**做了什么**
- 把「一排编号方块」换成 **Canvas 真实几何缩略图**：按 `coordinate` 百分比定位，实心圆点 + 座位号，颜色区分空/占
- 点击用**最近邻命中**（半径 60px），相邻座位不会点错
- 按每张桌自己的坐标范围归一化，保证点得准

**遗留**：现在看不出"13 连座的实际长度是 4 连座的 3 倍"，如需真实比例要改成按整个区域归一化（代价是单点变小）。

---

## 轮次 7 · 2026-11-21 21:12 · 建立 git 仓库与开发日志

**用户需求**：从现在开始写开发日志、创建 git 仓库、记录每一轮干了什么，所有接手的 agent 都要记录；并更新开发文档。

**做了什么**
- `git init` + 首次提交 `025ad8a`（67 个文件：源码 + docs + research，**排除构建产物**）
- `.gitignore`：排除 `app/build`、`.gradle`、`*.apk`、`local.properties`、`*.cookie`、`session*.json`
- 建立本 `DEVLOG.md`，补记轮次 0–6
- 建立 [`AGENTS.md`](AGENTS.md)：写明每个 agent 必须读日志、写完追加
- 更新 `docs/11-交接文档.md`：加 DEVLOG 索引、更新「下一步」
- 检查过没有凭证入库（`docs/12` 里的 `CASTGC=TGT-...` 是省略号）

**提交**：`025ad8a`（基线 67 文件）→ `e68173a`（日志与规范）→ `d6f1c54`（交接文档）

**注意**：提交前确认过仓库里没有 build 产物和任何真实凭证。

---

<!-- 新的一轮请从这里往下追加，格式参考上面各节 -->

## 轮次 8 · 2026-11-21 21:16 → 21:40 · 课表周视图（P0）

**用户需求**：以接棒开发者身份接手，按 `AGENTS.md` 的下一步优先级推进。本轮取 **P0 课表周视图**。

**做了什么**

- 新增 `WeekGrid.kt`（纯逻辑，不依赖 Android）：
  - 按周切成网格；周末列判定（整学期有没有周六周日的课，有才画 7 列）
  - **同天冲突分栏**（区间图贪心着色），跨天绝不一起算
  - `WeekGeometry`：节次 → dp 的三个几何函数
  - `weekRange` / `maxPeriod` / `periodCount`、周次压缩显示 `compressWeeks`
- 新增 `WeekScreen.kt`（Compose）：节次轴 + 5/7 列 + **绝对定位课程块**（`offset` + `height` 由节次算出）
  - 周切换（‹ › / 回到本周），周次默认**跟随今天**，手动翻走后停住（`pinnedWeek`）
  - 底部详情卡（课名/节次/起止/教室/教师/班级/周次），点块开关
  - 左侧 3dp 实色竖条做课程色标识
- 新增 `WeekGridTest.kt`：**59 个单测**（网格 38 + 压缩 9 + 清洗 5 + 锚点 7）
- `Timetable.kt` 新增 `validateFirstWeekMonday`（必须是周一）与 `cleanFieldText`（`"null"` 归一）
- `TimetableStore.decode`、`JwApi.parseCoursesJson` 的文本字段过 `cleanFieldText`（**老缓存自愈**）
- `HomeViewModel`：`HomeState` 加 `sessions/slots/firstWeekMonday/pinnedWeek`，加 `selectWeek`/`shiftWeek`，
  `setFirstWeekMonday` 改成返回校验错误
- `HomeScreen`：主卡与「今天」卡可点进周视图，顶部加「课表」入口
- `MainActivity`：`Page` 加 `WEEK`（四页）
- **`TestTimetable.kt` 夹具校准**：与平板实机缓存比对，发现 3 处偏差，修到 34/34 零差异
- 单测 36 → **95 个**，全绿

**关键发现**

1. 🔴 **`org.json` 的 `optString` 对 JSON null 返回字符串 `"null"`**。
   真机上「思想政治理论课Ⅰ」的教室显示成 `null`。而且**教务返回的 `jx0601mc` 正常就是 null**
   （思政第 12–15 周没排教室），所以这不是脏数据防御，是主路径。
   修法：`cleanFieldText` 整串匹配（**不能 contains**，否则 `nullroom` 这类正常词被误杀），
   解析层与缓存解码层都过一遍，老缓存不用重拉就自愈。

2. 🔴 **测试夹具与真实数据有偏差，而它号称"由真实课表生成"**。
   拿平板 `files/timetable.json`（34 条）逐条比对，3 处不一致：
   - 人工智能概论 周一 9–11：真实是**两条**（`4-10,13,14` + `11,12`），夹具合成一条 `4-14`
   - 思政 周三 3–4：真实**缺第 10 周**（国庆调课），夹具写成 4–17 连续
   - 思政 周二 5–6（良东150教，仅第 12 周）：夹具**完全漏了**
   已校准并补断言（`第10周周三没有思政课`、`人工智能概论拆成两段周次后仍只画一块`）。
   **教训：夹具是真实数据的子集 ≠ 夹具是齐全的。**

3. 课程色不能只靠淡背景区分：暖白纸面风格下 `alpha=.13` 让「中医基础学（琥珀）」和
   「正常人体学Ⅰ（红）」几乎同色。加 3dp 实色竖条后才一眼能认。

4. 分栏用 `Math.floorMod(hashCode, n)` 而不是 `abs()` —— `Int.MIN_VALUE` 的 `abs` 溢出成负数，直接下标越界。

**坑**

- **平板限宽 640dp 对课表是错的**。首页是卡片流，640dp 合理；课表是横向密集内容，
  在 3408px 平板上两边空出大半屏、7 列被挤成细条。周视图单独用 1100dp。
- **`verticalScroll` 上不能加 `fillMaxSize()`**：会把高度钉死成视口高度，内容滚不动。
  要 `fillMaxWidth()` 让内容自然高度。
- **`const val` 不能是 `Dp`**（`AXIS_W = 40.dp`）→ 只能 `private val`。
- `remember(week) { picked = null }` 里做副作用赋值不对，改 `LaunchedEffect(week)`。
- 锚点错误文案里写了 Markdown `**周一**`，Compose 不解析，真机上直接显示星号 → 去掉。
- 模拟器注入课表缓存：`run-as cp /sdcard/t.json` 会 `Permission denied`（scoped storage），
  必须走 `/data/local/tmp/t.json`。

**没做的 / 未验证**

- **周次锚点自动化没做**。只做了手动设置（校验周一 + 当场拦截非法值）。
  换学期仍需要人去点一下，否则周视图整张表全错。
- **周次锚点的手动流程**：21:45 补验通过 —— 把锚点改成 `2026-09-14`（下一个周一）后
  周次从 11 变成 10，第 10 周周三的思政课**如期消失**（正好命中本轮新加的断言
  `第10周周三没有思政课`）、周二出现单节的大学英语；`shared_prefs` 里确认写入了
  `first_week_monday">2026-09-07`（已改回原值）。两个方向（拒绝非法 / 保存合法）都有证据。
- **周末 7 列的手机布局未实测**：岐黄班整学期没有周末课，所以一直走 5 列分支。
  有周末课时的窄屏表现只有推断，没有证据。
- **课表块颜色的跨课区分度未做量化**：加左侧实色竖条后肉眼可辨，但没有量化的色差指标。
- **键盘弹出会挤压周视图**（手机改锚点时实测）：保存按钮仍可见可点，但网格被压掉下半屏。
  未做 `imePadding` / `adjustResize` 处理。
- **调课/停课通知未处理**：教务里存在但没有接口，周视图只反映静态排课。
- 罗马数字 `Ⅰ`（U+2160）在部分字体下渲染得像 `I`/`！`，未做字形替换（不是 bug，是字体）。
- 未提交过任何写操作（本轮全是只读 + 本地计算）。

**产出**

- 新文件：`app-android/app/src/main/java/com/kang/ubcm/WeekGrid.kt`、`WeekScreen.kt`
- 新测试：`app/src/test/java/com/kang/ubcm/WeekGridTest.kt`（59 个）
- 改动：`Timetable.kt`、`TimetableStore.kt`、`JwApi.kt`、`HomeViewModel.kt`、`HomeScreen.kt`、`MainActivity.kt`、`TestTimetable.kt`
- 新文档：[`docs/14-课表周视图.md`](docs/14-课表周视图.md)
- 更新：[`docs/11-交接文档.md`](docs/11-交接文档.md)（索引 + 下一步）、[`AGENTS.md`](AGENTS.md)（坑表 9 → 12 条）
- 真机截图：`~/Desktop/data/截屏/ubcm-week-0{1..5}-*.png`（平板）、`ubcm-week-1{0,1}-*.png`（手机）

