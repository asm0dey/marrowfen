package samples

@DslMarker
annotation class PipelineDsl

@PipelineDsl
class Pipeline {
    fun step(name: String) = Unit
    fun group(block: Pipeline.() -> Unit) = Unit
}

fun pipeline(block: Pipeline.() -> Unit) = Pipeline().apply(block)

val tooLong = pipeline {
    step("a")
    step("b")
    step("c")
    step("d")
    step("e")
    step("f")
}
