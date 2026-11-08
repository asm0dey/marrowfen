package samples

@DslMarker
@Target(AnnotationTarget.TYPE)
annotation class PipelineDsl

// the marker sits on the function type only
class Pipeline {
    fun step(name: String) = Unit
}

fun pipeline(block: @PipelineDsl Pipeline.() -> Unit) = Pipeline().apply(block)

val tooLong = pipeline {
    step("a")
    step("b")
    step("c")
    step("d")
    step("e")
    step("f")
}
