package io.github.hypercar.autowake

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.annotation.ChecksSdkIntAtLeast
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.settings.SettingsRevision
import io.github.hypercar.settings.SettingsStore

/** 独立使用 Android 的系统关联 API；不依赖第三方实现或授权材料。 */
object CarCompanionWake {
    private var lastTrigger: Long? = null
    private var lastAddress: String? = null
    private val associationGate = AssociationRequestGate()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private class PendingAssociation(
        val ticket: AssociationRequestGate.Ticket, val context: Context, val result: (String) -> Unit,
    ) {
        var timeout: Runnable? = null
        var createdId: Int? = null
    }
    private var pendingAssociation: PendingAssociation? = null

    private fun finishAssociation(request: PendingAssociation, message: String) {
        if (!associationGate.finish(request.ticket)) return
        request.timeout?.let { mainHandler.removeCallbacks(it) }
        if (pendingAssociation === request) pendingAssociation = null
        HyperCarRepo.log("COMPANION: $message")
        request.result(message)
    }

    private fun completeAssociation(request: PendingAssociation, info: AssociationInfo) {
        if (!supported(request.context)) return
        val address = info.deviceMacAddress?.toString()
        if (!associationGate.isActive(request.ticket)) {
            // 重复 created 回调不重复观察/提示；晚到的新授权仍按系统记录更新标签。
            if (request.createdId == info.id) return
            if (address != null && address.equals(SettingsStore.targetCarMac, ignoreCase = true)) {
                sync(request.context)
                SettingsRevision.bump()
            } else runCatching { manager(request.context)?.disassociate(info.id) }
            return
        }
        if (address == null || !address.equals(SettingsStore.targetCarMac, ignoreCase = true) ||
            !address.equals(request.ticket.address, ignoreCase = true)) {
            runCatching { manager(request.context)?.disassociate(info.id) }
            finishAssociation(request, "目标车机已变化，请重新申请增强发现")
            return
        }
        request.createdId = info.id
        val observing = sync(request.context)
        SettingsRevision.bump()
        finishAssociation(request, if (!observing) "增强发现已授权，但系统观察更新失败，请查看诊断日志"
            else if (SettingsStore.autoConnect) "增强发现已授权"
            else "增强发现已授权，开启上车自动连接后生效")
    }

    /** ActivityResult 与 onAssociationCreated 汇入同一完成出口，返回是否还需等待。 */
    fun onAssociationResult(associationId: Long, approved: Boolean): Boolean {
        val request = pendingAssociation ?: return false
        if (!supported(request.context)) return false
        if (request.ticket.id != associationId) return false
        if (!approved) {
            finishAssociation(request, "未授权增强发现，所选目标车机仍已保存")
            return false
        }
        val info = runCatching { manager(request.context)?.myAssociations?.firstOrNull {
            request.ticket.address.equals(it.deviceMacAddress?.toString(), ignoreCase = true)
        } }.getOrNull()
        if (info != null) completeAssociation(request, info)
        return associationGate.isActive(request.ticket)
    }

    fun cancelAssociation(associationId: Long, message: String) {
        pendingAssociation?.takeIf { it.ticket.id == associationId }?.let { finishAssociation(it, message) }
    }

    @ChecksSdkIntAtLeast(api = 33)
    fun supported(context: Context): Boolean = Build.VERSION.SDK_INT >= 33 &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    private fun manager(context: Context) = context.getSystemService(CompanionDeviceManager::class.java)

    fun isAssociated(context: Context): Boolean {
        val target = SettingsStore.targetCarMac ?: return false
        return associatedAddresses(context).any { target.equals(it, ignoreCase = true) }
    }

    fun associatedAddresses(context: Context): Set<String> {
        if (!supported(context)) return emptySet()
        return runCatching { manager(context)?.myAssociations.orEmpty().mapNotNull {
            it.deviceMacAddress?.toString()?.uppercase(java.util.Locale.ROOT)
        }.toSet() }.getOrDefault(emptySet())
    }

    /** 由设置页显式发起，系统对话框确认后才建立关联；不改自动连接开关。 */
    fun associate(context: Context, pending: (IntentSender) -> Unit, result: (String) -> Unit): Long? {
        fun report(message: String) {
            HyperCarRepo.log("COMPANION: $message")
            result(message)
        }
        val target = SettingsStore.targetCarMac
        if (!supported(context)) { report("此功能需要 Android 13 及系统伴生设备支持"); return null }
        if (target.isNullOrBlank()) {
            report("请先选择目标车机"); return null
        }
        val cdm = manager(context) ?: run { report("系统伴生设备服务不可用"); return null }
        if (isAssociated(context)) {
            report(if (!sync(context)) "增强发现已授权，但系统观察更新失败，请查看诊断日志"
                else if (SettingsStore.autoConnect) "增强发现已授权"
                else "增强发现已授权，开启上车自动连接后生效")
            return null
        }
        val ticket = associationGate.begin(target) ?: run {
            report("系统授权正在处理中，请先完成或取消当前申请")
            return null
        }
        val attempt = PendingAssociation(ticket, context.applicationContext, result)
        pendingAssociation = attempt
        val timeout = Runnable { finishAssociation(attempt, "增强发现授权超时，请确认车机蓝牙已开启后重试") }
        attempt.timeout = timeout
        mainHandler.postDelayed(timeout, 30_000)
        HyperCarRepo.log("COMPANION: 请求系统关联目标车机 $target")
        runCatching {
            val filter = BluetoothDeviceFilter.Builder().setAddress(target).build()
            val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
            cdm.associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    if (!associationGate.isActive(ticket)) return
                    if (!target.equals(SettingsStore.targetCarMac, ignoreCase = true)) {
                        finishAssociation(attempt, "目标车机已变化，请重新申请增强发现")
                        return
                    }
                    if (!associationGate.deliverPending(ticket)) return
                    mainHandler.removeCallbacks(timeout)
                    HyperCarRepo.log("COMPANION: 系统已提供关联确认入口")
                    runCatching { pending(intentSender) }.onFailure { finishAssociation(attempt, "无法打开系统确认：${it.message}") }
                }

                override fun onAssociationCreated(info: AssociationInfo) {
                    completeAssociation(attempt, info)
                }

                override fun onFailure(error: CharSequence?) { finishAssociation(attempt, "关联未完成：${error ?: "系统未找到车机"}") }
            })
        }.onFailure { finishAssociation(attempt, "无法关联车机：${it.message}") }
        return ticket.id
    }

    /** 恢复系统观察；关自动连接时停观察，换车时解除旧目标关联。 */
    @Suppress("DEPRECATION")
    fun sync(context: Context): Boolean {
        if (!supported(context)) return false
        val cdm = manager(context) ?: return false
        return runCatching {
            val target = SettingsStore.targetCarMac
            for (info in cdm.myAssociations) {
                val address = info.deviceMacAddress?.toString() ?: continue
                if (!address.equals(target, ignoreCase = true)) {
                    runCatching { cdm.stopObservingDevicePresence(address) }
                    cdm.disassociate(info.id)
                } else if (SettingsStore.autoConnect) {
                    cdm.startObservingDevicePresence(address)
                } else {
                    cdm.stopObservingDevicePresence(address)
                }
            }
            true
        }.onFailure { HyperCarRepo.log("COMPANION: 系统观察更新失败：${it.message}；继续使用 ACL/Wi-Fi 唤醒") }
            .getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    fun remove(context: Context): Boolean {
        if (!supported(context)) return false
        val cdm = manager(context) ?: return false
        val removed = runCatching {
            cdm.myAssociations.forEach { info ->
                info.deviceMacAddress?.toString()?.let { runCatching { cdm.stopObservingDevicePresence(it) } }
                cdm.disassociate(info.id)
            }
            true
        }.onFailure { HyperCarRepo.log("COMPANION: 解除关联失败：${it.message}") }
            .getOrDefault(false)
        SettingsRevision.bump()
        return removed
    }

    @Synchronized fun appeared(context: Context, info: AssociationInfo) {
        if (!supported(context)) return
        val address = info.deviceMacAddress?.toString() ?: return
        val now = SystemClock.elapsedRealtime()
        val last = lastTrigger.takeIf { address.equals(lastAddress, ignoreCase = true) }
        if (!CompanionWakePolicy.shouldTrigger(SettingsStore.autoConnect, SettingsStore.targetCarMac,
                address, HyperCarRepo.hasCurrentSession, now, last)) return
        // 校验关联仍属于本应用且未被用户撤销。
        if (runCatching { manager(context)?.myAssociations?.any { it.id == info.id &&
                address.equals(it.deviceMacAddress?.toString(), ignoreCase = true) } == true }.getOrDefault(false).not()) return
        lastTrigger = now
        lastAddress = address
        HyperCarRepo.log("COMPANION: 目标车机出现，恢复连接 addr=$address association=${info.id}")
        runCatching { ContextCompat.startForegroundService(context, HyperCarService.companionIntent(context, address)) }
            .onFailure { HyperCarRepo.log("COMPANION: 后台启动失败：${it.message}；可手动连接") }
    }
}
