package io.github.hypercar.carui

/** One immutable input binding per card surface handoff; a gesture never retargets mid-stream. */
internal class MapCardInputState {
    class Binding(val source: Any, val displayId: Int, val width: Int, val height: Int) {
        fun scaleFrom(viewWidth: Int, viewHeight: Int): Pair<Float, Float> {
            require(viewWidth > 0 && viewHeight > 0)
            return width.toFloat() / viewWidth to height.toFloat() / viewHeight
        }
    }

    @Volatile var current: Binding? = null
        private set

    fun bind(source: Any, displayId: Int, width: Int, height: Int) {
        require(displayId > 0 && width > 0 && height > 0)
        current = Binding(source, displayId, width, height)
    }

    fun acquire(source: Any): Binding? = current?.takeIf { it.source === source }
    fun accepts(binding: Binding): Boolean = current === binding
    fun clear() { current = null }
}
