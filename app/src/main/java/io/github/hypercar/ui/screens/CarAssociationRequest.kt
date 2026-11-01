package io.github.hypercar.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.hypercar.autowake.CarCompanionWake
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.settings.SettingsRevision
import kotlinx.coroutines.delay

/** 选车即申请增强发现，不再提供第二个选车/授权入口。 */
@Composable
internal fun rememberCarSelection(): (String?, String?) -> Unit {
    val context = LocalContext.current
    var busy by rememberSaveable { mutableStateOf(false) }
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    var requestId by rememberSaveable { mutableIntStateOf(0) }
    var launchedRequestId by rememberSaveable { mutableIntStateOf(0) }
    var associationId by rememberSaveable { mutableLongStateOf(0) }
    fun report(message: String) {
        HyperCarRepo.log("COMPANION: $message")
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (launchedRequestId != requestId) return@rememberLauncherForActivityResult
        dialogOpen = false
        busy = CarCompanionWake.onAssociationResult(associationId, it.resultCode == Activity.RESULT_OK)
        SettingsRevision.bump()
    }
    LaunchedEffect(requestId, busy, dialogOpen) {
        if (busy && !dialogOpen) {
            delay(30_000)
            if (busy && !dialogOpen) {
                busy = false
                CarCompanionWake.cancelAssociation(associationId, "增强发现授权超时，请确认车机蓝牙已开启，再点击该车机重试")
            }
        }
    }
    return { mac, name ->
        if (mac == null) {
            requestId++
            busy = false
            dialogOpen = false
            CarCompanionWake.cancelAssociation(associationId, "已取消旧车机授权申请")
            associationId = 0
            HyperCarRepo.updateTargetCar(null, null)
        } else if (busy) {
            report("正在等待系统授权，请先完成或取消当前申请")
        } else {
            HyperCarRepo.updateTargetCar(mac, name)
            if (CarCompanionWake.supported(context)) {
                busy = true
                val attempt = ++requestId
                if (!CarCompanionWake.isAssociated(context)) {
                    Toast.makeText(context, "目标车机已选择，正在请求增强发现授权", Toast.LENGTH_SHORT).show()
                }
                associationId = CarCompanionWake.associate(context,
                    pending = {
                        if (attempt == requestId && busy) {
                            launchedRequestId = attempt
                            launcher.launch(IntentSenderRequest.Builder(it).build())
                            dialogOpen = true
                        }
                    },
                    result = {
                        if (attempt == requestId) {
                            busy = false
                            dialogOpen = false
                            SettingsRevision.bump()
                            // 业务层已记录唯一结果，界面只显示提示。
                            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                        }
                    },
                ) ?: 0
            }
        }
    }
}
