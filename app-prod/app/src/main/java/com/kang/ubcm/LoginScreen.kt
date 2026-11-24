package com.kang.ubcm

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 登录页（docs/28 §2）。
 *
 * 定位（2026-11-24 起）：**不再是强制门禁**——缺凭证时先出现这里，可以「先跳过」进首页，
 * 底部会有红色警示栏提醒。凭证补齐流程（学号密码直连 CAS）完全保留。
 *
 * 🔴 红线：密码只在 [onLogin] 的入参里活一次，不进 State、不落盘、不写日志。
 * 这个页面本身也不缓存密码（remember 的只是本次输入，进程结束即消失）。
 */
@Composable
fun LoginScreen(
    login: LoginViewModel.UiState,
    onLoadPage: () -> Unit,
    onLogin: (username: String, password: String, captcha: String?) -> Unit,
    onRefreshCaptcha: () -> Unit,
    onSkip: () -> Unit,
) {
    var username by remember { mutableStateOf(login.lastUsername) }
    var password by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    // 首次进来拉一次学校登录页：拿 lt/execution，顺便探一下要不要图形验证码
    LaunchedEffect(Unit) { onLoadPage() }

    val canSubmit = username.isNotBlank() && password.isNotBlank() && !login.busy

    Surface(color = UbcmColors.Paper, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                // 只限宽、不限高：fillMaxSize 先把宽度顶满会让 widthIn(max) 失效（平板实测）
                Modifier.widthIn(max = 420.dp).fillMaxHeight().padding(horizontal = 28.dp),
            ) {
                Spacer(Modifier.weight(0.9f))

                // ---- 品牌大字 ----
                Text(
                    "UBCM",
                    fontSize = 52.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = UbcmColors.Cinnabar,
                    letterSpacing = (-1).sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "北京中医药大学 · 校园助手",
                    fontSize = 13.sp,
                    color = UbcmColors.Ash,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )

                Spacer(Modifier.height(40.dp))

                // ---- 学号 ----
                LoginField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = "学号",
                    keyboardType = KeyboardType.Number,
                )
                Spacer(Modifier.height(12.dp))

                // ---- 密码（可切明文）----
                LoginField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "密码",
                    keyboardType = KeyboardType.Password,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        Text(
                            if (showPassword) "隐藏" else "显示",
                            fontSize = 12.sp,
                            color = UbcmColors.Cinnabar,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { showPassword = !showPassword }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    },
                )

                // ---- 图形验证码（仅学校要求时出现）----
                if (login.captchaRequired) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            LoginField(
                                value = captcha,
                                onValueChange = { captcha = it },
                                placeholder = "图形验证码",
                                keyboardType = KeyboardType.Text,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier
                                .size(width = 96.dp, height = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(UbcmColors.CardWhite)
                                .border(1.dp, UbcmColors.Line, RoundedCornerShape(12.dp))
                                .clickable { onRefreshCaptcha() },
                            contentAlignment = Alignment.Center,
                        ) {
                            val bmp = login.captchaImage
                                ?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
                            if (bmp != null) {
                                Image(
                                    bmp.asImageBitmap(),
                                    contentDescription = "验证码，点击刷新",
                                    modifier = Modifier.fillMaxSize().padding(2.dp),
                                )
                            } else {
                                Text("点我刷新", fontSize = 10.sp, color = UbcmColors.Ash)
                            }
                        }
                    }
                }

                // ---- 失败提示 ----
                login.error?.let { err ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        err,
                        fontSize = 13.sp,
                        color = UbcmColors.Danger,
                        lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(18.dp))

                // ---- 登录按钮 ----
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (canSubmit) UbcmColors.Cinnabar else UbcmColors.Cinnabar.copy(alpha = .38f))
                        .clickable(enabled = canSubmit) {
                            if (canSubmit) onLogin(username, password, captcha.takeIf { it.isNotBlank() })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (login.busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("登录中…", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        }
                    } else {
                        Text("登录", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                }

                // ---- 先跳过 ----
                Spacer(Modifier.height(20.dp))
                Text(
                    "先跳过",
                    fontSize = 15.sp,
                    color = UbcmColors.InkSoft,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onSkip)
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )

                Spacer(Modifier.weight(1f))

                // ---- 底部说明 ----
                Text(
                    "密码只在本机与学校 CAS 通信时使用一次，不保存",
                    fontSize = 11.sp,
                    color = UbcmColors.Ash,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * 登录页输入框：白底、48dp、12dp 圆角、1dp line 描边；聚焦时描边变 brand-700（docs/18 §8.5）。
 * 用 BasicTextField 自绘而不是 OutlinedTextField —— 后者自带 label 浮动与 Material 描边，改不成设计稿的样子。
 */
@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(UbcmColors.CardWhite)
            .border(
                width = if (focused) 1.5.dp else 1.dp,
                color = if (focused) UbcmColors.Cinnabar else UbcmColors.Line,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, fontSize = 16.sp, color = UbcmColors.Ash)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = UbcmColors.Ink),
                cursorBrush = SolidColor(UbcmColors.Cinnabar),
                visualTransformation = visualTransformation,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}
