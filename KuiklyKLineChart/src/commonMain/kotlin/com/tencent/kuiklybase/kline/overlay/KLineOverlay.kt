package com.tencent.kuiklybase.kline.overlay

enum class KLineOverlayDrawingMode {
    POINT_BY_POINT,
    CONTINUOUS,
}

enum class KLineOverlayMagnetMode {
    NONE,
    WEAK,
    STRONG,
}

data class KLineOverlayPoint(
    val timestamp: Long,
    val value: Double,
) {
    init {
        require(value.isFinite()) { "Overlay point value must be finite" }
    }
}

class KLineOverlayFigureStyle(
    val color: String = "#2F80ED",
    val lineWidth: Double = 1.0,
    lineDash: List<Double> = emptyList(),
    val textSize: Double = 12.0,
) {
    val lineDash: List<Double> = lineDash.toList()

    init {
        require(color.isNotBlank()) { "Overlay style color must not be blank" }
        require(lineWidth.isFinite() && lineWidth > 0.0) { "Overlay line width must be finite and positive" }
        require(lineDash.all { it.isFinite() && it > 0.0 }) { "Overlay line dash values must be finite and positive" }
        require(textSize.isFinite() && textSize > 0.0) { "Overlay text size must be finite and positive" }
    }

    fun copy(
        color: String = this.color,
        lineWidth: Double = this.lineWidth,
        lineDash: List<Double> = this.lineDash,
        textSize: Double = this.textSize,
    ): KLineOverlayFigureStyle = KLineOverlayFigureStyle(color, lineWidth, lineDash, textSize)

    override fun equals(other: Any?): Boolean = other is KLineOverlayFigureStyle &&
        color == other.color && lineWidth == other.lineWidth && lineDash == other.lineDash && textSize == other.textSize

    override fun hashCode(): Int {
        var result = color.hashCode()
        result = 31 * result + lineWidth.hashCode()
        result = 31 * result + lineDash.hashCode()
        return 31 * result + textSize.hashCode()
    }

    override fun toString(): String =
        "KLineOverlayFigureStyle(color=$color, lineWidth=$lineWidth, lineDash=$lineDash, textSize=$textSize)"
}

object KLineOverlayStyleKey {
    const val DEFAULT: String = "default"
    const val TEXT: String = "text"
}

sealed interface KLineOverlayFigure {
    val style: KLineOverlayFigureStyle

    data class HorizontalLine(
        val value: Double,
        override val style: KLineOverlayFigureStyle,
    ) : KLineOverlayFigure {
        init {
            require(value.isFinite()) { "Overlay horizontal-line value must be finite" }
        }
    }

    data class VerticalLine(
        val timestamp: Long,
        override val style: KLineOverlayFigureStyle,
    ) : KLineOverlayFigure

    data class Segment(
        val start: KLineOverlayPoint,
        val end: KLineOverlayPoint,
        override val style: KLineOverlayFigureStyle,
    ) : KLineOverlayFigure

    data class Ray(
        val start: KLineOverlayPoint,
        val through: KLineOverlayPoint,
        override val style: KLineOverlayFigureStyle,
    ) : KLineOverlayFigure

    data class InfiniteLine(
        val start: KLineOverlayPoint,
        val through: KLineOverlayPoint,
        override val style: KLineOverlayFigureStyle,
    ) : KLineOverlayFigure

    class Polyline(
        points: List<KLineOverlayPoint>,
        override val style: KLineOverlayFigureStyle,
    ) : KLineOverlayFigure {
        val points: List<KLineOverlayPoint> = points.toList()

        fun copy(
            points: List<KLineOverlayPoint> = this.points,
            style: KLineOverlayFigureStyle = this.style,
        ): Polyline = Polyline(points, style)

        override fun equals(other: Any?): Boolean =
            other is Polyline && points == other.points && style == other.style

        override fun hashCode(): Int = 31 * points.hashCode() + style.hashCode()

        override fun toString(): String = "Polyline(points=$points, style=$style)"
    }

    data class Text(
        val anchor: KLineOverlayPoint,
        val text: String,
        override val style: KLineOverlayFigureStyle,
    ) : KLineOverlayFigure
}

class KLineOverlayInstance(
    val id: String,
    val templateName: String,
    val groupId: String? = null,
    val paneId: String,
    points: List<KLineOverlayPoint>,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val magnetMode: KLineOverlayMagnetMode = KLineOverlayMagnetMode.NONE,
    val zIndex: Int = 0,
    styles: Map<String, KLineOverlayFigureStyle> = emptyMap(),
    extendData: Map<String, String> = emptyMap(),
) {
    val points: List<KLineOverlayPoint> = points.toList()
    val styles: Map<String, KLineOverlayFigureStyle> = styles.toMap()
    val extendData: Map<String, String> = extendData.toMap()

    init {
        require(id.isNotBlank()) { "Overlay instance id must not be blank" }
        require(templateName.isNotBlank()) { "Overlay template name must not be blank" }
        require(groupId == null || groupId.isNotBlank()) { "Overlay group id must not be blank" }
        require(paneId.isNotBlank()) { "Overlay pane id must not be blank" }
        require(styles.keys.all(String::isNotBlank)) { "Overlay style keys must not be blank" }
        require(extendData.keys.all(String::isNotBlank)) { "Overlay extend-data keys must not be blank" }
    }

    fun copy(
        id: String = this.id,
        templateName: String = this.templateName,
        groupId: String? = this.groupId,
        paneId: String = this.paneId,
        points: List<KLineOverlayPoint> = this.points,
        visible: Boolean = this.visible,
        locked: Boolean = this.locked,
        magnetMode: KLineOverlayMagnetMode = this.magnetMode,
        zIndex: Int = this.zIndex,
        styles: Map<String, KLineOverlayFigureStyle> = this.styles,
        extendData: Map<String, String> = this.extendData,
    ): KLineOverlayInstance = KLineOverlayInstance(
        id, templateName, groupId, paneId, points, visible, locked, magnetMode, zIndex, styles, extendData,
    )

    override fun equals(other: Any?): Boolean = other is KLineOverlayInstance &&
        id == other.id && templateName == other.templateName && groupId == other.groupId && paneId == other.paneId &&
        points == other.points && visible == other.visible && locked == other.locked && magnetMode == other.magnetMode &&
        zIndex == other.zIndex && styles == other.styles && extendData == other.extendData

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + templateName.hashCode()
        result = 31 * result + (groupId?.hashCode() ?: 0)
        result = 31 * result + paneId.hashCode()
        result = 31 * result + points.hashCode()
        result = 31 * result + visible.hashCode()
        result = 31 * result + locked.hashCode()
        result = 31 * result + magnetMode.hashCode()
        result = 31 * result + zIndex
        result = 31 * result + styles.hashCode()
        return 31 * result + extendData.hashCode()
    }

    override fun toString(): String =
        "KLineOverlayInstance(id=$id, templateName=$templateName, groupId=$groupId, paneId=$paneId, " +
            "points=$points, visible=$visible, locked=$locked, magnetMode=$magnetMode, zIndex=$zIndex, " +
            "styles=$styles, extendData=$extendData)"
}

class KLineOverlayConfig(
    val templateName: String,
    val groupId: String? = null,
    val paneId: String,
    points: List<KLineOverlayPoint>,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val magnetMode: KLineOverlayMagnetMode = KLineOverlayMagnetMode.NONE,
    val zIndex: Int = 0,
    styles: Map<String, KLineOverlayFigureStyle> = emptyMap(),
    extendData: Map<String, String> = emptyMap(),
) {
    val points: List<KLineOverlayPoint> = points.toList()
    val styles: Map<String, KLineOverlayFigureStyle> = styles.toMap()
    val extendData: Map<String, String> = extendData.toMap()

    init {
        KLineOverlayInstance(
            id = "validation",
            templateName = templateName,
            groupId = groupId,
            paneId = paneId,
            points = points,
            visible = visible,
            locked = locked,
            magnetMode = magnetMode,
            zIndex = zIndex,
            styles = styles,
            extendData = extendData,
        )
    }

    fun toInstance(id: String): KLineOverlayInstance = KLineOverlayInstance(
        id = id,
        templateName = templateName,
        groupId = groupId,
        paneId = paneId,
        points = points.toList(),
        visible = visible,
        locked = locked,
        magnetMode = magnetMode,
        zIndex = zIndex,
        styles = styles.toMap(),
        extendData = extendData.toMap(),
    )

    fun copy(
        templateName: String = this.templateName,
        groupId: String? = this.groupId,
        paneId: String = this.paneId,
        points: List<KLineOverlayPoint> = this.points,
        visible: Boolean = this.visible,
        locked: Boolean = this.locked,
        magnetMode: KLineOverlayMagnetMode = this.magnetMode,
        zIndex: Int = this.zIndex,
        styles: Map<String, KLineOverlayFigureStyle> = this.styles,
        extendData: Map<String, String> = this.extendData,
    ): KLineOverlayConfig = KLineOverlayConfig(
        templateName, groupId, paneId, points, visible, locked, magnetMode, zIndex, styles, extendData,
    )

    override fun equals(other: Any?): Boolean = other is KLineOverlayConfig &&
        templateName == other.templateName && groupId == other.groupId && paneId == other.paneId &&
        points == other.points && visible == other.visible && locked == other.locked && magnetMode == other.magnetMode &&
        zIndex == other.zIndex && styles == other.styles && extendData == other.extendData

    override fun hashCode(): Int {
        var result = templateName.hashCode()
        result = 31 * result + (groupId?.hashCode() ?: 0)
        result = 31 * result + paneId.hashCode()
        result = 31 * result + points.hashCode()
        result = 31 * result + visible.hashCode()
        result = 31 * result + locked.hashCode()
        result = 31 * result + magnetMode.hashCode()
        result = 31 * result + zIndex
        result = 31 * result + styles.hashCode()
        return 31 * result + extendData.hashCode()
    }

    override fun toString(): String =
        "KLineOverlayConfig(templateName=$templateName, groupId=$groupId, paneId=$paneId, points=$points, " +
            "visible=$visible, locked=$locked, magnetMode=$magnetMode, zIndex=$zIndex, styles=$styles, " +
            "extendData=$extendData)"
}

class KLineOverlayContext internal constructor(
    val instance: KLineOverlayInstance,
) {
    val points: List<KLineOverlayPoint> get() = instance.points
    val extendData: Map<String, String> get() = instance.extendData

    fun style(key: String = KLineOverlayStyleKey.DEFAULT): KLineOverlayFigureStyle =
        instance.styles[key] ?: instance.styles[KLineOverlayStyleKey.DEFAULT] ?: KLineOverlayFigureStyle()
}

interface KLineOverlayTemplate {
    val name: String
    val requiredPointCount: Int
    val drawingMode: KLineOverlayDrawingMode

    fun createFigures(context: KLineOverlayContext): List<KLineOverlayFigure>
}
