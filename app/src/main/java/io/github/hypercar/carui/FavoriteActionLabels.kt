package io.github.hypercar.carui

internal object FavoriteActionLabels {
    private val off = setOf("喜欢", "收藏", "未收藏", "未喜欢", "like", "collect", "favorite", "add to favorites")
    private val on = setOf("已收藏", "已喜欢", "取消喜欢", "取消收藏", "unlike", "uncollect", "unfavorite", "remove from favorites")
    fun supported(label: String): Boolean = label.trim().lowercase().let { it in off || it in on }
    fun liked(label: String): Boolean = label.trim().lowercase() in on
}
