package tun.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class TunRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("tun")

    override fun instance() = RuleSet(ruleSetId, listOf(::DslBlockBudget))
}
