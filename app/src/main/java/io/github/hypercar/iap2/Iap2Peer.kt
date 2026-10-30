package io.github.hypercar.iap2

/**
 * 本轮 iAP2 会话的**对端车机**（蓝牙地址 + 名称）。
 *
 * 为什么需要：`AutoStart` 的 ACL 广播可能没被收到（App 是用户手动点「连接」拉起来的，
 * 或进程被冷启动时车机 ACL 早已建立）。这时 [io.github.hypercar.session.HyperCarRepo]
 * 里的 `aclCarMac` 是空的，而"已配对车机"记录需要车机 MAC 才能和蓝牙列表对上——
 * 于是从 iAP2 socket 的 `remoteDevice` 直接取（入站 accept / 出站 connect 两侧都行）。
 */
object Iap2Peer {
    @Volatile
    var address: String? = null
        private set

    @Volatile
    var name: String? = null
        private set

    /** 最近一次记录的单调时间（毫秒），便于判断是不是本轮的值。 */
    @Volatile
    var at: Long = 0L
        private set

    private var softwareIdentity: String? = null

    @Synchronized
    fun rememberIdentification(address: String?, identity: String) {
        if (address != null && address.equals(this.address, ignoreCase = true)) softwareIdentity = identity
    }

    @Synchronized
    fun softwareIdentityFor(address: String?): String? =
        if (address != null && address.equals(this.address, ignoreCase = true)) softwareIdentity else null

    @Synchronized
    fun remember(address: String?, name: String?) {
        val mac = address?.trim().orEmpty()
        if (mac.isEmpty()) return
        softwareIdentity = null
        this.address = mac
        this.name = name?.takeIf { it.isNotBlank() }
        at = System.currentTimeMillis()
    }

    @Synchronized
    fun clear() {
        softwareIdentity = null
        address = null
        name = null
        at = 0L
    }
}
