package io.github.hypercar.settings

internal enum class ProjectionTarget(val key: String, val label: String) {
    CarPlay("carplay", "CarPlay 车机"),
    Yadea("yadea", "雅迪仪表"),
}

internal enum class YadeaConnectionMode(val key: String, val label: String) {
    Manual("manual", "手动 IP"),
    P2p("p2p", "P2P 扫描"),
}
