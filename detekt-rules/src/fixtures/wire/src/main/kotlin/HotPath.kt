package fixtures

// Deliberately over budget: 21 lines and nesting depth 4. A copy sits under core/ to prove the scoping.
fun twentyOneLines(input: List<Int>): Int {
    var total = 0
    total += input.size
    total += input.sum()
    total += input.max()
    total += input.min()
    total += input.first()
    total += input.last()
    total += input.count { it > 0 }
    total += input.count { it < 0 }
    total += input.count { it == 0 }
    total += input.distinct().size
    total += input.sorted().first()
    total += input.sortedDescending().first()
    total += input.filter { it % 2 == 0 }.size
    total += input.filter { it % 2 != 0 }.size
    total += input.map { it * 2 }.sum()
    total += input.map { it * 3 }.sum()
    total += input.map { it * 4 }.sum()
    return total
}

fun depthFour(input: List<Int>): Int {
    var hits = 0
    for (a in input) {
        if (a > 0) {
            for (b in input) {
                if (b > a) {
                    hits++
                }
            }
        }
    }
    return hits
}
