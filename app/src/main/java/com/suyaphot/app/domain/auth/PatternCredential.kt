package com.suyaphot.app.domain.auth

/** Canonical 3x3 credential path. Node IDs are row-major 0..8. */
object PatternCredential {
    fun isCanonical(chars: CharArray): Boolean {
        if (chars.size !in 6..11 || chars[0] != 'P' || chars[1] != ':') return false
        val nodes = IntArray(chars.size - 2)
        for (index in nodes.indices) {
            val value = chars[index + 2].digitToIntOrNull() ?: return false
            if (value !in 0..8) return false
            nodes[index] = value
        }
        return runCatching { canonicalChars(nodes).contentEquals(chars) }.getOrDefault(false)
    }

    fun normalize(raw: IntArray): IntArray {
        val selected = BooleanArray(9)
        val result = ArrayList<Int>(9)
        for (node in raw) {
            require(node in 0..8) { "Pattern node outside grid" }
            if (selected[node]) continue
            if (result.isNotEmpty()) {
                val previous = result.last()
                val fromRow = previous / 3
                val fromCol = previous % 3
                val toRow = node / 3
                val toCol = node % 3
                val rowDelta = toRow - fromRow
                val colDelta = toCol - fromCol
                if (rowDelta % 2 == 0 && colDelta % 2 == 0 &&
                    (kotlin.math.abs(rowDelta) == 2 || kotlin.math.abs(colDelta) == 2)) {
                    val middle = ((fromRow + toRow) / 2) * 3 + (fromCol + toCol) / 2
                    if (!selected[middle]) {
                        selected[middle] = true
                        result += middle
                    }
                }
            }
            selected[node] = true
            result += node
        }
        require(result.size >= 4) { "Pattern needs at least four nodes" }
        return result.toIntArray()
    }

    fun canonicalChars(raw: IntArray): CharArray {
        val normalized = normalize(raw)
        return CharArray(normalized.size + 2).also { chars ->
            chars[0] = 'P'
            chars[1] = ':'
            normalized.forEachIndexed { index, node -> chars[index + 2] = ('0'.code + node).toChar() }
        }
    }
}
