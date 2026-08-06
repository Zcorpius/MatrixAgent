# AI OS 如何控制"不配合"的三方应用

> 技术调研与架构推演 · 2026-08
> 适用范围:MatrixAgent / AAOS 车机 Agent 方向设计参考

---

## 0. TL;DR(核心结论)

1. **AI Agent 控制三方 App 有四层技术手段,且是"降级金字塔"**:原子接口 → Deeplink/Intent → View 树(a11y)→ GUI Agent(看屏)。上层优先,下层兜底。**这不是设计构想,而是 2026 年 Google/Apple/国产厂商的实际产品架构。**

2. **"应用不配合就做不了 AI OS"——在手机上成立,在车机上不成立。** 关键差异是**生态强制权**:车机能"逼"应用给接口(协议强制/定制合作/自研替代),手机没这个筹码,且超级 App 会反向对抗自动化。

3. **GUI Agent 是兜底,不是主力,且当前有硬天花板**:单步成功率 ~85%(OSWorld),但**多步长任务只有 20.6%**(OSWorld 2.0)。步骤累积错误是核心瓶颈。

4. **车机 AI OS 的价值地基不在三方 App**(车控/导航/系统/媒体中心),所以即便三方完全不配合,主体仍成立。地基塌了才叫"做不了",长尾体验一般只叫"不丝滑"。

5. **"每个 App 内置 Agent + 系统 MCP 编排"方向是趋势**(Honor/ZTE/StepFun 已在用 MCP),但它的真正卡点是应用方意愿 + 标准化协议 + 安全委托链,长尾仍需 GUI Agent 兜底。**它与 GUI Agent 是叠加,不是替代。**

---

## 1. 问题背景

接入 AAOS 后,Agent 需要操作爱奇艺、Bilibili 这类三方媒体应用。但这类应用:

- 安全性约束不像系统级那么严格,但**应用本身不提供原子方法调用**(没有 `play(videoId)` 这样的 API);
- Agent 只能通过 **screen(屏幕理解)+ input(注入操作)** 来模拟人类操作。

核心矛盾由此而来:**当三方 App 既不提供原子接口、又不主动配合时,AI OS 还能不能驱动它?这个问题在车机和手机上答案完全不同。**

---

## 2. 控制三方 App 的四层技术手段(降级金字塔)

```
① 原子接口(App Intents / MediaSession / 私有 AIDL)   ← 最精确,需应用主动实现
        ↓ 没有
② Deeplink / Intent                                    ← 搭便车,用应用已有的入口
        ↓ 没有
③ WMS View 树(AccessibilityNodeInfo)                 ← 结构化定位,系统侧读
        ↓ 树是黑的(H5/自绘)
④ GUI Agent(截屏 + VLM 看图 + 注入点击)              ← 看屏操作,长尾兜底
```

**优先级原则:上层优先(准、快、便宜),下层兜底(覆盖广但不可靠)。** 这正是 Google 2026 年 *The Intelligent OS* 的官方双路径设计思想(见 §6.1)。

### 2.1 原子接口

应用**主动**暴露的结构化方法,如 `mediaApp.play(MediaId)`、MediaSession、App Intents、ContentProvider。
- 优点:最精确、最快、最便宜;语义封闭(播放就是播放)。
- 缺点:几乎没人会为你的 Agent 单独做。覆盖面最窄。

### 2.2 Deeplink / Intent

Android 本来就有的跳转/动作机制。对 Agent 的价值:**应用方完全不用改代码**,因为这些入口是应用为了网页跳转、推送、分享等目的**已经暴露**的,Agent 在"搭便车"。

- **Intent**:消息对象,描述"想做什么"。`startActivity(intent)` 拉起应用/页面。隐式 Intent(`ACTION_VIEW` + URI)由系统匹配。
- **Deeplink**:通过 URI 直接跳到应用内某页面。两种形态:
  - Custom scheme:`iqiyi://video/xxx`、`bilibili://video/BV1xx`
  - App Links(HTTP + 域名验证):`https://m.bilibili.com/video/BV1xx`(更安全,Google 主推)

| 特性 | 说明 |
|---|---|
| 覆盖面 | 头部应用基本都有,但只能到"页面级" |
| 局限 | 不能做页面内细粒度操作(开弹幕/投屏/倍速没有对应 URI);scheme 无公开契约,版本更新会变 |

**同一个请求走三层的对比**——"播放《狂飙》第 5 集":

| 层级 | 做法 | 需要应用配合 |
|---|---|---|
| 原子接口 | `play(MediaId("kuangbiao_ep5"))` 秒回 | ✅ 需主动实现(几乎不存在) |
| Deeplink | 构造 `iqiyi://...` → `startActivity` 跳落地页默认开播 | ❌ 用应用已有入口 |
| GUI Agent | 拉起 → 截屏 → 找搜索框 → 点 → 输入 → 找第 5 集 → 点 | ❌ 但每步都可能失败 |

### 2.3 WMS View 树(AccessibilityNodeInfo)

工程上能拿到的"View 信息"通道不是 WMS 本身(WMS 在 `system_server`,普通进程拿不到),而是 **AccessibilityService 暴露的 `AccessibilityNodeInfo` 树**(等价于 `uiautomator dump`,底层也是 a11y)。

一棵 a11y 树里每个节点可拿到:`bounds`(像素坐标)、`text`、`contentDescription`、`resourceId`、`className`、`isClickable`、parent/children —— **等于不用猜就知道按钮在哪、叫什么**。

**为什么读 View 树 > 看截图**:

| 维度 | 读 View 树(a11y) | 看截图(VLM) |
|---|---|---|
| 定位精度 | 接近 100%(直接拿坐标) | 依赖识图,会偏 |
| 延迟 | dump 几百 ms | VLM 推理数秒 |
| 成本 | 本地解析,近乎免费 | 每步调一次 VLM |
| 语义 | text/label 现成 | 要 VLM 读字 |
| 看见"视觉状态" | 弱(选中/禁用/loading 不一定反映) | 强 |

**结论:正经的车载 GUI Agent,第一选择永远是读树,VLM 是兜底。**

**View 树路线的硬伤(直接关系爱奇艺/B站能不能用)**——a11y 树依赖应用"正确暴露语义",而很多渲染方式不暴露:

- **WebView / H5**:a11y 树里往往只有一个 WebView 空节点,DOM 不可见。**B站、爱奇艺大量页面是 H5**,正是 view 树最容易失效的地方。
- **Flutter / Compose 自绘**:需应用主动 merge 到系统 a11y,缺失常见。
- **游戏 / 全自绘 / SurfaceView**:树里只剩一个 SurfaceView 节点,**完全黑盒,只能纯视觉**。

**两个工程坑**:
1. **resourceId 不能信**:三方 release 版会混淆 resource-id(`id/abc_123`),版本一更新就变。长期定位要用 `text` + 结构路径 + 语义。
2. **AAOS 上 a11y 是前置依赖**:读树和 input 注入(`dispatchGesture`)**是同一个 a11y 通道**——一旦在 AAOS 上打通 a11y,两者一起解决,成本摊销。这是性价比最高的工程切入点。

### 2.4 GUI Agent(看屏 + 注入)

最后兜底。看屏(截屏 + VLM 理解)+ 注入(a11y dispatchGesture / adb / 系统签名)模拟人类点击。可靠性见 §6.4。

### 四层对比总表

| 层级 | 需要应用做什么 | 可靠性 | 覆盖面 | 延迟/成本 |
|---|---|---|---|---|
| ① 原子接口 | 主动实现结构化方法 | 最高 | 最窄 | 最低 |
| ② Deeplink/Intent | 无需(用已有入口) | 中 | 中 | 低 |
| ③ View 树 | 无需,但依赖正确 a11y 标注 | 高(树可用时) | 受 H5/自绘限制 | 低 |
| ④ GUI Agent | 无需 | 低(多步尤甚) | 最广 | 高(VLM) |

---

## 3. App 的真实技术形态:hybrid 马赛克

"这个 App 是不是 H5?"是个**错误的问题**。真实情况是:**主流 App 都是 hybrid 混合的,native 和 H5 拼成"马赛克",不是一整块材料。** 对 Agent 意味着:a11y 树在同一个 App 里**有的区域能读、有的区域是黑的**,要按区域切换,不是按 App 切换。

### 3.1 一个 App 内部的典型分工(以爱奇艺 / B站为例)

| 区域 | 通常技术 | a11y 树可读 |
|---|---|---|
| 启动页、登录、底部 tab、首页框架 | Native | ✅ |
| 播放器(解码、进度条、手势) | Native(性能必须) | ✅ |
| 首页推荐流、视频列表 | 常为 native 列表/自绘 | ✅(大多) |
| **视频详情页、评论、个人中心、会员中心、活动页、广告** | **H5** | ❌ 黑的 |
| 小程序/活动容器 | WebView | ❌ 黑的 |

**关键**:同一个任务(如"播放某剧"),入口(搜索框、播放)往往是 native,但点进去之后的详情页、选集、评论很可能就是 H5。**前半段 view 树能走,后半段突然失效,必须切视觉。** 这就是为什么 hybrid(读树优先 + 视觉兜底)不是可选项而是必选项。

### 3.2 实际上至少四类技术形态

严格讲不是 native vs H5 二分,而是至少四类,每类对 view 树友好度不同:

1. **Native**(传统 View / Compose 正确标注)
2. **H5 / WebView**
3. **跨端框架**(Flutter 自绘、React Native 映射)
4. **纯自绘 / SurfaceView**(游戏、特效 UI,彻底黑盒)

### 3.3 如何快速判断一个页面的技术形态(实操)

1. **开发者选项 → 显示布局边界**:native 控件显示粉色/绿色边框线;某块区域完全无线、光秃秃 → H5 或自绘。最直观。
2. **`uiautomator dump` / a11y 树**:节点密 → native;整页只有几个大节点、下面全空 → H5/WebView。
3. 树里出现 `WebView` 节点 → 该块是 H5。

---

## 4. 车机 vs 手机:生态强制权的根本差异

这是理解整个问题的**钥匙**。

> 车机 AI OS 比手机 AI OS 多了一件手机没有的武器:**生态强制权**。它不是"等"三方提供原子接口,而是能"逼"三方提供,或自己造一个。

### 4.1 车机的三件武器

| 武器 | 做法 | 例子 |
|---|---|---|
| **协议强制** | 用"上架/接入权"换"接口开放" | AAOS 要求媒体 App 想进媒体中心**必须实现 `MediaBrowserService`**;不实现就不让进 |
| **定制合作** | 和应用方谈定制版,接口是谈出来的 | 车机版爱奇艺、车机版 QQ 音乐,接车厂私有接口标准 |
| **自研替代** | 核心高频场景干脆自己做 | 蔚小理/华为/小米自研音乐、电台、播客 |

> 手机 OS 做不到强制微信开放接口(微信敢硬刚苹果),因为议价权在超级 App 手里。车机则是车厂拿捏应用。

### 4.2 手机为什么被卡死

三件武器在手机上逐个失效:

- **协议强制**:手机没筹码,超级 App 反向议价(微信硬刚苹果打赏抽成/暗黑模式)。
- **定制合作**:手机没人给你做定制版。
- **自研替代**:替代不了微信/抖音/支付宝。

而且手机有车机几乎不存在的**对抗性环境**:

1. **超级 App 主动对抗自动化**:微信/抖音/银行 App 检测 a11y 自动点击、检测 root、用 `FLAG_SECURE` 禁截屏。
2. **App Intents / Shortcuts 冷启动死循环**:应用没动力接入 → 用户没的用 → 更没人接入。
3. **安全敏感度高**:车机误触是"播错剧",手机误触可能是"转错账/发错群"。iOS 直接在 App Store 条款禁止三方 a11y 自动化,只有 Apple 自己的 Siri 能用。

### 4.3 同一个 GUI Agent,车机是兜底,手机是主力

这是最关键的对比:

- **车机**:协议 + 定制 + 自研拿到大部分接口 → GUI Agent 只兜底长尾 → 体验可控。
- **手机**:拿不到头部接口 + app 反对抗 → **GUI Agent 被迫当主力** → 体验直接被 GUI Agent 可靠性天花板拖累。

国产厂商 2024–2025 推的"AI 帮你点"(荣耀 YOYO、vivo 蓝心、小米 HyperAI、OPPO 小布),本质都是**因为没拿到接口、只能走 GUI Agent**,不是不想用接口。逻辑和车机相反。

### 4.4 "应用不配合就做不了吗"——精确回答

把"做不了"拆开:

| 目标 | 应用不配合时 |
|---|---|
| 做一个车机 AI OS(整体) | ✅ **做得了**——地基在系统层 |
| 对三方视频 App 深度操作(选集/投币/会员)丝滑 | ⚠️ 只能 GUI Agent 降级 |
| 对所有三方 App 都丝滑 | ❌ 这个确实做不了 |

**车机 AI OS 的价值地基不靠三方 App**:

| 车机 AI OS 核心价值 | 依赖三方配合吗 |
|---|---|
| 车控(空调/车窗/座椅/驾驶模式/氛围灯) | ❌ 100% 可控 |
| 导航、路线、充电规划 | ❌ |
| 系统设置、多模态(语音/视线/手势) | ❌ |
| 自研音乐/电台/播客 | ❌ 车厂自研 |
| 三方音乐/音频(媒体类) | ❌ MediaSession 协议强制 |

> **>80% 的价值地基完全不依赖三方 App。** 开车看爱奇艺/刷抖音本就是低频受限场景,三方视频是锦上添花,不是地基。地基塌了才叫"做不了",长尾体验一般只叫"不丝滑"。
>
> **活证据**:理想、小鹏、问界鸿蒙座舱的三方 App 生态都不完整,应用基本不配合做深度定制,但 AI OS 照样成立、照样卖得好。

**注意一个被忽视的等式**:很多人默认"AI OS 的价值 = 能丝滑控制所有三方 App"。这个等式在**手机**上成立(高频全在三方 App),所以手机被卡死;在**车机**上**不成立**(高频在系统层),所以车机不受影响。破了这个等式,所谓"绊住脚"的质疑就化解了。

---

## 5. 前沿方向:应用 Agent + MCP 编排

### 5.1 思路与价值

每个 App 内部自己做一套应用 Agent(最懂自己的功能,用一等公民内部接口),系统 Agent 只做意图理解 + 任务拆分 + 路由分发。这是经典 **orchestrator-worker** 多智能体架构。

价值:**绕开所有 GUI Agent 痛点**——不用看屏、不用对抗 a11y 黑盒、不怕 UI 改版,因为应用 Agent 用的是应用内部私有接口。职责分层也干净。这正是 **MCP(Model Context Protocol)** 标准化的方向,也是华为"元服务"、各家"应用服务化"的思路。

### 5.2 真正的难点(不在架构,在这四点)

1. **应用方接入意愿**:比 App Intents 更难的冷启动死循环。让应用暴露几个离散 action 都没动力,让它自己写一个完整 agent 成本高一个数量级。**这和手机困境同根,是商业问题不是技术问题。**
2. **标准化协议缺失**:每个应用 Agent 说"方言",系统怎么编排?需 MCP 这样的协议。但 **MCP 现状偏桌面/云端的工具/数据源暴露,远没覆盖"每个手机 App 内置自主 agent"**——手机进程隔离、生命周期、权限模型都对不上 MCP server 模式。
3. **安全委托链**:系统把意图委托给应用 Agent 执行,应用 Agent 要代表用户操作(登录/付费/读数据)。授权边界、转授权信任模型、出 bug 谁担责,目前无成熟方案。
4. **长尾应用不会做 agent**:头部可能配合,长尾绝对不会,GUI Agent 仍是必须的 fallback。

### 5.3 它是叠加,不是替代

在降级金字塔最顶上加一层,**优化头部体验,但不消灭 GUI Agent**:

```
① 应用 Agent(一等公民,最懂自己)   ← 优先级最高,谁接入谁丝滑
   ↓ 没 agent / 失败
② 原子接口 / Deeplink / Intent
   ↓
③ View 树(a11y)
   ↓
④ GUI Agent(看屏)兜底长尾          ← 永远需要
```

### 5.4 行业坐标

- **AppFunctions(Android 官方,2026)**:本节主角,见 §6.1。官方定位"**on-device MCP servers**"——每个 app 向 OS 注册可被 agent 编排的工具,**本地执行**(对比标准 MCP server 走云端 + 网络往返)。Android 16+ 实验 API,Gemini 集成仍在私有预览。
- **MCP(Anthropic, 2024)**:跨平台编排协议,目前偏桌面/云;国产努比亚/StepFun 已在端上使用(见 §6.2)。
- **A2A(Agent-to-Agent)**:agent 间通信协议,努比亚 NaviX 与 MCP 并用。
- **App Actions / App Intents**:这个方向的低配版(暴露离散 action,非 agent),接入率低。
- **鸿蒙元服务 / 小米华为"应用服务化"**:国产在推,走得比国外激进。
- **AI-native App**:下一代 app 内置 agent,概念早期。

### 5.5 关键澄清:MCP 是"编排层",不是"执行层"(必读)

这是最容易产生误解的一点,调研后必须讲清:**MCP / A2A / AppFunctions 解决的是"系统 agent 如何发现、调度、组合能力"(编排层);但底层真正"操作三方 App 屏幕"的执行通道,仍然是 a11y / GUI 树 / 模拟点击(执行层)。**

| 层 | 角色 | 应用不配合时 |
|---|---|---|
| MCP / A2A / AppFunctions | **编排层**(发现、调度、组合应用暴露的能力) | ❌ 没能力可编排 |
| a11y + GUI 树 + 模拟点击 | **执行层**(真正读取并操作屏幕) | ✅ 仍能看屏操作(唯一兜底) |

**结论:应用不配合时,MCP/AppFunctions 这条路同样走不通(它们也需要应用主动实现)。** 所有人——Google、Apple、国产——最终都得回到 a11y + GUI 自动化这条唯一的执行层兜底通道。这正是 §6.2 揭示的:国产嘴上说用 MCP,底层操作三方 App 仍清一色靠 a11y。

因此前文降级金字塔可更精确地表述为:**上面几层(原子接口 / AppFunctions / MCP)全是"编排层",只有最底层 GUI Agent 才是真正不依赖配合的"执行层兜底"。**

---

## 6. 2026 工业现状调研

### 6.1 Google:AppFunctions + Android Computer Control(双路径,最清晰)

Google 在 Android 上的 Agent 设计是**自顶向下四层**,核心是"两条并列能力暴露路径 + Gemini 编排"。

**完整架构分层**([Android AI 总览](https://developer.android.com/ai)):

```
① 编排层      Gemini(端侧 Nano + 云)+ function-calling
              意图理解 / 发现 tools / 跨 app 编排 / 安全确认
② 能力暴露层  ②a AppFunctions(应用主动)= on-device MCP server
              ②b Android Computer Control(系统兜底)= 官方 GUI agent
③ 模型层      Gemini Nano / Gemma 4 / AICore / 云 Gemini
④ 传统 ML 层  ML Kit / LiteRT / MediaPipe
```

**官方降级架构原话**([AppFunctions 文档](https://developer.android.com/ai/appfunctions)):

> "Make your app's capabilities available to qualified agents using AppFunctions **or** rely on UI automation on supported devices **as a fall back**."

—— 即前文降级金字塔的官方实现:**AppFunctions 优先(应用配合),Computer Control 兜底(应用不配合)**。Android 官方博客 *The Intelligent OS*(2026-02)详述了这两条路径:

**(1) AppFunctions —— "设备端 MCP Server"(编排层,需应用配合)**

官方文档原文:"AppFunctions ... to simplify Android MCP integration. It empowers your apps to behave like **on-device MCP servers**." 与标准 MCP Server 的差异:**OS 内置、本地执行**,对比标准 MCP server 走"云端执行 + 网络往返"。

- 开发者实现:`@AppFunction(isDescribedByKDoc = true)` 注解 + **KDoc 文档即 AI 可读 schema** + `AppFunctionService` 入口;Jetpack 库自动生成 XML schema 注册到 OS。需 **Android 16+(API 36)**。
- 调用方需 `EXECUTE_APP_FUNCTIONS` 权限;Gemini 集成处于**私有预览**。
- **现状很早期**:实验性 API,仅"少量 app 和系统 agent"能用完整 pipeline。
- 应用不实现 → ❌ 不可用。

**(2) Android Computer Control —— 官方 GUI agent,且不走无障碍!(执行层,零代码兜底)**

这是 Gemini "screen automation" 的底层框架。**关键修正:Google 没有借用无障碍服务**(与国产根本不同),而是新造了一条干净的特权通道([Computer Control 文档](https://developer.android.com/ai/computer-control)):

| 维度 | Android Computer Control |
|---|---|
| 机制 | **迭代截屏 → 智能推断 → 模拟点击/滑动/文本输入** |
| 运行环境 | **安全后台虚拟显示器(virtual display)**,类似投屏,与用户当前界面隔离——这正是 Gemini "端侧虚拟手机环境"、用户能 "View progress" 看虚拟屏的底层原因 |
| 权限 | **`ACCESS_COMPUTER_CONTROL` 特权系统权限(不是 a11y!)** |
| 谁能用 | **仅 OEM 预装助手**(Pixel/Gemini、三星助手),三方不可用 |
| 应用配合 | target app **零适配** |
| 限制 | 单 session 最多 6 个 target app 顺序执行;全局同时只允许 1 个活跃 session |
| 安全 | 首次操作弹用户授权对话框;虚拟显示器隔离;可移交控制权给用户(交易确认);用户可主动接管 |

消费层(Gemini screen automation)的能力与边界([Google Support](https://support.google.com/pixelphone/answer/16940971?hl=en)):下复杂披萨单、重复买菜、叫 Uber、订 DoorDash;若应用已作为 Connected App 连接,**优先 Connected App 而非屏幕自动化**(官方降级逻辑)。安全上有 Take Control(密码/支付/登录暂停让用户接管)、行动计划需确认、警告 prompt injection。**官方自列风险**:点错按钮、未经许可下单、数量错误,并明确 "You're responsible for Gemini's actions ... including ... purchases"——**责任归用户**。**现实边界极窄**:仅 Pixel 10 / Galaxy S26 / Z Flip 8 / Z Fold 8,仅美国和韩国,仅英语和韩语,beta。

**(3) Gemini —— 用 function-calling 编排**

AppFunctions 把能力声明成 "tools",**Gemini 用标准 function-calling 机制**([Gemini API function-calling](https://ai.google.dev/gemini-api/docs/function-calling))决定何时调哪个 tool、跨 app 编排。官方博客称 Gemini 已能在 Calendar/Notes/Tasks 等 app 类别上跨 app 自动化([The Intelligent OS, 2026-02](https://android-developers.googleblog.com/2026/02/the-intelligent-os-making-ai-agents.html));**编排权牢牢攥在 Gemini(=OS)手里**——印证"OS 厂商不会把编排权交给应用 agent"。

**Google vs 国产:两种执行哲学**

| | Google(Computer Control) | 国产(荣耀/小米/vivo 等) |
|---|---|---|
| 执行机制 | 新特权权限 `ACCESS_COMPUTER_CONTROL` + 虚拟显示器 | 借用无障碍服务走"盲道" |
| 隔离 | 虚拟显示器隔离,不打扰用户 | 直接接管真机屏幕 |
| 安全 | 特权权限受控 + 授权弹窗 + 可移交控制 | a11y 能读一切("上帝之手"),透明度参差 |
| 开放性 | 封闭:仅 OEM 预装助手 | 半开放:系统智能体自用 |
| 落地 | Preview / private preview(优雅但早期) | 已大规模商用(粗暴但已落地) |

**本质**:Google 不愿像国产那样挤占残障人士的无障碍通道,而是重造一条"干净专用通道"——既是工程洁癖,也是合规考量(走 a11y 在欧美有"单独同意"法律风险)。

> 一句话:**这是"应用不配合怎么办"的官方答案——Google = 中粒度 AppFunctions + Gemini function-calling 编排 + Computer Control 特权 GUI agent 兜底(特权权限+虚拟显示器,不走 a11y);三层都把编排权和执行可控性留在系统侧,但承认还很早期、风险自担。**

**对 MatrixAgent 的启示**:车机侧若做 GUI 兜底,可参考 Google 虚拟显示器隔离思路(比直接 a11y 接管更安全、更适合车规),而非照搬国产 a11y 走盲道。

### 6.2 国产:清一色 a11y 走"盲道" + MCP 编排(走得更激进)

上轮"未公开技术细节"的空白,被 21 世纪经济报道《万字详解智能体:AI 手机走"盲道"》坐实:**国产智能体操作三方 App 的底层执行,清一色靠 Android 无障碍服务(Accessibility Service)读 GUI 树 + 模拟点击/滑动/输入。** "盲道"比喻:无障碍功能本是给视障人士的专用通道,AI 借道而行。机制三步:感知(读屏)→ 推理(LLM)→ 操作。能力边界:"读屏"能隐秘获得前台所有内容(含银行 App 卡号),被形容为"上帝之手"。

**各厂商隐私透明度光谱(差异很大)**:

| 厂商 | 做法 | 透明度 |
|---|---|---|
| 小米超级小爱 | 弹通知明确说明 + 用户同意才开 a11y | 最透明 |
| 荣耀 YOYO | 任务时自动开、结束自动关,不单独通知 | 中 |
| 三星 Bixby | 默默开启 | 低 |
| OPPO 小布 | 开启前不询问,且不在无障碍列表(无从核查) | 最低 |
| vivo | 输入法嵌入,授权后全程开 | 中 |
| **华为** | **唯一例外:不走 a11y,走意图框架(API 合作)** | 最克制 |

华为的例外正好印证"系统厂有生态权力时优先 API,而非 a11y 兜底"。

**真实成功率(权威印证)**:张驰团队论文 **App Agent ~73%(学习示例后 84%),真人 95%**;WebArena 第一名 IBM CUGA 仅 **61.7%**。实测案例:点咖啡,小米/OPPO 跳到美团搜索页就**任务终止**;荣耀 YOYO 走到下单页但要"多轮确认,并不比自己动手快多少"。

**两条路线的专家定调(权威印证全文推演)**:

- 意图框架(API/SDK):稳定安全,但"App 大厂态度谨慎",怕"用户不再主动打开 App,影响流量和广告"——正是"应用方没动力配合"的商业根因。
- 视觉路线(GUI Agent):能绕过 App 后台授权、前台直接操作,代表未来但风险多。
- **业内原话:"视觉路线只是过渡方案,最优解仍然还是推动 API 合作";两条"并行,不是二选一"。**

**两家"AI Agent 手机"(WAIC 2026)**:

- **努比亚 NaviX Ultra**("世界首款量产"):system-level GUI Agent + **MCP + A2A 双协议**。
- **StepFun StepX Neo**(2026-07):Step AOS(AI-native、Android 兼容)+ agent "Amoo" + "双域三步"记忆架构 + MCP + 集成 Alipay。

**关键澄清(见 §5.5)**:这些产品虽用 MCP/A2A,但那只是**编排层**;底层操作不配合的三方 App,**仍靠 a11y 走盲道**。中国 AI 手机 2026 出货预计 1.47 亿台,vivo/Honor/Xiaomi/OPPO 都在卷。

### 6.3 Apple:App Intents,最保守也最被卡

WWDC26 仍主推 **App Intents**(应用自愿暴露),Siri 2026 定位"agentic automation engine",三支柱:Onscreen Awareness + Personal Context + App Intents。Apple **基本没走系统级 GUI 自动化兜底**——因 iOS 禁三方 a11y 自动化 + 支付/隐私考量。**所以 Apple 仍最被"应用不配合"卡着。**

### 6.4 GUI Agent 可靠性的最新硬数据(关键)

来自 *The Hardest Easy Problem in AI*:

- **OSWorld(单步基准)**:12%(2024-04)→ **85%**(2026-06),两年大涨。
- **OSWorld 2.0(多步长任务)**:最好的前沿系统仅 **20.6%**;人类中位需 1.6 小时完成。
- 核心论断原文:**"GUIs are an adversarial control surface"**(GUI 是对抗性控制界面)。

**含义**:单步够用了(85%,GUI 兜底"能用");但**多步真实任务只有 20.6%,远不够**(所以"不丝滑")。步骤累积错误率是核心瓶颈。这精确量化了"GUI Agent 单步 80%、多步串联暴跌",且"对抗性控制界面"正对应"手机 app 会主动对抗自动化"。

**国产实测互相印证**(见 §6.2):App Agent 成功率 73%、WebArena 第一仅 61.7%、真人 95%——三组数据指向同一结论:**GUI 自动化"能用"但离"好用"还差很远,所以只能当兜底,不能当主力。**

### 6.5 未定争论

**pixel-driving(GUI Agent)vs agent-native interfaces(MCP)谁会赢,未定。** Google 两条都做(对冲),最稳妥。

---

## 7. 对 MatrixAgent 的落地建议

### 7.1 架构:按降级金字塔组织执行层

执行层不要只做 GUI Agent,也不要只等应用接口,而是**分层 fallback**:

```
用户意图
  → 应用 Agent / AppFunctions(若可用)
  → 原子接口 / MediaSession
  → Deeplink / Intent
  → View 树(a11y)结构化定位
  → GUI Agent(VLM 看屏 + 注入)兜底
```

每一层失败才降级。**这正是 Google 2026 官方架构,踩在趋势上。**

### 7.2 地基优先,别押注三方 App

**别把 MatrixAgent 的成败押在"能不能丝滑控制爱奇艺"上**——它正好落在"应用不配合就做不好"的长尾区。项目最该投入、最能证明"懂车机 AI OS 地基"的是:

- **车控**(空调/车窗/座椅/驾驶模式)
- **导航 / 充电规划**
- **系统设置 / 多模态交互**
- **媒体中心(MediaSession)**

这些是 AI OS 成立的理由,且不依赖三方配合。

### 7.3 GUI 兜底:能用,但别过度承诺

记住 20.6% 的多步天花板。MatrixAgent 把 GUI 作为兜底层是对的,但面试时主动讲:

> "我知道当前多步 GUI agent 成功率只有 ~20%,所以我把它定位成兜底而不是主力。"

这种对技术栈局限的清醒认知,比吹"AI 控制一切"加分得多——这是 mid-level 往 senior 走的分水岭。

### 7.4 面试叙事(高价值判断点)

能讲清楚以下几点的候选人,远超"我做了个通用 Agent 框架":

1. **同样是 AI OS,车机和手机因为生态权力差异走了完全相反的技术路线**(车机 GUI Agent 兜底,手机 GUI Agent 主力)。说明懂"约束决定架构"。
2. **车机 AI OS 的价值地基在系统层,不在三方 App**;能解释为什么蔚小理华为应用生态不完整照样卖得动。
3. **不盲目上 VLM**:先用平台原生 a11y 通道做结构化定位,遇到 H5/自绘黑盒才退化到视觉。
4. **区分地基价值 vs 锦上添花**;车机/手机分开设计,无通用解(反对过早抽象的"伪通用层")。

---

## 8. 参考资料

- [The Intelligent OS: Making AI agents more helpful (Android Developers, 2026-02)](https://android-developers.googleblog.com/2026/02/the-intelligent-os-making-ai-agents.html) — AppFunctions + UI 自动化双路径
- [Gemini Intelligence (Google Blog)](https://blog.google/products-and-platforms/platforms/android/gemini-intelligence/) — 多步骤任务自动化
- [How AI in China Is Killing Smartphone Apps (Computerworld, 2026-08-03)](https://www.computerworld.com/article/4203560/how-ai-in-china-is-killing-smartphone-apps.html) — 国产三家用 MCP + 系统级访问
- [The Hardest Easy Problem in AI: Computer Use Agents (Medium)](https://medium.com/@adnanmasood/the-hardest-easy-problem-in-ai-the-state-of-computer-use-agents-a7e3aea7fa3a) — OSWorld 85% vs 长任务 20.6% 鸿沟
- [Explore Advanced App Intents (WWDC26)](https://developer.apple.com/videos/play/wwdc2026/343/) — Apple App Intents 路线
- [The Three-Way Fight for China's AI Phone (hellochinatech)](https://hellochinatech.com/p/china-ai-agent-phone) — WAIC 2026 三家 AI agent 手机
- [XPENG AI Tianji / XOS 5.1.0](https://www.xpeng.com/news/018f968985698f616d3f2c9e8f720154) — 车机侧 AI OS
- [HarmonyOS Cockpit 6 (China Daily, Auto China 2026)](https://epaper.chinadaily.com.cn/a/202604/27/WS69eebd44a310ec22b1fd4164.html) — 华为座舱 AI OS

**深度调研补充(2026-08,第二轮)**:

- [Overview of AppFunctions (developer.android.com)](https://developer.android.com/ai/appfunctions) — 官方 API:`@AppFunction` / KDoc schema / `EXECUTE_APP_FUNCTIONS` / 设备端 MCP server
- [Ask Gemini to handle your multi-step tasks (Google Support)](https://support.google.com/pixelphone/answer/16940971?hl=en) — screen automation 机制、端侧虚拟手机环境、Take Control、官方风险列表
- [万字详解智能体:AI 手机走"盲道" (21世纪经济报道)](https://www.21jingji.com/article/20250317/herald/6410c6c74c64a254bdc041898ecbd76c.html) — 国产 a11y 走盲道机制、厂商透明度光谱、成功率数据、两条路线专家定调
- [Nubia NaviX Ultra (Pandaily)](https://pandaily.com/ai-agent-phones-nubia-bytedance-waic-jul2026) — system-level GUI Agent + MCP/A2A
- [StepFun StepX Neo (SCMP)](https://www.scmp.com/tech/big-tech/article/3360544/chinas-stepfun-claims-it-has-unveiled-worlds-first-ai-smartphone) — Step AOS / Amoo / 双域三步记忆

**深度调研补充(2026-08,第三轮 —— Google 设计深挖)**:

- [Android AI 总览 (developer.android.com/ai)](https://developer.android.com/ai) — Google Agent 技术栈四层分层(编排 / 能力暴露 / 模型 / 传统 ML)
- [Android Computer Control (developer.android.com)](https://developer.android.com/ai/computer-control) — 官方 GUI agent 框架:`ACCESS_COMPUTER_CONTROL` 特权权限 + 虚拟显示器 + 截屏推断模拟输入(不走 a11y)+ 仅 OEM 预装助手 + 单 session 6 app
- [Function calling with the Gemini API (ai.google.dev)](https://ai.google.dev/gemini-api/docs/function-calling) — Gemini 编排 AppFunctions 的底层 function-calling 机制
- [Google details 'AppFunctions' (9to5Google, 2026-02-25)](https://9to5google.com/2026/02/25/android-appfunctions-gemini/) — AppFunctions 是 Android 16 平台特性,Gemini 用其编排跨 app 操作

---

*本文档由 2026-08 的技术推演 + 三轮深度调研整理而成,结论已存入项目记忆 `ai-os-landscape-2026`。第三轮调研补充了 Google 完整技术栈分层、Android Computer Control 官方 GUI agent 机制(特权权限+虚拟显示器,不走 a11y)、以及 Gemini function-calling 编排。*
