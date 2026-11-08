package samples

@DslMarker
annotation class PipelineDsl

@PipelineDsl
class Pipeline {
    fun step(name: String) = Unit
    fun group(block: Pipeline.() -> Unit) = Unit
}

fun pipeline(block: Pipeline.() -> Unit) = Pipeline().apply(block)

// 4 statements plus a when with a multi-line branch, counted per branch: 6
fun overBudget(mode: Int) = pipeline {
    step("a")
    step("b")
    step("c")
    step("d")
    when (mode) {
        1 -> {
            step("one")
            step("uno")
        }
        else -> step("other")
    }
}
