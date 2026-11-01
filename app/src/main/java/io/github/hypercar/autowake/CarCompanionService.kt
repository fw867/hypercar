package io.github.hypercar.autowake

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import androidx.annotation.RequiresApi
import io.github.hypercar.session.HyperCarRepo

@RequiresApi(33)
class CarCompanionService : CompanionDeviceService() {
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        CarCompanionWake.appeared(applicationContext, associationInfo)
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        // 设备出现观察可能包含 BLE；不能据此拆除已交接至 Wi-Fi 的 CarPlay。
        HyperCarRepo.log("COMPANION: 设备不再可见 association=${associationInfo.id}，会话由现有链路判断")
    }
}
