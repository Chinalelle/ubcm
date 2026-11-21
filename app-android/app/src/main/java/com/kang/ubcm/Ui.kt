package com.kang.ubcm

import androidx.compose.ui.graphics.Color

/**
 * 全局配色：暖白纸面 + 朱砂点缀，刻意避开 Material 默认紫。
 * 首页与体检页共用，避免两套颜色飘。
 */
object UbcmColors {
    val Ink = Color(0xFF1E1B17)
    val InkSoft = Color(0xFF6B6359)
    val Paper = Color(0xFFFBF7F0)
    val CardWhite = Color(0xFFFFFFFF)
    val Line = Color(0xFFEDE3D4)
    val Cinnabar = Color(0xFFB33A2B)
    val Jade = Color(0xFF2E7D5B)
    val Amber = Color(0xFFC98A1F)
    val Ash = Color(0xFF9A9A94)
}
