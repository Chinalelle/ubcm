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
