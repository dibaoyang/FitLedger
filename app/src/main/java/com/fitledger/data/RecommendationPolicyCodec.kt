package com.fitledger.data

import com.fitledger.domain.MacroTolerance
import com.fitledger.domain.RecommendationPolicy
import org.json.JSONObject

/** Strict for present values; absent legacy configuration means the unrestricted policy. */
object RecommendationPolicyCodec {
    private const val MAX_BYTES = 16 * 1024

    fun encode(policy: RecommendationPolicy): JSONObject {
        policy.validate()
        fun macro(value: MacroTolerance) = JSONObject()
            .put("under", value.under ?: JSONObject.NULL)
            .put("over", value.over ?: JSONObject.NULL)
        return JSONObject()
            .put("dailyBudgetYuan", policy.dailyBudgetYuan ?: JSONObject.NULL)
            .put("carbs", macro(policy.carbs))
            .put("protein", macro(policy.protein))
            .put("fat", macro(policy.fat))
    }

    fun decode(text: String): RecommendationPolicy {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "推荐约束配置过大" }
        return decode(JSONObject(text))
    }

    fun decode(json: JSONObject): RecommendationPolicy {
        fun number(owner: JSONObject, key: String): Double? {
            if (!owner.has(key) || owner.isNull(key)) return null
            require(owner.get(key) is Number) { "推荐约束 $key 须填写有效数值" }
            return owner.getDouble(key).also {
                require(it.isFinite() && it >= 0) { "推荐约束 $key 须为不小于 0 的有效数值" }
            }
        }
        fun macro(key: String): MacroTolerance {
            if (!json.has(key)) return MacroTolerance()
            require(json.get(key) is JSONObject) { "推荐约束 $key 的格式无效" }
            val value = json.getJSONObject(key)
            return MacroTolerance(number(value, "under"), number(value, "over"))
        }
        return RecommendationPolicy(number(json, "dailyBudgetYuan"), macro("carbs"), macro("protein"), macro("fat"))
            .also { it.validate() }
    }
}
