package com.kuikly.kuiklyklinechart.shared.canvas

data class KLineToolbarAction(
    val id: String,
    val label: String,
    val tooltip: String = id,
)

data class KLineToolbarButton(
    val action: KLineToolbarAction,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    fun hit(x: Double, y: Double): Boolean =
        x in left..right && y in top..bottom
}

object KLineToolbar {
    const val HEIGHT: Double = 52.0
    private const val PADDING: Double = 8.0
    private const val GAP: Double = 8.0
    private const val BTN_HEIGHT: Double = 36.0
    private const val BTN_MIN_WIDTH: Double = 64.0

    fun drawToolbar(
        canvas: KLineCanvasAdapter,
        viewportWidth: Double,
        viewportTop: Double,
        actions: List<KLineToolbarAction>,
        selected: Set<String> = emptySet(),
    ): List<KLineToolbarButton> {
        val top = viewportTop
        val bottom = top + HEIGHT
        canvas.drawRect(0.0, top, viewportWidth, bottom, "#F5F5F7FF", "#D0D0D5FF", strokeWidth = 1.0)
        val buttons = mutableListOf<KLineToolbarButton>()
        var cursorX = PADDING
        val btnY = top + (HEIGHT - BTN_HEIGHT) / 2
        actions.forEach { action ->
            val textWidth = (action.label.length.coerceAtLeast(2) * 18.0).coerceAtLeast(BTN_MIN_WIDTH)
            val left = cursorX
            val right = cursorX + textWidth
            val isSelected = action.id in selected
            canvas.drawRect(
                left, btnY, right, btnY + BTN_HEIGHT,
                color = if (isSelected) "#E3E8FFFF" else "#FFFFFFFF",
                strokeColor = if (isSelected) "#4F7CFFFF" else "#C8CCD1FF",
                strokeWidth = 1.0, cornerRadius = 6.0,
            )
            canvas.drawText(
                action.label,
                left + 6.0, btnY + 8.0,
                right - 6.0, btnY + BTN_HEIGHT - 2.0,
                color = "#1D1D1FFF",
                textSize = 14.0,
            )
            buttons += KLineToolbarButton(action, left, btnY, right, btnY + BTN_HEIGHT)
            cursorX = right + GAP
        }
        return buttons
    }

    fun layoutBottom(
        canvas: KLineCanvasAdapter,
        viewportWidth: Double,
        viewportBottom: Double,
        actions: List<KLineToolbarAction>,
        selected: Set<String> = emptySet(),
    ): List<KLineToolbarButton> {
        val top = viewportBottom - HEIGHT
        return drawToolbar(canvas, viewportWidth, top, actions, selected)
    }
}
