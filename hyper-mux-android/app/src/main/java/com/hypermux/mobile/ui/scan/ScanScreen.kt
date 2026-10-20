package com.hypermux.mobile.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * 扫一扫登录 TV / 网页端（与登录页左侧二维码配对）。
 * 流程：相机取帧 → ZXing 解码 → 识别自家载荷 → 弹确认框 → confirm → toast + 返回。
 * 相机权限首次进入运行时申请；拒绝则停留在提示态，可退出。
 */
@Composable
fun ScanScreen(
    navController: NavController,
    vm: ScanViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        if (!granted) {
            Toast.makeText(context, "需要相机权限才能扫一扫", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(Unit) {
        if (!cameraGranted) permLauncher.launch(Manifest.permission.CAMERA)
    }

    val foundId by vm.foundQrId.collectAsStateWithLifecycle()
    val foundKind by vm.foundKind.collectAsStateWithLifecycle()
    val confirming by vm.confirming.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (cameraGranted) {
            CameraPreview(onText = vm::onQrDecoded)
            // WeChat 式取景框：四角括号 + 上下循环扫描线（确认框弹出时暂停动画）
            ScanViewfinder(active = foundId == null && result == null)
        } else {
            Text(
                text = "需要相机权限才能扫一扫\n（可在系统设置中授权后重新进入）",
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 顶栏：返回 + 标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }
            Text("扫一扫", color = Color.White, fontSize = 18.sp)
        }

        // 底部提示条
        Text(
            text = "将镜头对准 TV 或网页端登录页上的二维码",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }

    // 扫码结果：成功 → toast + 返回；失败 → toast 后继续扫
    LaunchedEffect(result) {
        when (val r = result) {
            is ScanResult.Success -> {
                Toast.makeText(context, "${r.targetName}登录成功", Toast.LENGTH_SHORT).show()
                navController.popBackStack()
            }
            is ScanResult.Error -> {
                Toast.makeText(context, r.msg, Toast.LENGTH_SHORT).show()
                vm.resumeScanning()
            }
            null -> Unit
        }
    }

    // 确认框（安全要求：扫到不直接登录，必须显式确认）
    val qrId = foundId
    if (qrId != null) {
        val target = ScanViewModel.targetName(foundKind)
        AlertDialog(
            onDismissRequest = vm::cancelConfirm,
            title = { Text("检测到 ${target}登录请求") },
            text = { Text("确认用当前账号登录 HyperMux ${target} 吗？") },
            confirmButton = {
                TextButton(onClick = vm::confirmLogin, enabled = !confirming) {
                    Text(if (confirming) "确认中…" else "确认登录")
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelConfirm) { Text("取消") }
            }
        )
    }
}

/* ==================== 取景框（微信扫码风格） ==================== */

/**
 * 扫码取景框：四角括号 + 从上到下循环扫描线（两端渐隐），给用户明确的"把码放进框里"引导。
 * [active]=false（确认框弹出 / 报错处理中）时只留静态框，动画暂停。
 */
@Composable
private fun BoxScope.ScanViewfinder(active: Boolean) {
    val lineColor = Color(0xFF35C759) // 品牌绿系扫描线（深色相机底上最醒目）
    val transition = rememberInfiniteTransition(label = "scanLine")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanProgress"
    )

    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(bottom = 24.dp) // 视觉中心略高于几何中心（顶栏占上部空间）
            .size(250.dp)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val corner = 26.dp.toPx()   // 角括号边长
            val stroke = 3.dp.toPx()
            val white = Color.White

            // 四角括号（微信式：只有角、不画整圈边）
            // 左上
            drawLine(white, Offset(0f, stroke / 2f), Offset(corner, stroke / 2f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(white, Offset(stroke / 2f, 0f), Offset(stroke / 2f, corner), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            // 右上
            drawLine(white, Offset(w - corner, stroke / 2f), Offset(w, stroke / 2f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(white, Offset(w - stroke / 2f, 0f), Offset(w - stroke / 2f, corner), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            // 左下
            drawLine(white, Offset(0f, h - stroke / 2f), Offset(corner, h - stroke / 2f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(white, Offset(stroke / 2f, h - corner), Offset(stroke / 2f, h), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            // 右下
            drawLine(white, Offset(w - corner, h - stroke / 2f), Offset(w, h - stroke / 2f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(white, Offset(w - stroke / 2f, h - corner), Offset(w - stroke / 2f, h), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)

            if (active) {
                // 扫描线：上下循环（去程下行、回程上行，无跳变），两端渐隐；离边框稍留内缩避免顶角
                val inset = 10.dp.toPx()
                val frac = if (progress <= 0.5f) progress * 2f else (1f - progress) * 2f
                val y = inset + (h - inset - inset) * frac
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, lineColor, lineColor, Color.Transparent),
                        startX = inset,
                        endX = w - inset
                    ),
                    start = Offset(inset, y),
                    end = Offset(w - inset, y),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
    }
}

/* ==================== 相机取流 + ZXing 解码 ==================== */

@Composable
private fun CameraPreview(onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzer = remember { QrFrameAnalyzer(onText) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                runCatching {
                    val provider = future.get()
                    provider.unbindAll()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(ANALYZE_EXECUTOR, analyzer) }
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}

/** 解码跑在独立线程（勿用主线程执行器：TRY_HARDER 单帧可达数十毫秒，会卡预览） */
private val ANALYZE_EXECUTOR = Executors.newSingleThreadExecutor()

/** YUV_420_888 的 Y 平面直接喂 ZXing（避免 YUV→RGB 整帧转换的额外开销） */
private class YuvLuminanceSource(width: Int, height: Int, private val data: ByteArray) :
    LuminanceSource(width, height) {

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        val out = if (row != null && row.size >= width) row else ByteArray(width)
        data.copyInto(out, 0, y * width, (y + 1) * width)
        return out
    }

    override fun getMatrix(): ByteArray = data
}

private class QrFrameAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
            )
        )
    }

    /** 命中后静默 2s：同一张码连续多帧命中只触发一次确认流程 */
    private var lastHitAt = 0L

    override fun analyze(image: ImageProxy) {
        try {
            if (System.currentTimeMillis() - lastHitAt < 2_000) return
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val buffer = plane.buffer
            val gray = ByteArray(w * h)
            if (rowStride == w) {
                buffer.get(gray, 0, w * h)
            } else {
                val row = ByteArray(rowStride)
                for (y in 0 until h) {
                    buffer.get(row, 0, rowStride)
                    System.arraycopy(row, 0, gray, y * w, w)
                }
            }
            val bitmap = BinaryBitmap(HybridBinarizer(YuvLuminanceSource(w, h, gray)))
            val result = reader.decode(bitmap)
            lastHitAt = System.currentTimeMillis()
            onText(result.text)
        } catch (_: NotFoundException) {
            // 本帧没有码：正常，继续下一帧
        } catch (_: Exception) {
            // 单帧解码异常忽略（尺寸/格式边缘情况），不影响后续帧
        } finally {
            image.close()
        }
    }
}
