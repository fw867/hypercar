package io.github.hypercar.carui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hypercar.BuildConfig
import io.github.hypercar.R

@Composable
fun CarAboutPage(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val appInfo = context.applicationInfo
    val label = remember { pm.getApplicationLabel(appInfo).toString() }
    val icon = remember { drawableToBitmap(appInfo.loadIcon(pm), 128) }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(CarPanel)
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.ArrowBack, "返回", tint = CarInk, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("关于应用", color = CarInk, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(18.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CarCard).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            icon?.let {
                Image(it.asImageBitmap(), "应用图标",
                    Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)))
                Spacer(Modifier.height(14.dp))
            }
            Text(label, color = CarInk, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("车机自绘界面 · CarPlay / AirPlay", color = CarMuted, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            AboutRow("版本", BuildConfig.VERSION_NAME)
            AboutRow("版本号", BuildConfig.VERSION_CODE.toString())
            AboutRow("作者", context.getString(R.string.app_author))
            AboutRow("项目", context.getString(R.string.app_repo))
            AboutRow("包名", context.packageName)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "本应用仅用于个人研究，与 BMW / Apple / 高德无隶属关系。",
            color = CarMuted, fontSize = 12.sp,
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = CarMuted, fontSize = 14.sp, modifier = Modifier.width(76.dp))
        Text(value, color = CarInk, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap? = runCatching {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
    } else {
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        }
    }
}.getOrNull()
