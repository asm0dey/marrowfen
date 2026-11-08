package samples

// six statements, but the receiver carries no @DslMarker: not a DSL block
val notDsl = with(StringBuilder()) {
    append("a")
    append("b")
    append("c")
    append("d")
    append("e")
    toString()
}
