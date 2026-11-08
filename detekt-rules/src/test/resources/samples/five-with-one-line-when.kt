package samples

@DslMarker
annotation class PipelineDsl

@PipelineDsl
class Pipeline {
    fun step(name: String) = Unit
    fun group(block: Pipeline.() -> Unit) = Unit
}

fun pipeline(block: Pipeline.() -> Unit) = Pipeline().apply(block)

fun withinBudget(mode: Int) = pipeline {
    step("a")
    step("b")
    group {
        step("c")
        step("d")
    }
    when (mode) {
        1 -> step("one")
        2 -> step("two")
        else -> step("other")
    }
    step("e")
}
