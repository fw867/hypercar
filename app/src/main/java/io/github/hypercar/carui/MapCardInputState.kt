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

/** Once a finger has dragged or a second pointer joined, lifting cannot become a card click. */
internal class MapCardTap(private val slop: Int, private val longPressMs: Int) {
    private var x = 0f
    private var y = 0f
    private var eligible = false

    fun down(x: Float, y: Float) {
        this.x = x
        this.y = y
        eligible = true
    }

    fun observe(x: Float, y: Float, pointers: Int) {
        val dx = x - this.x
        val dy = y - this.y
        if (pointers != 1 || dx * dx + dy * dy > slop.toFloat() * slop) eligible = false
    }

    fun isTap(durationMs: Long) = eligible && durationMs in 0 until longPressMs.toLong()
}
