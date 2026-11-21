# 08 · API 返回字段格式化规范（数据契约）

> 采集方式：复用登录态，在页面上下文用 `Authorization: Bearer <JWT>` 直连 `/apim/sems-tp-nup/**`，逐个接口记录**原始响应**（未做任何清理）
> 采集时间：2026-11-19 21:00–21:10　原始样本：`research/raw/api-samples/envelopes.json`（29 接口）、`paging.json`（8 接口）
> 结论基于实测，不是推测。凡未验证的均标注「未验证」。

---

## 0. 一句话

**这不是一套"有规范"的 API**。门户后端是 Java（Spring Boot + PageHelper），响应包装有 **4 套并存**，字段类型以**字符串**为主，复合值用**逗号串**和**字符串里套 JSON** 表达。
客户端必须写**防御式解析**，不能假设任何字段的类型稳定。

---

## 1. 传输层规范（这一层是稳定的）

| 项 | 规范 |
|---|---|
| 协议 | HTTPS（`https://i.bucm.edu.cn/apim/sems-tp-nup/...`） |
| 方法 | **全部 POST**（GET 到 POST 接口 → HTTP 500） |
| Content-Type | `application/json` |
| Body | JSON 对象；无参数也要传 `{}`（传 null 会异常） |
| 鉴权 | `Authorization: Bearer <accessToken>` |
| 公开接口 | 少数不需要鉴权（实测：`/bucm/fw/getUnitType` 无凭据返回 200） |
| 网关前缀 | `/apim/sems-tp-nup` |
| 响应 Content-Type | `application/json;charset=UTF-8` |

**注意**：
- 服务端**不认** `Cookie: accessToken`（实测被拒），也**不认** `access_token` 自定义头。
- 未带凭据时 **HTTP 仍是 200**，靠 body 里的 `code` 判断（这是最容易写错的地方）。

---

## 2. 响应包装：4 种形态并存（核心发现）

### 形态 A：标准成功（21/29 接口）

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": <任意>,
  "errorData": null
}
```

- `code`：**number**，不是字符串
- `msg`：中文提示
- `errorData`：正常时为 `null`，异常时可能是对象
- `data`：**类型不定**（见第 3 节）

### 形态 B：业务失败（同一套壳，code 非 200）

```json
{"code": 400, "msg": "操作失败", "data": null, "errorData": null}
```

### 形态 C：服务端异常（**键名变了**）

```json
{"code": 500, "message": "系统异常"}
```

- ⚠️ 这里 `msg` **变成了 `message`**，且**没有 `data` / `errorData`**
- HTTP 状态码可能是 **200，也可能是 500**（实测：`getTaskInfo` 缺参数时 HTTP=500；`/card/weather` 正常时 HTTP=200）
- **客户端不能只判断 `code`，也不能只判断 HTTP 状态**

### 形态 D：未鉴权

```json
{"code": 0, "msg": "未传递 access_token或Authorization 头信息", "data": null}
```

- HTTP = 200
- `code` 是 **0**，不是 401
- 这是客户端判断"登录态失效"的唯一可靠信号（配合本地 `exp` 时间）

### 形态 E：框架级 404（Spring Boot 默认体）

```json
{"timestamp":"2026-11-19T13:05:08.485+00:00","path":"/apim/sems-tp-nup/nonexist/foo",
 "status":404,"error":"Not Found","message":null,"requestId":"9ff5..."}
```

- 请求路径拼错时出现，与业务无关
- HTTP = 404

### 形态 F：裸返回（没有包装！）

| 接口 | 返回 |
|---|---|
| `/bucm/oaemail/haveRedPoint` | `0`（裸数字） |
| `/getToken` | `"4f04fde8-c873-4eed-b3ff-cd8def62881b"`（裸字符串） |
| `/bucm/oaemail/getInboxHome` | **直接返回 PageHelper 分页对象**（无 code/msg/data） |

> 这三处是"包装缺失"的坑，客户端要按接口特判。

---

## 3. `data` 的类型谱（必须在客户端做联合类型）

实测到一个接口里的 `data` 可以是 **7 种**：

| 类型 | 实例 | 说明 |
|---|---|---|
| `object` | `/homePage/sceneData`、`/card/weather/getWeatherAndDate` | 常规 |
| `array` | `/home/getHomeCarousel`、`/bucm/appmarket/getBnuRecommendApp` | 常规 |
| **`object of arrays`** | `/bucm/fw/getLevelOneData` | 键是分类 ID `"1".."8"`，值是数组（**动态键**） |
| `string` | `/getToken` | 裸 UUID |
| `number` | `/bucm/oaemail/haveRedPoint` → `0` | — |
| `boolean` | `/bucm/appmarket/getToConsoleQx`、`/bucm/message/getsendMsgQX`、`/bucm/schedule/scheduleCenter/queryMenuPerm` | `true/false` |
| `null` | `/bucm/fw/getRecentQuery`、`/bucm/calendar/getHasActiveDate` | 无数据时是 `null` 不是 `[]` |

**建模建议**：`data` 用泛型 + `Any`，各接口单独声明期望类型，解析失败降级为"无数据"，**不要全局强转**。

---

## 4. 分页规范（PageHelper）

凡是列表型接口（消息、校园信息、问答、邮箱）都带分页，参数与返回统一：

### 4.1 请求参数

```json
{
  "pageNum": 1,
  "pageSize": 10,
  "start_date": "", "end_date": "",
  "msg_content": "", "tab_type": "message_all",
  "type_id": "", "APP_ID": "", "is_read": ""
}
```

- `pageNum` **从 1 开始**（不是 0）
- `pageSize` 默认 10；传 5 生效，未见上限
- 空字符串 `""` 表示"不限"，**不要传 null**
- `tab_type` 是业务枚举（`message_all` / `message_remind` …）

### 4.2 响应结构（PageHelper 标准 18 字段）

```json
{
  "total": 118353, "list": [...],
  "pageNum": 1, "pageSize": 10, "size": 10,
  "startRow": 1, "endRow": 10, "pages": 11836,
  "prePage": 0, "nextPage": 2,
  "isFirstPage": true, "isLastPage": false,
  "hasPreviousPage": false, "hasNextPage": true,
  "navigatePages": 8, "navigatepageNums": [1,2,3,...],
  "navigateFirstPage": 1, "navigateLastPage": 8
}
```

- 这 18 个字段**全是 snake/camel 混排**（`pageNum` 驼峰、`startRow` 驼峰、`navigatepageNums` 全小写混）
- 空列表时 `list: []`、`total: 0`、`pages: 0`
- ⚠️ `getInboxHome` 直接把这一坨当顶层返回（无包装）

**实测数据量**（可作为产品判断依据）：
| 接口 | total |
|---|---|
| 消息提醒 | **118,353** |
| 校园信息 | 33,748 |
| 校务问答 | 8,682 |
| 待办 | 0（新生无待办） |

---

## 5. 字段命名与类型规范（以 163 条应用为样本的实测统计）

### 5.1 命名风格

| 风格 | 占比 | 例子 |
|---|---|---|
| **全大写下划线** | 主体 | `APP_NAME`、`BELONG_UNIT_ID`、`PC_ACCESS_ADRESS` |
| 驼峰 | 个别 | `children`、`name`、`opebutton`、`pageNum` |
| 小写下划线 | PageHelper 分页 | `is_calendar_manager` |

> ⚠️ `PC_ACCESS_ADRESS` / `MOBILE_ACCESS_ADRESS` 是**拼写错误**（ADRESS 少一个 D），照抄，不要"修正"。

### 5.2 值类型（47 个字段的统计）

| 类型 | 字段数 | 说明 |
|---|---|---|
| `string` | **41** | 包括本该是数字、布尔、数组、时间的值 |
| `number` | 3 | 仅 `IS_COLLECTION`、`APP_ORDER_ID`、`ORDER_ID` |
| `null` 占多数的字段 | 8 | 100% 为 null（`COLLECT_LOGO`、`TODO_KEY`、`GROUP_NAME`…） |

**典型"类型失真"**：

| 字段 | 实际值 | 本该是 |
|---|---|---|
| `AD_WIDTH` | `"1661"` | number |
| `OPEN_TYPE` | `"1"` | enum |
| `IS_MOBILE_SHOW` | `"0,1"` | **boolean 数组** |
| `DIMENSIONS` | `"1,213977042944,2186985109524480"` | **number 数组** |
| `ISLOCK` / `IS_RECOMMENDED` / `AUTH_METHOD` | `"0"` / `"1"` | boolean |
| `PC_ACCESS_ADRESS` | `"{\"type\":\"1\",\"url\":\"...\"}"` | **对象（双重序列化）** |
| `CREATE_TIME` | `"2026-11-02T01:41:41.000+0000"` | timestamp |

### 5.3 布尔与多值的表达

```
IS_MOBILE_SHOW: "0,1"      → PC 显示 + 移动显示
IS_MOBILE_SHOW: "0"        → 仅 PC
是否类字段统一 "0"/"1"，不是 true/false
多值统一逗号分隔，不是 JSON 数组
```

### 5.4 时间格式

```
"2026-11-19T08:34:28.000+0000"   ← Java Date.toString()，UTC，+0000 无冒号
"2026-11-19T16:00:32.000+0000"
```

- **是 UTC 时间**，客户端展示要 +8
- 不是标准 ISO8601（时区 `+0000` 没有冒号）
- iOS `ISO8601DateFormatter` 默认解析会失败，需要自定义格式 `yyyy-MM-dd'T'HH:mm:ss.SSSZ`
- 另有纯展示用的字段：`"2026年11月19日 星期四"`、`"丙午马年十月十一"`（后端已格式化好，直接显示）

### 5.5 图片与相对路径

```
ICON:      "./resource/defaults/image/appTypeIcon/icon/ic_xxx.png"
APP_LOGO:  "./resource/defaults/image/appIcon/app_4.png"
AD_IMG:    "uploadfiles/ad/700200/2025/11/21/1763715716941.jpg"
```

- 相对路径，**必须拼前缀**：`https://i.bucm.edu.cn/tp_nup/` + 路径
- 有 `AD_IMG` 与 `AD_IMG_MOBILE` 成对出现（移动端要优先取 `_MOBILE`）

### 5.6 动态键（不能当固定结构解析）

| 接口 | 动态键 |
|---|---|
| `/bucm/fw/getLevelOneData` | `data` 的键是分类 ID `"1".."8"` |
| `/homePage/sceneData` | `SCENE.WIDGET` 的键是雪花 ID（`"2653820598407168"`） |
| `/bucm/appmarket/getMySubscribeApp` | `SORT_APP_LIST` 的键是中文分类名 |

### 5.7 ID 规范

- 业务 ID 统一为**长整型字符串**（雪花算法）：`"7228921016320"`、`"18844299227123712"`
- 别用 `Int` 装，iOS 用 `String`，Android 用 `String`（避免 2^53 精度丢失）
- 部分 ID 带后缀：资源 ID `"939ab98f5911497ba44deb8bf31ff153FW"`（32 位 hex + `FW`）、日历 `"20260000000R"`

---

## 6. 业务码与错误处理

| code | HTTP | 含义 | 客户端动作 |
|---|---|---|---|
| `200` | 200 | 成功 | 正常渲染 |
| `400` | 200 | 参数/业务失败 | 提示 `msg`，不重试 |
| `500` | 200 或 500 | 服务端异常（含参数缺失） | 提示"服务暂时不可用"，可重试 |
| `0` | 200 | 未鉴权 | **触发重新登录** |
| — | 404 | 路径错误（Spring 默认体） | 开发期问题，不提示用户 |

**判定顺序（务必按此实现）**：

```
1. HTTP != 200 且 body 无 code  → 网络/网关错误
2. body 无 code 字段（裸返回）    → 按接口特判
3. code == 0                     → 登录失效 → 静默续期
4. code != 200                   → 业务失败，展示 msg
5. 否则                          → 解析 data
```

---

## 7. 各接口 `data` 结构速查表

| 接口 | data 类型 | 关键字段 |
|---|---|---|
| `/homePage/sceneData` | object | `LANGUAGE`、`SCENE.TEMPLATE[]`、`SCENE.WIDGET{动态键}` |
| `/home/getHomeCarousel` | array | `AD_IMG`、`AD_IMG_MOBILE`、`AD_TITLE`、`OPEN_TYPE` |
| `/bucm/appmarket/getBnuRecommendApp` | array | 应用对象（同下表） |
| `/card/weather/getWeatherAndDate` | object | `WEATHER`(null)、`CURRENT_DATE.{HOUR,TODAY,LUNAR_CALENDAR,WEEK_DAY}` |
| `/bucm/fw/getLevelOneData` | **object of arrays** | 键 `"1".."8"`，值 `[{CATEGORY_ID,BELONG_UNIT,RESOURCE_ID,BELONG_UNIT_ID,APP_NAME,CATEGORY_NAME,PC_ACCESS_ADRESS}]` |
| `/bucm/fw/getUnitType` | array | `UNIT_TYPE`、`name`、`children[]{UNIT_ID,OFFICE_NAME}` |
| `/bucm/appmarket/getMySubscribeApp` | object | `APP_LIST[]`、`SORT_APP_LIST{}`、`LETTER_LIST`、`PROJECT_NAME` |
| `/appMarket/getAppDimension` | array | `RESOURCE_ID`、`NAME`、`CATEGORY_NAME` |
| `/work/getServiceUnitName` | array | `UNIT_ID`、`UNIT_NAME` |
| `/task/getTaskInfo` | **PageHelper** | 见第 4 节；`list[].MSG_*` |
| `/bucm/campusBucm/getBnuCampusPimPageInfo` | **PageHelper** | `list[].{PIM_ID,TIME,IS_NEW,TYPE_ID,BELONG_UNIT_NAME}` |
| `/bucm/ask/getTotalObjList` | **PageHelper** | `list[].{ASK_ID,ASK_TITLE,ASK_CONTENT,ANSWER_CONTENT,USER_NAME,CREATE_TIME}` |
| `/up/calendar/getMineCal` | array | `RESOURCE_ID`、`CAL_NAME`、`CAL_COLOR`、`CAL_TYPE`、`SHARE_TYPE`、`CREATTIME` |
| `/up/calendar/getCalendarManger` | object | `is_calendar_manager`、`is_calendar_share`（**字符串 "true"/"false"**） |
| `/bucm/appmarket/getToConsoleQx` | boolean | — |
| `/getToken` | string | UUID |
| `/bucm/oaemail/haveRedPoint` | number | 裸值 |

**应用对象（163 条的公共结构）**：

```json
{
  "APP_NAME": "教务系统",
  "BELONG_UNIT": "教务处（教材建设中心）",
  "BELONG_UNIT_ID": "900110",
  "PROJECT_NAME": "教学科研",
  "IS_MOBILE_SHOW": "0,1",
  "MOBILE_ACCESS_ADRESS": "https://jw.bucm.edu.cn/njwhd/login/wechatcp",
  "PC_ACCESS_ADRESS": "http://jw.bucm.edu.cn",
  "APPID": "7228921016320",
  "APP_ID": "7228921016320",
  "AUTH_METHOD": "1",
  "IS_SECOND_CHECK": "0",
  "IS_RECOMMENDED": "0",
  "ICON": "./resource/defaults/image/appTypeIcon/icon/ic_xx.png",
  "APP_LOGO": "./resource/defaults/image/appIcon/app_4.png",
  "CREATE_TIME": "2026-11-02T01:41:41.000+0000"
}
```

---

## 8. 教务系统（另一套体系，非 JSON）

教务 `jw.bucm.edu.cn/jsxsd/**` **返回 HTML，不是 JSON**，需要 DOM 解析。

- 页面结构：查询壳页 → 结果页（`xxx_frm` → `xxx_query` → `xxx_list`）
- 课表数据在 `table.layui-table` 中**行数最多**的那张表
- 列序（10 列，实测）：`课程名 | 地点 | 学分 | 属性 | 课程性质 | 教师 | 时间 | 内部ID | 课程编号 | 课程群`
- 时间列格式：`"4-10,13-14周 星期一1091011节"`
  - 前半段：周次（逗号分隔或区间）
  - 后半段：**第一位是星期，之后每两位一个节次**（`1091011` = 周一 第 09、10、11 节）
- 无 `viewport`，无 JSON 接口，**HTML 结构改版即失效**

**登录要注意（实测验证）**：
- 教务**吃 CAS 单点**，但入口不是直接访问 `/jsxsd/`（会落到强智自己的登录页）
- 正确入口：`https://cas.bucm.edu.cn/cas/login?service=https://jw.bucm.edu.cn/` → 有 CASTGC 时自动回跳到 `xsMain_bzy.html` 并建立 `JSESSIONID`
- 客户端应：登录后静默访问一次这个 URL，再读 `jw.bucm.edu.cn` 域下的 `JSESSIONID`

---

## 9. 客户端建模清单（照着写就行）

1. **统一响应解析器**：先判 HTTP，再判 body 形状（有无 `code`），再判 `code` 值。**不要用 `data class ApiResponse<T>(code:Int, msg:String, data:T)` 一把梭** —— `message`/`data` 缺失会让反序列化直接抛异常。
2. **所有数值字段先用 `String` 接**，需要时再 `toIntOrNull()`。
3. **`"0,1"` 这类多值**写扩展函数 `String.toFlags(): List<String>`。
4. **`PC_ACCESS_ADRESS` 二次 parse**：`Json.decodeFromString<AccessAddress>(str)`，失败则降级为"直接当 URL 用"。
5. **时间**：自定义 `yyyy-MM-dd'T'HH:mm:ss.SSSZ`，并强制按 UTC 处理再转本地。
6. **相对路径**统一 `UrlResolver(base = "https://i.bucm.edu.cn/tp_nup/")`。
7. **分页**封装 `Page<T>(total, list, pages, ...)`，`pageNum` 从 1。
8. **登录失效**只认 `code == 0`，全局拦截器统一处理，避免各页面各弹一次。
9. **超时与重试**：实测接口响应 100–900ms；批量接口（服务分类 327KB / 应用清单 410KB）要给 10s 以上超时。
10. **字段兜底**：8 个字段 100% 为 null，UI 层不要依赖它们。

---

## 10. 未验证 / 待补

- [ ] 写操作接口（选课、报名、提交）的请求体规范 —— 本轮只读，未采集
- [ ] 大文件/附件下载接口（`uploadfiles/...`）
- [ ] 附件预览接口
- [ ] 搜索接口（`searchStr` 参数在各列表接口的行为）
- [ ] 消息已读上报接口（消息中心点开消息时会调用）
- [ ] 移动端专用接口（门户是否对移动 UA 返回不同结构 —— 实测服务端不嗅探 UA）
