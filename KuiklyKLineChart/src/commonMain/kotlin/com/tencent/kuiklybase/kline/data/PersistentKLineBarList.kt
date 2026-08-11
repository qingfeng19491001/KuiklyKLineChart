package com.tencent.kuiklybase.kline.data

internal interface KLineBarRopeTracker {
    fun recordBarRopeSplice()
    fun recordBarRopeNodeVisit()
    fun recordBarLeafItemsCopied(count: Int)
    fun recordBarIteratorItemGet()
    fun recordBarIteratorNodeVisit()
}

/** Immutable timestamp-ordered AVL rope used by store snapshots. */
internal class PersistentKLineBarList internal constructor(
    private val root: BarNode,
) : AbstractList<KLineBar>() {
    override val size: Int get() = root.size
    internal val height: Int get() = root.height
    internal val firstTimestamp: Long? get() = root.firstTimestamp
    internal val lastTimestamp: Long? get() = root.lastTimestamp

    override fun get(index: Int): KLineBar = get(index, null)

    internal fun get(index: Int, tracker: KLineBarRopeTracker?): KLineBar {
        checkElementIndex(index, size)
        var node = root
        var offset = index
        while (node is BarNode.Branch) {
            tracker?.recordBarRopeNodeVisit()
            if (offset < node.left.size) node = node.left else {
                offset -= node.left.size
                node = node.right
            }
        }
        tracker?.recordBarRopeNodeVisit()
        return (node as BarNode.Leaf).values[offset]
    }

    override fun iterator(): Iterator<KLineBar> = trackedListIterator(0, null)

    override fun listIterator(index: Int): ListIterator<KLineBar> = trackedListIterator(index, null)

    internal fun trackedListIterator(index: Int, tracker: KLineBarRopeTracker?): ListIterator<KLineBar> {
        if (index !in 0..size) throw IndexOutOfBoundsException("index: $index, size: $size")
        return TreeListIterator(root, index, tracker)
    }

    internal fun concat(suffix: List<KLineBar>, tracker: KLineBarRopeTracker?): PersistentKLineBarList {
        if (suffix.isEmpty()) return this
        tracker?.recordBarRopeSplice()
        return PersistentKLineBarList(join(root, persistentKLineBars(suffix, tracker).root, tracker))
    }

    internal fun prepend(prefix: List<KLineBar>, tracker: KLineBarRopeTracker?): PersistentKLineBarList {
        if (prefix.isEmpty()) return this
        tracker?.recordBarRopeSplice()
        return PersistentKLineBarList(join(persistentKLineBars(prefix, tracker).root, root, tracker))
    }

    internal fun replaceRange(
        fromIndex: Int,
        toIndex: Int,
        replacement: List<KLineBar>,
        tracker: KLineBarRopeTracker?,
    ): PersistentKLineBarList {
        require(fromIndex in 0..toIndex && toIndex <= size)
        tracker?.recordBarRopeSplice()
        return PersistentKLineBarList(join(
            join(slice(root, 0, fromIndex, tracker), persistentKLineBars(replacement, tracker).root, tracker),
            slice(root, toIndex, size, tracker),
            tracker,
        ))
    }

    internal fun exactTimestampIndex(timestamp: Long, tracker: KLineBarRopeTracker? = null): Int? {
        var node = root
        var offset = 0
        while (node is BarNode.Branch) {
            tracker?.recordBarRopeNodeVisit()
            if (timestamp <= requireNotNull(node.left.lastTimestamp)) node = node.left else {
                offset += node.left.size
                node = node.right
            }
        }
        tracker?.recordBarRopeNodeVisit()
        if (node !is BarNode.Leaf) return null
        val local = node.values.binarySearchBy(timestamp) { it.timestamp }
        return if (local >= 0) offset + local else null
    }

    internal fun lowerBoundTimestamp(timestamp: Long, tracker: KLineBarRopeTracker? = null): Int {
        var node = root
        var offset = 0
        while (node is BarNode.Branch) {
            tracker?.recordBarRopeNodeVisit()
            if (timestamp <= requireNotNull(node.left.lastTimestamp)) node = node.left else {
                offset += node.left.size
                node = node.right
            }
        }
        tracker?.recordBarRopeNodeVisit()
        if (node !is BarNode.Leaf) return 0
        val local = node.values.binarySearchBy(timestamp) { it.timestamp }
        return offset + if (local >= 0) local else -local - 1
    }

    internal fun nearestTimestampIndex(timestamp: Long, tracker: KLineBarRopeTracker? = null): Int? {
        if (isEmpty()) return null
        exactTimestampIndex(timestamp, tracker)?.let { return it }
        val insertion = lowerBoundTimestamp(timestamp, tracker)
        if (insertion == 0) return 0
        if (insertion == size) return lastIndex
        val beforeDistance = timestamp.toULong() - get(insertion - 1, tracker).timestamp.toULong()
        val afterDistance = get(insertion, tracker).timestamp.toULong() - timestamp.toULong()
        return if (beforeDistance <= afterDistance) insertion - 1 else insertion
    }

}

private const val BAR_LEAF_SIZE = 64

internal fun emptyPersistentKLineBars(): PersistentKLineBarList = PersistentKLineBarList(BarNode.Empty)

internal fun persistentKLineBars(
    source: List<KLineBar>,
    tracker: KLineBarRopeTracker?,
): PersistentKLineBarList {
    if (source is PersistentKLineBarList) return source
    if (source.isEmpty()) return emptyPersistentKLineBars()
    val leaves = ArrayList<BarNode>((source.size + BAR_LEAF_SIZE - 1) / BAR_LEAF_SIZE)
    val iterator = source.iterator()
    while (iterator.hasNext()) {
        val values = ArrayList<KLineBar>(BAR_LEAF_SIZE)
        while (values.size < BAR_LEAF_SIZE && iterator.hasNext()) values += iterator.next()
        tracker?.recordBarLeafItemsCopied(values.size)
        leaves += BarNode.Leaf(values)
    }
    var level = leaves
    while (level.size > 1) {
        val next = ArrayList<BarNode>((level.size + 1) / 2)
        var index = 0
        while (index < level.size) {
            next += if (index + 1 < level.size) branch(level[index], level[index + 1]) else level[index]
            index += 2
        }
        level = next
    }
    return PersistentKLineBarList(level.single())
}

internal sealed interface BarNode {
    val size: Int
    val height: Int
    val firstTimestamp: Long?
    val lastTimestamp: Long?

    data object Empty : BarNode {
        override val size = 0
        override val height = 0
        override val firstTimestamp: Long? = null
        override val lastTimestamp: Long? = null
    }

    class Leaf(val values: List<KLineBar>) : BarNode {
        override val size = values.size
        override val height = 1
        override val firstTimestamp = values.first().timestamp
        override val lastTimestamp = values.last().timestamp
    }

    class Branch(val left: BarNode, val right: BarNode) : BarNode {
        override val size = left.size + right.size
        override val height = maxOf(left.height, right.height) + 1
        override val firstTimestamp = left.firstTimestamp
        override val lastTimestamp = right.lastTimestamp
    }
}

private fun branch(left: BarNode, right: BarNode): BarNode = when {
    left.size == 0 -> right
    right.size == 0 -> left
    else -> BarNode.Branch(left, right)
}

private fun join(left: BarNode, right: BarNode, tracker: KLineBarRopeTracker?): BarNode {
    tracker?.recordBarRopeNodeVisit()
    if (left.size == 0) return right
    if (right.size == 0) return left
    if (left.height > right.height + 1) {
        left as BarNode.Branch
        return balance(left.left, join(left.right, right, tracker), tracker)
    }
    if (right.height > left.height + 1) {
        right as BarNode.Branch
        return balance(join(left, right.left, tracker), right.right, tracker)
    }
    return BarNode.Branch(left, right)
}

private fun balance(left: BarNode, right: BarNode, tracker: KLineBarRopeTracker?): BarNode {
    tracker?.recordBarRopeNodeVisit()
    if (left.height > right.height + 1) {
        left as BarNode.Branch
        return if (left.left.height >= left.right.height) {
            branch(left.left, branch(left.right, right))
        } else {
            val pivot = left.right as BarNode.Branch
            branch(branch(left.left, pivot.left), branch(pivot.right, right))
        }
    }
    if (right.height > left.height + 1) {
        right as BarNode.Branch
        return if (right.right.height >= right.left.height) {
            branch(branch(left, right.left), right.right)
        } else {
            val pivot = right.left as BarNode.Branch
            branch(branch(left, pivot.left), branch(pivot.right, right.right))
        }
    }
    return branch(left, right)
}

private fun slice(node: BarNode, fromIndex: Int, toIndex: Int, tracker: KLineBarRopeTracker?): BarNode {
    tracker?.recordBarRopeNodeVisit()
    if (fromIndex == 0 && toIndex == node.size) return node
    if (fromIndex == toIndex) return BarNode.Empty
    return when (node) {
        BarNode.Empty -> BarNode.Empty
        is BarNode.Leaf -> {
            tracker?.recordBarLeafItemsCopied(toIndex - fromIndex)
            BarNode.Leaf(node.values.subList(fromIndex, toIndex).toList())
        }
        is BarNode.Branch -> when {
            toIndex <= node.left.size -> slice(node.left, fromIndex, toIndex, tracker)
            fromIndex >= node.left.size -> slice(node.right, fromIndex - node.left.size, toIndex - node.left.size, tracker)
            else -> join(
                slice(node.left, fromIndex, node.left.size, tracker),
                slice(node.right, 0, toIndex - node.left.size, tracker),
                tracker,
            )
        }
    }
}

private class TreeListIterator(
    root: BarNode,
    startIndex: Int,
    private val tracker: KLineBarRopeTracker?,
) : ListIterator<KLineBar> {
    private data class Frame(val branch: BarNode.Branch, val wentLeft: Boolean)

    private val totalSize = root.size
    private val path = mutableListOf<Frame>()
    private var cursor = startIndex
    private var leaf: BarNode.Leaf? = null
    private var leafOffset = 0

    init {
        if (root !is BarNode.Empty) locate(root, if (startIndex == totalSize) totalSize - 1 else startIndex, startIndex == totalSize)
    }

    override fun hasNext() = cursor < totalSize
    override fun hasPrevious() = cursor > 0
    override fun nextIndex() = cursor
    override fun previousIndex() = cursor - 1

    override fun next(): KLineBar {
        if (!hasNext()) throw NoSuchElementException()
        if (leafOffset == requireNotNull(leaf).size) moveToNextLeaf()
        tracker?.recordBarIteratorItemGet()
        return requireNotNull(leaf).values[leafOffset++].also { cursor++ }
    }

    override fun previous(): KLineBar {
        if (!hasPrevious()) throw NoSuchElementException()
        if (leafOffset == 0) moveToPreviousLeaf()
        leafOffset--
        cursor--
        tracker?.recordBarIteratorItemGet()
        return requireNotNull(leaf).values[leafOffset]
    }

    private fun locate(root: BarNode, target: Int, atEnd: Boolean) {
        var node = root
        var offset = target
        while (node is BarNode.Branch) {
            tracker?.recordBarIteratorNodeVisit()
            if (offset < node.left.size) {
                path += Frame(node, wentLeft = true)
                node = node.left
            } else {
                offset -= node.left.size
                path += Frame(node, wentLeft = false)
                node = node.right
            }
        }
        tracker?.recordBarIteratorNodeVisit()
        leaf = node as BarNode.Leaf
        leafOffset = if (atEnd) node.size else offset
    }

    private fun moveToNextLeaf() {
        while (path.isNotEmpty()) {
            val frame = path.removeAt(path.lastIndex)
            tracker?.recordBarIteratorNodeVisit()
            if (frame.wentLeft) {
                path += Frame(frame.branch, wentLeft = false)
                var node: BarNode = frame.branch.right
                while (node is BarNode.Branch) {
                    tracker?.recordBarIteratorNodeVisit()
                    path += Frame(node, wentLeft = true)
                    node = node.left
                }
                tracker?.recordBarIteratorNodeVisit()
                leaf = node as BarNode.Leaf
                leafOffset = 0
                return
            }
        }
        throw NoSuchElementException()
    }

    private fun moveToPreviousLeaf() {
        while (path.isNotEmpty()) {
            val frame = path.removeAt(path.lastIndex)
            tracker?.recordBarIteratorNodeVisit()
            if (!frame.wentLeft) {
                path += Frame(frame.branch, wentLeft = true)
                var node: BarNode = frame.branch.left
                while (node is BarNode.Branch) {
                    tracker?.recordBarIteratorNodeVisit()
                    path += Frame(node, wentLeft = false)
                    node = node.right
                }
                tracker?.recordBarIteratorNodeVisit()
                leaf = node as BarNode.Leaf
                leafOffset = node.size
                return
            }
        }
        throw NoSuchElementException()
    }
}

private fun checkElementIndex(index: Int, size: Int) {
    if (index !in 0 until size) throw IndexOutOfBoundsException("index: $index, size: $size")
}
