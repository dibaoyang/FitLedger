package com.fitledger.data

import com.fitledger.domain.*
import org.json.JSONArray
import org.json.JSONObject

/** Versioned inside the existing plan JSON; legacy plans do not gain an advanced configuration. */
object AdvancedPlanCodec {
    const val VERSION = 1

    fun encode(plan: AdvancedPlan): JSONObject {
        plan.validateConfiguration()
        return JSONObject().apply {
            put("version", VERSION)
            put("templates", JSONArray(plan.templates.map(::template)))
            put("schedule", schedule(plan.schedule))
            put("reference", JSONObject().put("mode", plan.reference.mode.name)
                .put("weight", plan.reference.weight).put("lean", plan.reference.lean))
            put("weekly", JSONObject().put("mode", plan.weekly.mode.name).put("amount", plan.weekly.amount)
                .put("weights", JSONArray(plan.weekly.weights)))
            put("exercise", JSONObject().put("fraction", plan.exercise.fraction)
                .put("maximumKcal", plan.exercise.maximumKcal ?: JSONObject.NULL)
                .put("destination", plan.exercise.destination?.name ?: JSONObject.NULL))
        }
    }

    fun decode(json: JSONObject): AdvancedPlan {
        require(integer(json, "version") == VERSION) { "不支持此高级计划版本，请升级 App" }
        val reference = obj(json, "reference")
        val weekly = obj(json, "weekly")
        val exercise = obj(json, "exercise")
        return AdvancedPlan(
            templates = objects(json, "templates", 50).map(::template),
            schedule = schedule(obj(json, "schedule")),
            reference = BodyReference(choice(reference, "mode"), number(reference, "weight"), number(reference, "lean")),
            weekly = WeeklyBudget(choice(weekly, "mode"), number(weekly, "amount"), numbers(weekly, "weights", 7)),
            exercise = ExerciseAllocation(number(exercise, "fraction"), nullableNumber(exercise, "maximumKcal"),
                nullableText(exercise, "destination")?.let { enumValueOf<Macro>(it) })
        ).also { it.validateConfiguration() }
    }

    private fun template(value: PlanTemplate) = JSONObject().apply {
        put("id", value.id); put("name", value.name)
        put("budget", JSONObject().put("mode", value.budget.mode.name).put("value", value.budget.value)
            .put("expenditureSource", value.budget.expenditureSource.name).put("manualExpenditure", value.budget.manualExpenditure))
        put("macros", JSONObject().put("mode", value.macros.mode.name)
            .put("carbs", spec(value.macros.carbs)).put("protein", spec(value.macros.protein)).put("fat", spec(value.macros.fat))
            .put("autoMacro", value.macros.autoMacro.name).put("adjustmentOrder", JSONArray(value.macros.adjustmentOrder.map { it.name })))
        put("consistency", value.consistency.name)
        put("carbSchedule", JSONObject().put("mode", value.carbSchedule.mode.name)
            .put("multipliers", JSONArray(value.carbSchedule.multipliers)).put("start", value.carbSchedule.start)
            .put("intervalDays", value.carbSchedule.intervalDays).put("decrementGrams", value.carbSchedule.decrementGrams)
            .put("minimumGrams", value.carbSchedule.minimumGrams))
    }

    private fun template(json: JSONObject): PlanTemplate {
        val budget = obj(json, "budget")
        val macros = obj(json, "macros")
        val carbs = obj(json, "carbSchedule")
        return PlanTemplate(
            id = text(json, "id"), name = text(json, "name"),
            budget = EnergyBudget(choice(budget, "mode"), number(budget, "value"), choice(budget, "expenditureSource"), number(budget, "manualExpenditure")),
            macros = MacroAllocation(choice(macros, "mode"), spec(obj(macros, "carbs")), spec(obj(macros, "protein")),
                spec(obj(macros, "fat")), choice(macros, "autoMacro"), strings(macros, "adjustmentOrder", 3).map { enumValueOf<Macro>(it) }),
            consistency = choice(json, "consistency"),
            carbSchedule = CarbSchedule(choice(carbs, "mode"), numbers(carbs, "multipliers", 7), whole(carbs, "start"),
                integer(carbs, "intervalDays"), number(carbs, "decrementGrams"), number(carbs, "minimumGrams"))
        )
    }

    private fun spec(value: MacroSpec) = JSONObject()
        .put("rule", JSONObject().put("basis", value.rule.basis.name).put("value", value.rule.value))
        .put("minimum", value.minimum).put("maximum", value.maximum ?: JSONObject.NULL)

    private fun spec(json: JSONObject): MacroSpec {
        val rule = obj(json, "rule")
        return MacroSpec(Rule(choice(rule, "basis"), number(rule, "value")), number(json, "minimum"), nullableNumber(json, "maximum"))
    }

    private fun schedule(value: TemplateSchedule) = JSONObject().apply {
        put("mode", value.mode.name); put("start", value.start); put("singleTemplate", value.singleTemplate)
        put("weeklyTemplates", JSONArray(value.weeklyTemplates)); put("cycleTemplates", JSONArray(value.cycleTemplates))
        put("trainingTemplate", value.trainingTemplate); put("restTemplate", value.restTemplate)
        put("trainingDay", value.trainingDay)
        put("stages", JSONArray(value.stages.map { JSONObject().put("startOffsetDays", it.startOffsetDays).put("templateId", it.templateId) }))
    }

    private fun schedule(json: JSONObject) = TemplateSchedule(
        mode = choice(json, "mode"), start = whole(json, "start"), singleTemplate = text(json, "singleTemplate"),
        weeklyTemplates = strings(json, "weeklyTemplates", 7), cycleTemplates = strings(json, "cycleTemplates", 366),
        trainingTemplate = text(json, "trainingTemplate"), restTemplate = text(json, "restTemplate"),
        stages = objects(json, "stages", 100).map { PlanStage(integer(it, "startOffsetDays"), text(it, "templateId")) },
        trainingDay = boolean(json, "trainingDay")
    )

    // JSONObject's numeric/boolean accessors coerce strings and truncate fractions. Reject those
    // values before constructing a plan so corrupted backups cannot silently change its meaning.
    private fun obj(json: JSONObject, key: String): JSONObject {
        val value = json.get(key)
        require(value is JSONObject) { "高级计划中的 $key 必须是对象" }
        return value
    }

    private fun number(json: JSONObject, key: String): Double {
        val value = json.get(key)
        require(value is Number && value.toDouble().isFinite()) { "高级计划中的 $key 必须是有限数字" }
        return value.toDouble()
    }

    private fun whole(json: JSONObject, key: String): Long {
        val value = number(json, key)
        require(value >= -9007199254740991.0 && value <= 9007199254740991.0 && value == value.toLong().toDouble()) {
            "高级计划中的 $key 必须是有效整数"
        }
        return value.toLong()
    }

    private fun integer(json: JSONObject, key: String): Int {
        val value = whole(json, key)
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "高级计划中的 $key 超出范围" }
        return value.toInt()
    }

    private fun text(json: JSONObject, key: String): String {
        val value = json.get(key)
        require(value is String && value.length <= 80 && value.isNotBlank()) { "高级计划中的 $key 无效" }
        return value
    }

    private inline fun <reified E : Enum<E>> choice(json: JSONObject, key: String): E = enumValueOf(text(json, key))

    private fun boolean(json: JSONObject, key: String): Boolean {
        val value = json.get(key)
        require(value is Boolean) { "高级计划中的 $key 必须为开关值" }
        return value
    }

    private fun nullableNumber(json: JSONObject, key: String): Double? {
        require(json.has(key)) { "高级计划缺少 $key" }
        return if (json.isNull(key)) null else number(json, key)
    }

    private fun nullableText(json: JSONObject, key: String): String? {
        require(json.has(key)) { "高级计划缺少 $key" }
        return if (json.isNull(key)) null else text(json, key)
    }

    private fun array(json: JSONObject, key: String, maximum: Int): JSONArray {
        val value = json.get(key)
        require(value is JSONArray && value.length() <= maximum) { "高级计划中的 $key 数量过多或格式无效" }
        return value
    }

    private fun objects(json: JSONObject, key: String, maximum: Int): List<JSONObject> {
        val values = array(json, key, maximum)
        return (0 until values.length()).map { index ->
            val value = values.get(index)
            require(value is JSONObject) { "高级计划中的 $key 项目必须是对象" }
            value
        }
    }

    private fun strings(json: JSONObject, key: String, maximum: Int): List<String> {
        val values = array(json, key, maximum)
        return (0 until values.length()).map { index ->
            val value = values.get(index)
            require(value is String && value.isNotBlank() && value.length <= 80) { "高级计划中的 $key 项目必须是有效文字" }
            value
        }
    }

    private fun numbers(json: JSONObject, key: String, maximum: Int): List<Double> {
        val values = array(json, key, maximum)
        return (0 until values.length()).map { index ->
            val value = values.get(index)
            require(value is Number && value.toDouble().isFinite()) { "高级计划中的 $key 项目必须是有限数字" }
            value.toDouble()
        }
    }
}
