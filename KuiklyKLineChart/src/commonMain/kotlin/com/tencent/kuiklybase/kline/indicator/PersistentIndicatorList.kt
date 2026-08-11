package com.tencent.kuiklybase.kline.indicator

internal interface PersistentIndicatorIteratorTracker {
    fun recordNodeVisit()
    fun recordItemGet()
}

/** Immutable AVL rope used by indicator snapshots. */
internal class PersistentIndicatorList<T> private constructor(
    private val root: RopeNode<T>,
) : AbstractList<T>() {
    override val size: Int get() = root.size
    internal val treeHeight: Int get() = root.height

    internal fun nodeVisits(index: Int): Int {
        checkElementIndex(index, size)
        var visits = 1
        var node = root
        var offset = index
        while (node is RopeNode.Branch) {
            visits++
            if (offset < node.left.size) node = node.left else {
                offset -= node.left.size
                node = node.right
            }
        }
        return visits
    }

    override fun get(index: Int): T {
        checkElementIndex(index, size)
        var node = root
        var offset = index
        while (node is RopeNode.Branch) {
            if (offset < node.left.size) node = node.left
            else {
                offset -= node.left.size
                node = node.right
            }
        }
        return (node as RopeNode.Leaf).values[offset]
    }

    override fun iterator(): Iterator<T> = trackedListIterator(0, null)

    override fun listIterator(index: Int): ListIterator<T> = trackedListIterator(index, null)

    internal fun trackedListIterator(
        index: Int,
        tracker: PersistentIndicatorIteratorTracker?,
    ): ListIterator<T> {
        if (index !in 0..size) throw IndexOutOfBoundsException("index: $index, size: $size")
        return IndicatorTreeListIterator(root, index, tracker)
    }

    fun replaceRange(
        fromIndex: Int,
        toIndex: Int,
        replacement: List<T>,
        tracker: KLinePerformanceTracker? = null,
        key: KLinePerformanceKey? = null,
    ): PersistentIndicatorList<T> {
        require(fromIndex in 0..toIndex && toIndex <= size)
        if (tracker != null && key != null) tracker.recordRopeSplice(key)
        return PersistentIndicatorList(join(
            join(slice(root, 0, fromIndex, tracker, key), from(replacement, tracker, key).root),
            slice(root, toIndex, size, tracker, key),
        ))
    }

    fun concat(
        suffix: List<T>,
        tracker: KLinePerformanceTracker? = null,
        key: KLinePerformanceKey? = null,
    ): PersistentIndicatorList<T> {
        if (tracker != null && key != null) tracker.recordRopeSplice(key)
        return PersistentIndicatorList(join(root, from(suffix, tracker, key).root))
    }

    fun slice(
        fromIndex: Int,
        toIndex: Int,
        tracker: KLinePerformanceTracker? = null,
        key: KLinePerformanceKey? = null,
    ): PersistentIndicatorList<T> {
        require(fromIndex in 0..toIndex && toIndex <= size)
        return PersistentIndicatorList(slice(root, fromIndex, toIndex, tracker, key))
    }

    companion object {
        private const val LEAF_SIZE = 64

        fun <T> from(
            source: List<T>,
            tracker: KLinePerformanceTracker? = null,
            key: KLinePerformanceKey? = null,
        ): PersistentIndicatorList<T> {
            if (source is PersistentIndicatorList<T>) return source
            if (source.isEmpty()) return PersistentIndicatorList(RopeNode.Empty)
            if (tracker != null && key != null) tracker.recordRopeLeafItemsCopied(key, source.size)
            val leaves = ArrayList<RopeNode<T>>((source.size + LEAF_SIZE - 1) / LEAF_SIZE)
            var start = 0
            while (start < source.size) {
                val end = minOf(source.size, start + LEAF_SIZE)
                val values = ArrayList<T>(end - start)
                for (index in start until end) values += source[index]
                leaves += RopeNode.Leaf(values)
                start = end
            }
            var level = leaves
            while (level.size > 1) {
                val next = ArrayList<RopeNode<T>>((level.size + 1) / 2)
                var index = 0
                while (index < level.size) {
                    next += if (index + 1 < level.size) branch(level[index], level[index + 1]) else level[index]
                    index += 2
                }
                level = next
            }
            return PersistentIndicatorList(level.single())
        }
    }
}

private class IndicatorTreeListIterator<T>(
    root: RopeNode<T>,
    startIndex: Int,
    private val tracker: PersistentIndicatorIteratorTracker?,
) : ListIterator<T> {
    private data class Frame<T>(val branch: RopeNode.Branch<T>, val wentLeft: Boolean)

    private val totalSize = root.size
    private val path = mutableListOf<Frame<T>>()
    private var cursor = startIndex
    private var leaf: RopeNode.Leaf<T>? = null
    private var leafOffset = 0

    init {
        if (root !is RopeNode.Empty) locate(root, if (startIndex == totalSize) totalSize - 1 else startIndex, startIndex == totalSize)
    }

    override fun hasNext() = cursor < totalSize
    override fun hasPrevious() = cursor > 0
    override fun nextIndex() = cursor
    override fun previousIndex() = cursor - 1

    override fun next(): T {
        if (!hasNext()) throw NoSuchElementException()
        if (leafOffset == requireNotNull(leaf).size) moveToNextLeaf()
        tracker?.recordItemGet()
        return requireNotNull(leaf).values[leafOffset++].also { cursor++ }
    }

    override fun previous(): T {
        if (!hasPrevious()) throw NoSuchElementException()
        if (leafOffset == 0) moveToPreviousLeaf()
        leafOffset--
        cursor--
        tracker?.recordItemGet()
        return requireNotNull(leaf).values[leafOffset]
    }

    private fun locate(root: RopeNode<T>, target: Int, atEnd: Boolean) {
        var node = root
        var offset = target
        while (node is RopeNode.Branch) {
            tracker?.recordNodeVisit()
            if (offset < node.left.size) {
                path += Frame(node, wentLeft = true)
                node = node.left
            } else {
                offset -= node.left.size
                path += Frame(node, wentLeft = false)
                node = node.right
            }
        }
        tracker?.recordNodeVisit()
        @Suppress("UNCHECKED_CAST")
        val resolved = node as RopeNode.Leaf<T>
        leaf = resolved
        leafOffset = if (atEnd) resolved.size else offset
    }

    private fun moveToNextLeaf() {
        while (path.isNotEmpty()) {
            val frame = path.removeAt(path.lastIndex)
            tracker?.recordNodeVisit()
            if (frame.wentLeft) {
                path += Frame(frame.branch, wentLeft = false)
                var node: RopeNode<T> = frame.branch.right
                while (node is RopeNode.Branch) {
                    tracker?.recordNodeVisit()
                    path += Frame(node, wentLeft = true)
                    node = node.left
                }
                tracker?.recordNodeVisit()
                @Suppress("UNCHECKED_CAST")
                val resolved = node as RopeNode.Leaf<T>
                leaf = resolved
                leafOffset = 0
                return
            }
        }
        throw NoSuchElementException()
    }

    private fun moveToPreviousLeaf() {
        while (path.isNotEmpty()) {
            val frame = path.removeAt(path.lastIndex)
            tracker?.recordNodeVisit()
            if (!frame.wentLeft) {
                path += Frame(frame.branch, wentLeft = true)
                var node: RopeNode<T> = frame.branch.left
                while (node is RopeNode.Branch) {
                    tracker?.recordNodeVisit()
                    path += Frame(node, wentLeft = false)
                    node = node.right
                }
                tracker?.recordNodeVisit()
                @Suppress("UNCHECKED_CAST")
                val resolved = node as RopeNode.Leaf<T>
                leaf = resolved
                leafOffset = resolved.size
                return
            }
        }
        throw NoSuchElementException()
    }
}

private sealed interface RopeNode<out T> {
    val size: Int
    val height: Int

    data object Empty : RopeNode<Nothing> {
        override val size = 0
        override val height = 0
    }

    class Leaf<T>(val values: List<T>) : RopeNode<T> {
        override val size = values.size
        override val height = 1
    }

    class Branch<T>(val left: RopeNode<T>, val right: RopeNode<T>) : RopeNode<T> {
        override val size = left.size + right.size
        override val height = maxOf(left.height, right.height) + 1
    }
}

private fun <T> branch(left: RopeNode<T>, right: RopeNode<T>): RopeNode<T> = when {
    left.size == 0 -> right
    right.size == 0 -> left
    else -> RopeNode.Branch(left, right)
}

private fun <T> join(left: RopeNode<T>, right: RopeNode<T>): RopeNode<T> {
    if (left.size == 0) return right
    if (right.size == 0) return left
    if (left.height > right.height + 1) {
        left as RopeNode.Branch
        return balance(left.left, join(left.right, right))
    }
    if (right.height > left.height + 1) {
        right as RopeNode.Branch
        return balance(join(left, right.left), right.right)
    }
    return RopeNode.Branch(left, right)
}

private fun <T> balance(left: RopeNode<T>, right: RopeNode<T>): RopeNode<T> {
    if (left.height > right.height + 1) {
        left as RopeNode.Branch
        return if (left.left.height >= left.right.height) {
            branch(left.left, branch(left.right, right))
        } else {
            val pivot = left.right as RopeNode.Branch
            branch(branch(left.left, pivot.left), branch(pivot.right, right))
        }
    }
    if (right.height > left.height + 1) {
        right as RopeNode.Branch
        return if (right.right.height >= right.left.height) {
            branch(branch(left, right.left), right.right)
        } else {
            val pivot = right.left as RopeNode.Branch
            branch(branch(left, pivot.left), branch(pivot.right, right.right))
        }
    }
    return branch(left, right)
}

private fun <T> slice(
    node: RopeNode<T>,
    fromIndex: Int,
    toIndex: Int,
    tracker: KLinePerformanceTracker? = null,
    key: KLinePerformanceKey? = null,
): RopeNode<T> {
    if (tracker != null && key != null) tracker.recordRopeNodeVisit(key)
    if (fromIndex == 0 && toIndex == node.size) return node
    if (fromIndex == toIndex) return RopeNode.Empty
    return when (node) {
        RopeNode.Empty -> RopeNode.Empty
        is RopeNode.Leaf -> {
            if (tracker != null && key != null) tracker.recordRopeLeafItemsCopied(key, toIndex - fromIndex)
            RopeNode.Leaf(node.values.subList(fromIndex, toIndex).toList())
        }
        is RopeNode.Branch -> when {
            toIndex <= node.left.size -> slice(node.left, fromIndex, toIndex, tracker, key)
            fromIndex >= node.left.size -> slice(node.right, fromIndex - node.left.size, toIndex - node.left.size, tracker, key)
            else -> join(
                slice(node.left, fromIndex, node.left.size, tracker, key),
                slice(node.right, 0, toIndex - node.left.size, tracker, key),
            )
        }
    }
}

private fun checkElementIndex(index: Int, size: Int) {
    if (index !in 0 until size) throw IndexOutOfBoundsException("index: $index, size: $size")
}

internal fun <T> List<T>.persistentReplaceRange(
    fromIndex: Int,
    toIndex: Int,
    replacement: List<T>,
    context: KLineIndicatorUpdateContext? = null,
): List<T> = PersistentIndicatorList.from(this).replaceRange(
    fromIndex, toIndex, replacement, context?.performanceTracker, context?.performanceKey,
)

internal fun <T> List<T>.persistentConcat(suffix: List<T>, context: KLineIndicatorUpdateContext? = null): List<T> =
    PersistentIndicatorList.from(this).concat(suffix, context?.performanceTracker, context?.performanceKey)

internal fun <T> List<T>.persistentSlice(
    fromIndex: Int,
    toIndex: Int,
    context: KLineIndicatorUpdateContext? = null,
): List<T> {
    val persistent = PersistentIndicatorList.from(this)
    return persistent.slice(fromIndex, toIndex, context?.performanceTracker, context?.performanceKey)
}
