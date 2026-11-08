package samples

@DslMarker
annotation class PipelineDsl

// the marker sits on a supertype only
@PipelineDsl
interface Scope

class Pipeline : Scope {
    fun step(name: String) = Unit
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
