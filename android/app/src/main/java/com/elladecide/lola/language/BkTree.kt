package com.elladecide.lola.language

/** Índice métrico sobre formas ya normalizadas. */
internal class BkTree {
    private class Node(val word: String) {
        val children = mutableMapOf<Int, Node>()
    }

    private var root: Node? = null

    fun insert(word: String) {
        val first = root
        if (first == null) {
            root = Node(word)
            return
        }
        var node: Node = first
        while (true) {
            val distance = EditDistance.between(node.word, word)
            if (distance == 0) return
            val child = node.children[distance]
            if (child == null) {
                node.children[distance] = Node(word)
                return
            }
            node = child
        }
    }

    fun search(word: String, maxDistance: Int): List<String> {
        val first = root ?: return emptyList()
        val results = mutableListOf<String>()
        val pending = ArrayDeque<Node>().apply { add(first) }
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            val distance = EditDistance.between(node.word, word)
            if (distance <= maxDistance) results += node.word
            val lower = distance - maxDistance
            val upper = distance + maxDistance
            node.children.forEach { (edge, child) ->
                if (edge in lower..upper) pending.add(child)
            }
        }
        return results
    }
}
