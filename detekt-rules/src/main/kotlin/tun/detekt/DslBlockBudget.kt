package tun.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * AD-2: a DSL block holds at most [allowedStatements] lines or sub-blocks.
 *
 * A DSL block is a lambda whose receiver class, one of its supertypes, or the function type itself
 * carries a `@DslMarker` annotation.
 * Each top-level statement counts as one, including one that holds its own lambda (a sub-block,
 * checked on its own) and a `when` whose branches are all one line. A `when` with a multi-line
 * branch counts one per branch.
 */
class DslBlockBudget(config: Config) :
    Rule(config, "A DSL block holds at most `allowedStatements` (configurable, default 5) lines or sub-blocks (AD-2)."),
    RequiresAnalysisApi {

    private val allowedStatements: Int by config(DEFAULT_ALLOWED)

    override fun visitLambdaExpression(lambdaExpression: KtLambdaExpression) {
        super.visitLambdaExpression(lambdaExpression)
        val size = lambdaExpression.bodyExpression?.statements?.sumOf(::weight) ?: return
        if (size > allowedStatements && isDslBlock(lambdaExpression)) {
            val message = "DSL block has $size lines or sub-blocks, the budget is $allowedStatements. " +
                "Move logic into a plain function or a nested block."
            report(Finding(Entity.from(lambdaExpression), message))
        }
    }

    private fun weight(statement: KtExpression): Int =
        if (statement is KtWhenExpression && statement.entries.any { '\n' in it.text }) statement.entries.size else 1

    // Kotlin applies a @DslMarker through the receiver class, its supertypes, or an annotation on the function type.
    private fun isDslBlock(lambda: KtLambdaExpression): Boolean = analyze(lambda) {
        val type = lambda.expressionType as? KaFunctionType ?: return@analyze false
        val receiver = type.receiverType ?: return@analyze false
        val receiverClasses = (listOf(receiver) + receiver.allSupertypes).mapNotNull { it.expandedSymbol }
        // the lambda's inferred type drops type annotations, so read them from the parameter it is passed to
        val call = lambda.getStrictParentOfType<KtCallElement>()?.resolveToCall()?.singleFunctionCallOrNull()
        val parameterType = call?.argumentMapping?.get(lambda)?.symbol?.returnType
        val annotations = type.annotations + parameterType?.annotations.orEmpty() +
            receiverClasses.flatMap { it.annotations }
        annotations.any { annotation ->
            val annotationClass = annotation.classId?.let { findClass(it) }
            annotationClass != null && DSL_MARKER in annotationClass.annotations
        }
    }

    private companion object {
        const val DEFAULT_ALLOWED = 5
        val DSL_MARKER = ClassId.topLevel(FqName("kotlin.DslMarker"))
    }
}
