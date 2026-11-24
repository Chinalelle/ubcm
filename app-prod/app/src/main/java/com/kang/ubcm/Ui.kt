package com.kang.ubcm

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 蓝白金设计系统 · 色值唯一来源（视觉规范见 docs/18 §2）。
 *
 * 三条硬规则：
 * 1. 白是主体：页面底 [Bg]、卡片 [White]，不用大面积深色底。
 * 2. 蓝只承担「可操作 / 已选中 / 链接 / 数据」，不做装饰性大色块、不做渐变。
 * 3. 金严格 ≤ 10%，只给强调数字、徽章、进度荣誉。
 *
 * 旧符号名（[UbcmColors]）继续可用，但值全部指向这里，避免两套色源飘。
 */
object UbcmTheme {

    // ---------- 品牌蓝（docs/18 §2.1） ----------
    /** 最深：大标题、深色顶栏底 */
    val Brand900 = Color(0xFF0B2545)
    /** 深色顶栏渐变底（仅顶栏） */
    val Brand800 = Color(0xFF123561)
    /** 品牌主色：主按钮、Tab 选中、强调竖条 */
    val Brand700 = Color(0xFF1B4A7E)
    /** 主按钮按下态 */
    val Brand600 = Color(0xFF235E9C)
    /** 链接、可交互、图表主系列 */
    val Brand500 = Color(0xFF3A7BC8)
    /** 禁用态、浅色图标（不适合小号正文） */
    val Brand300 = Color(0xFF8FB8EA)
    /** 选中背景、标签底、课程块底 */
    val Brand100 = Color(0xFFDCE9F9)
    /** 极浅色块（替代灰底） */
    val Brand50 = Color(0xFFF2F7FD)

    // ---------- 金（docs/18 §2.2） ----------
    /** 金色文字（白底对比度 4.6:1，达标） */
    val Gold600 = Color(0xFFA67C00)
    /** 徽章、进度环、强调数字（需配深色字） */
    val Gold500 = Color(0xFFC9A227)
    /** 金色浅底、奖章底 */
    val Gold100 = Color(0xFFF7EDCC)

    // ---------- 中性（docs/18 §2.3） ----------
    /** 卡片、输入框、浮层 */
    val White = Color(0xFFFFFFFF)
    /** 页面底（冷白，比卡片退一档） */
    val Bg = Color(0xFFF4F7FB)
    /** 分割线、卡片描边 */
    val Line = Color(0xFFE2E9F2)
    /** 正文 / 标题（藏青黑，禁止纯黑） */
    val Text1 = Color(0xFF101B2B)
    /** 次要文字、副标题 */
    val Text2 = Color(0xFF4E5D73)
    /** 占位、弱文字、未选中图标 */
    val Text3 = Color(0xFF8C9AAD)

    // ---------- 语义色（docs/18 §2.4，降饱和） ----------
    /** 成功、可用、已预约 */
    val Success = Color(0xFF2E9E6B)
    /** 警告、临界、即将过期 */
    val Warning = Color(0xFFD99A2B)
    /** 错误、已占、危险操作 */
    val Danger = Color(0xFFD14343)
    /** 信息提示（同 brand-500） */
    val Info = Color(0xFF3A7BC8)
}

/**
 * 兼容层：旧代码里的 269 处 `UbcmColors.XXX` 继续编译，但值统一由 [UbcmTheme] 提供。
 *
 * 老符号名与新语义并非一一对应，后续分批改成 [UbcmTheme] 的新命名，
 * 本轮只换色值，不动调用点符号名。
 */
object UbcmColors {
    /** 正文 / 标题 → text-1 */
    val Ink = UbcmTheme.Text1
    /** 次要文字 → text-2 */
    val InkSoft = UbcmTheme.Text2
    /** 页面底 → bg */
    val Paper = UbcmTheme.Bg
    /** 卡片 → white */
    val CardWhite = UbcmTheme.White
    /** 分割线 / 描边 → line */
    val Line = UbcmTheme.Line
    /**
     * 旧「朱砂」符号：语义已变成**品牌蓝**（brand-700）。
     * 原来用它表达「错误 / 危险 / 已占」的调用点，已逐个改为 [Danger]。
     */
    val Cinnabar = UbcmTheme.Brand700
    /** 成功 / 可用 → success */
    val Jade = UbcmTheme.Success
    /** 仅作纯色块、徽章底 → gold-500；当「警告文字」用时应改 [Warning] */
    val Amber = UbcmTheme.Gold500
    /** 占位 / 弱文字 → text-3 */
    val Ash = UbcmTheme.Text3
    /**
     * 危险 / 错误 / 已占（docs/18 §2.4 danger #D14343）。
     * 只给失败、过期、违约、已满这类真负面语义；不要拿它做装饰。
     */
    val Danger = UbcmTheme.Danger
    /** 警告 / 临界（docs/18 §2.4 warning #D99A2B），用于「将满 / 即将过期」。 */
    val Warning = UbcmTheme.Warning
}

/** 字体层级（docs/18 §3）。字号与建议行高成对给出，避免各处自己算行高。 */
object UbcmType {
    /** 余额、总分等唯一大数字 */
    const val DISPLAY_SP = 34f
    const val DISPLAY_LINE_SP = 41f
    /** 页面主标题 */
    const val H1_SP = 24f
    const val H1_LINE_SP = 31f
    /** 区块标题 */
    const val H2_SP = 20f
    const val H2_LINE_SP = 26f
    /** 卡片标题、列表主文 */
    const val TITLE_SP = 17f
    const val TITLE_LINE_SP = 24f
    /** 正文 */
    const val BODY_SP = 16f
    const val BODY_LINE_SP = 24f
    /** 辅助说明、时间 */
    const val CAPTION_SP = 13f
    const val CAPTION_LINE_SP = 18f
    /** 标签、角标 */
    const val MICRO_SP = 11f
    const val MICRO_LINE_SP = 13f

    val Display = DISPLAY_SP.sp
    val DisplayLine = DISPLAY_LINE_SP.sp
    val DisplayWeight = FontWeight.SemiBold

    val H1 = H1_SP.sp
    val H1Line = H1_LINE_SP.sp
    val H1Weight = FontWeight.SemiBold

    val H2 = H2_SP.sp
    val H2Line = H2_LINE_SP.sp
    val H2Weight = FontWeight.SemiBold

    val Title = TITLE_SP.sp
    val TitleLine = TITLE_LINE_SP.sp
    val TitleWeight = FontWeight.Medium

    val Body = BODY_SP.sp
    val BodyLine = BODY_LINE_SP.sp
    val BodyWeight = FontWeight.Normal

    val Caption = CAPTION_SP.sp
    val CaptionLine = CAPTION_LINE_SP.sp
    val CaptionWeight = FontWeight.Normal

    val Micro = MICRO_SP.sp
    val MicroLine = MICRO_LINE_SP.sp
    val MicroWeight = FontWeight.Normal
}

/** 圆角（docs/18 §5）。卡片与按钮刻意不同：卡片更圆、按钮略方，层级才分得开。 */
object UbcmRadius {
    /** 卡片 16dp */
    val Card = 16.dp
    /** 按钮 12dp */
    val Button = 12.dp
    /** 输入框 / 选择器 12dp */
    val Field = 12.dp
    /** 标签 / 胶囊 999dp */
    val Pill = 999.dp
    /** 小方形标签 8dp */
    val Chip = 8.dp
    /** 底部弹层顶角 28dp */
    val SheetTop = 28.dp
    /** 头像 999dp */
    val Avatar = 999.dp
}

/** 间距（docs/18 §4，4dp 基准），只放重复率最高的几档，避免过度抽象。 */
object UbcmSpacing {
    /** 页面左右安全边距 */
    val PageH = 20.dp
    /** 卡片内边距 */
    val CardPad = 16.dp
    /** 卡片之间垂直间距 */
    val CardGap = 12.dp
    /** 区块之间 */
    val SectionGap = 24.dp
    /** 列表行最小高度（单行） */
    val RowMin = 60.dp
}
