package com.kang.ubcm

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 我的页（docs/23）：信息卡 + 设置三分组。
 *
 * 信息卡数据只来自本机账号（JWT + 课表班级），**不本地编辑**；
 * 拿不到的学院/专业显示 `—`，不编造。
 */
@Composable
fun MeScreen(
    homeVm: HomeViewModel,
    audit: CredentialGate.Audit,
    onOpenAuth: () -> Unit,
    onOpenProbe: () -> Unit,
    onLogout: () -> Unit,
) {
    val ctx = LocalContext.current
    val st by homeVm.state.collectAsStateWithLifecycle()

    var avatarVersion by remember { mutableStateOf(0) }
    var showAvatarSheet by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var confirmingLogout by remember { mutableStateOf(false) }
    var editingAnchor by remember { mutableStateOf(false) }
    var anchorText by remember { mutableStateOf(st.firstWeekMonday.toString()) }
    var anchorError by remember { mutableStateOf<String?>(null) }
    var cacheSize by remember { mutableStateOf(dirSize(ctx.cacheDir)) }
    var cacheCleared by remember { mutableStateOf(false) }

    val avatar: Bitmap? = remember(avatarVersion) { AvatarStore.load(ctx) }

    // Photo Picker：无需存储权限
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && AvatarStore.importFrom(ctx, uri)) {
            avatarVersion++
        }
    }
    // 相机缩略图（相机 App 负责拍照，不需要 CAMERA 权限）
    val takePreview = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp != null && AvatarStore.importBitmap(ctx, bmp)) {
            avatarVersion++
        }
    }

    // 门禁的 CredentialGate 只检查"有没有值"（JWT 额外查过期），座位 cookie 的有效性它感知不到。
    // 这里补一次只读校验，让"凭证状态"那行不虚报。
    var seatCookieOk by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        val ck = Session.loadSeatCookie(ctx)
        if (!ck.isNullOrBlank()) {
            seatCookieOk = withContext(Dispatchers.IO) {
                runCatching { SeatApi.validCookie(ck) }.getOrDefault(false)
            }
        }
    }

    val id = Session.parse(Session.loadToken(ctx))
    val name = st.userName ?: id?.name
    val studentNo = id?.studentNo
    val className = st.sessions.firstOrNull { it.className.isNotBlank() }?.className

    Surface(color = UbcmColors.Paper, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            LazyColumn(
                Modifier.fillMaxHeight().align(Alignment.TopCenter).widthIn(max = 640.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    Text("我的", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = UbcmColors.Ink)
                }

                item {
                    ProfileCard(
                        avatar = avatar,
                        name = name,
                        studentNo = studentNo,
                        className = className,
                        onAvatarClick = { showAvatarSheet = true },
                    )
                }

                // ---------------- 账号与安全 ----------------
                item {
                    GroupTitle("账号与安全")
                    SettingCard {
                        SettingRow(
                            title = "凭证状态",
                            value = if (seatCookieOk == false && audit.okCount > 0) {
                                Profile.credentialText(audit.okCount, audit.items.size) + " · 座位待续期"
                            } else {
                                Profile.credentialText(audit.okCount, audit.items.size)
                            },
                            dotColor = when {
                                audit.items.isEmpty() -> UbcmColors.Ash
                                audit.okCount == 0 -> UbcmColors.Danger
                                seatCookieOk == false -> UbcmColors.Warning
                                audit.okCount >= audit.items.size -> UbcmTheme.Success
                                else -> UbcmColors.Warning
                            },
                            onClick = onOpenAuth,
                        )
                        DividerRow()
                        SettingRow(title = "数据体检", value = "", onClick = onOpenProbe)
                        DividerRow()
                        // 退出登录：危险色文字，行内二次确认（不弹窗）
                        Row(
                            Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!confirmingLogout) {
                                Text(
                                    "退出登录",
                                    fontSize = 14.sp,
                                    color = UbcmColors.Danger,
                                    modifier = Modifier.weight(1f).clickable { confirmingLogout = true },
                                )
                            } else {
                                Text("清空所有凭证？", fontSize = 13.sp, color = UbcmColors.InkSoft)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "确认退出",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(UbcmColors.Danger)
                                        .clickable { onLogout() }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "取消",
                                    fontSize = 13.sp,
                                    color = UbcmColors.Ash,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { confirmingLogout = false }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }

                // ---------------- 学习 ----------------
                item {
                    GroupTitle("学习")
                    SettingCard {
                        SettingRow(
                            title = "周次锚点",
                            value = if (st.shownWeek >= 1) "第 ${st.shownWeek} 周" else "未开学",
                            onClick = {
                                anchorText = st.firstWeekMonday.toString()
                                anchorError = null
                                editingAnchor = !editingAnchor
                            },
                        )
                        if (editingAnchor) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text("本学期第 1 周的周一（换学期必改）", fontSize = 11.sp, color = UbcmColors.Ash)
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = anchorText,
                                        onValueChange = { anchorText = it; anchorError = null },
                                        singleLine = true,
                                        isError = anchorError != null,
                                        modifier = Modifier.width(150.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "保存",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(UbcmColors.Cinnabar)
                                            .clickable {
                                                val err = homeVm.setFirstWeekMonday(anchorText)
                                                if (err == null) editingAnchor = false else anchorError = err
                                            }
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                    )
                                }
                                anchorError?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Text(it, fontSize = 11.sp, color = UbcmColors.Danger)
                                }
                            }
                        }
                    }
                }

                // ---------------- 通用 ----------------
                item {
                    GroupTitle("通用")
                    SettingCard {
                        SettingRow(
                            title = "清理缓存",
                            value = if (cacheCleared) "已清理" else formatSize(cacheSize),
                            onClick = {
                                File(ctx.cacheDir.absolutePath).listFiles()?.forEach { it.deleteRecursively() }
                                cacheSize = dirSize(ctx.cacheDir)
                                cacheCleared = true
                            },
                        )
                        DividerRow()
                        SettingRow(
                            title = "关于本应用",
                            value = "v1.0",
                            onClick = { showAbout = !showAbout },
                        )
                        if (showAbout) {
                            AboutPanel()
                        }
                    }
                }
            }

            if (showAvatarSheet) {
                AvatarSheet(
                    hasAvatar = avatar != null,
                    onPick = {
                        showAvatarSheet = false
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onCamera = {
                        showAvatarSheet = false
                        takePreview.launch(null)
                    },
                    onReset = {
                        AvatarStore.clear(ctx)
                        avatarVersion++
                        showAvatarSheet = false
                    },
                    onClose = { showAvatarSheet = false },
                )
            }
        }
    }
}

// ---------------- 信息卡 ----------------

@Composable
private fun ProfileCard(
    avatar: Bitmap?,
    name: String?,
    studentNo: String?,
    className: String?,
    onAvatarClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 64dp 圆头像 + 18dp 相机角标
        Box(Modifier.size(64.dp).clickable(onClick = onAvatarClick)) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape).background(UbcmTheme.Line),
                contentAlignment = Alignment.Center,
            ) {
                if (avatar != null) {
                    Image(
                        bitmap = avatar.asImageBitmap(),
                        contentDescription = "头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                } else {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = "默认头像",
                        tint = UbcmColors.Ash,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(UbcmColors.Cinnabar)
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PhotoCamera,
                    contentDescription = "更换头像",
                    tint = Color.White,
                    modifier = Modifier.size(11.dp),
                )
            }
        }

        // 信息区：左对齐、与头像间距 40dp（不贴右缘）
        Spacer(Modifier.width(40.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                Profile.orEmpty(name),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = UbcmColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ProfileLine(Profile.orEmpty(studentNo))
            ProfileLine(Profile.EMPTY)
            ProfileLine(Profile.EMPTY)
            ProfileLine(Profile.orEmpty(Profile.classLabel(className)))
        }
    }
}

@Composable
private fun ProfileLine(text: String) {
    Text(text, fontSize = 13.sp, color = UbcmColors.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

// ---------------- 设置区 ----------------

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = UbcmColors.Ash,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbcmColors.CardWhite)
            .border(1.dp, UbcmColors.Line, RoundedCornerShape(16.dp)),
        content = content,
    )
}

@Composable
private fun DividerRow() {
    Box(
        Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(UbcmColors.Line.copy(alpha = 0.7f))
    )
}

@Composable
private fun SettingRow(
    title: String,
    value: String,
    dotColor: Color? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(46.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 14.sp, color = UbcmColors.Ink, modifier = Modifier.weight(1f))
        if (dotColor != null) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(6.dp))
        }
        if (value.isNotBlank()) {
            Text(value, fontSize = 13.sp, color = UbcmColors.InkSoft)
            Spacer(Modifier.width(6.dp))
        }
        Text("›", fontSize = 16.sp, color = UbcmColors.Ash)
    }
}

@Composable
private fun AboutPanel() {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("UBcm v1.0 · 北京中医药大学校园助手", fontSize = 12.sp, color = UbcmColors.Ink)
        Text("非官方应用 · 个人本地工具，数据只存本机", fontSize = 11.sp, color = UbcmColors.InkSoft)
        Text("数据来源：学校门户 / 教务系统 / 图书馆 / 一卡通（只读）", fontSize = 11.sp, color = UbcmColors.InkSoft)
        Text("不代登录、不托管他人凭证；写操作一律在学校页面完成", fontSize = 11.sp, color = UbcmColors.InkSoft)
    }
}

// ---------------- 头像 Sheet ----------------

@Composable
private fun AvatarSheet(
    hasAvatar: Boolean,
    onPick: () -> Unit,
    onCamera: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)).clickable(onClick = onClose),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(UbcmColors.CardWhite)
                .pointerInput(Unit) { detectTapGestures { } }
                .navigationBarsPadding()
                .padding(vertical = 8.dp),
        ) {
            Text(
                "更换头像",
                fontSize = 13.sp,
                color = UbcmColors.Ash,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp),
            )
            SheetAction("从相册选择", onPick)
            SheetAction("拍照", onCamera)
            if (hasAvatar) SheetAction("恢复默认头像", onReset)
            Box(Modifier.fillMaxWidth().height(8.dp).background(UbcmColors.Paper))
            SheetAction("取消", onClose)
        }
    }
}

@Composable
private fun SheetAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, color = UbcmColors.Ink)
    }
}

// ---------------- 工具 ----------------

private fun dirSize(dir: File): Long = try {
    if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
} catch (e: Exception) {
    0L
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "0 KB"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
