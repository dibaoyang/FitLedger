package com.fitledger.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.fitledger.domain.*
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

val Context.settingsStore by preferencesDataStore("settings", produceMigrations={listOf(VisibilityMigration)})
object PlanCodec {
    private const val MAX_PLAN_BYTES = 256 * 1024
    fun encode(p: Plan): String {
        p.validateConfiguration()
        return JSONObject().apply {
        put("goal", p.goal); put("mode", p.mode.name)
        fun rule(key: String, r: Rule) { put(key, JSONObject().put("basis", r.basis.name).put("value", r.value)) }
        rule("energy", p.energy); rule("carbs", p.carbs); rule("protein", p.protein); rule("fat", p.fat)
        put("cycle", JSONArray(p.cycle)); put("start", p.start)
        put("taperGrams", p.taperGrams); put("taperDays", p.taperDays); put("minimumCarbs", p.minimumCarbs)
        put("intakeMode",p.intakeMode.name);put("fixedIntakeKcal",p.fixedIntakeKcal);put("energyAdjustment",p.energyAdjustment)
        put("useExpenditureBudget",p.useExpenditureBudget);put("deficitKcal",p.deficitKcal)
        put("ratio",JSONObject().put("carbs",p.ratio.carbs).put("protein",p.ratio.protein).put("fat",p.ratio.fat))
        p.advanced?.let { put("advanced", AdvancedPlanCodec.encode(it)) }
        }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_PLAN_BYTES) { "计划配置过大" } }
    }
    fun decode(s: String): Plan {
        require(s.toByteArray(Charsets.UTF_8).size <= MAX_PLAN_BYTES) { "计划配置过大" }
        return JSONObject(s).let { j ->
        require(j.getJSONArray("cycle").length()==7) { "碳循环必须包含 7 天配置" }
        fun rule(key: String): Rule = j.getJSONObject(key).let { Rule(Basis.valueOf(it.getString("basis")), it.getDouble("value")) }
        Plan(j.getString("goal"), PlanMode.valueOf(j.getString("mode")), rule("energy"), rule("carbs"), rule("protein"), rule("fat"),
            (0..6).map { j.getJSONArray("cycle").getDouble(it) }, j.getLong("start"), j.getDouble("taperGrams"), j.getInt("taperDays"), j.getDouble("minimumCarbs"),
            IntakeMode.valueOf(j.optString("intakeMode","FIXED")),j.optDouble("fixedIntakeKcal",2000.0),j.optDouble("energyAdjustment",0.0),
            j.optJSONObject("ratio")?.let {MacroRatio(it.getDouble("carbs"),it.getDouble("protein"),it.getDouble("fat"))} ?: MacroRatio(),j.optBoolean("useExpenditureBudget",false),j.optDouble("deficitKcal",500.0),
            advanced = if (j.has("advanced")) {
                require(j.get("advanced") is JSONObject) { "高级计划配置无效" }
                AdvancedPlanCodec.decode(j.getJSONObject("advanced"))
            } else null).also { it.validateConfiguration() }
        }
    }
}
data class Preferences(
    val plan: Plan = Plan(), val reminder: Boolean = false, val hour: Int = 8, val minute: Int = 0,
    val energyUnit: EnergyUnit = EnergyUnit.KCAL, val nextPlan: Plan? = null, val effectiveFrom: Long? = null,
    val profile:BodyProfile?=null,val privacy:Boolean=false, val visibility:BodyVisibility=BodyVisibility.all(!privacy),
    val weightInputUnit: WeightUnit = WeightUnit.KG, val weightFoodShortcut: Boolean = true,
    val hasReadIntro: Boolean = false, val skipMuscleInput: Boolean = false
) {
    fun planFor(day: Long): Plan = if(nextPlan != null && effectiveFrom != null && day >= effectiveFrom) nextPlan else plan
    val editablePlan get() = nextPlan ?: plan
}
object PreferencesCodec {
    fun encode(p: Preferences): String = JSONObject().apply {
        put("plan", JSONObject(PlanCodec.encode(p.plan)))
        put("reminder", p.reminder); put("hour", p.hour); put("minute", p.minute); put("energyUnit",p.energyUnit.name)
        p.nextPlan?.let { put("nextPlan",JSONObject(PlanCodec.encode(it))); put("effectiveFrom",p.effectiveFrom) }
        p.profile?.let {put("profile",ProfileCodec.encode(it))};put("privacy",p.privacy);put("visibility",VisibilityCodec.encode(p.visibility))
        put("weightInputUnit",p.weightInputUnit.name);put("weightFoodShortcut",p.weightFoodShortcut)
        put("hasReadIntro",p.hasReadIntro);put("skipMuscleInput",p.skipMuscleInput)
    }.toString()
    fun decode(text: String): Preferences = JSONObject(text).let { j ->
        val p = Preferences(PlanCodec.decode(j.getJSONObject("plan").toString()), j.getBoolean("reminder"),j.getInt("hour"),j.getInt("minute"),
            EnergyUnit.valueOf(j.optString("energyUnit","KCAL")),
            if(j.has("nextPlan")) PlanCodec.decode(j.getJSONObject("nextPlan").toString()) else null,
            if(j.has("nextPlan")) j.getLong("effectiveFrom") else null,
            j.optJSONObject("profile")?.let(ProfileCodec::decode),j.optBoolean("privacy",false),VisibilityCodec.decode(j.optJSONObject("visibility"),j.optBoolean("privacy",false)),
            WeightUnit.valueOf(j.optString("weightInputUnit","KG")),j.optBoolean("weightFoodShortcut",true),
            optionalBoolean(j,"hasReadIntro"),optionalBoolean(j,"skipMuscleInput"))
        require(p.hour in 0..23 && p.minute in 0..59) { "备份中的提醒时间无效" }
        p
    }
    private fun optionalBoolean(j:JSONObject,key:String):Boolean {
        if(!j.has(key)) return false
        require(j.get(key) is Boolean) { "设置 $key 的格式无效" }
        return j.getBoolean(key)
    }
}
class Settings internal constructor(private val store: DataStore<androidx.datastore.preferences.core.Preferences>) {
    constructor(context: Context):this(context.settingsStore)
    private val planKey = stringPreferencesKey("plan")
    private val enabledKey = booleanPreferencesKey("reminder")
    private val hourKey = intPreferencesKey("hour")
    private val minuteKey = intPreferencesKey("minute")
    private val energyKey = stringPreferencesKey("energy_unit")
    private val weightInputUnitKey = stringPreferencesKey("weight_input_unit")
    private val weightFoodShortcutKey = booleanPreferencesKey("weight_food_shortcut")
    private val hasReadIntroKey = booleanPreferencesKey("has_read_intro")
    private val skipMuscleInputKey = booleanPreferencesKey("skip_muscle_input")
    private val nextPlanKey = stringPreferencesKey("next_plan")
    private val effectiveKey = longPreferencesKey("plan_effective_day")
    private val profileKey=stringPreferencesKey("body_profile")
    private val privacyKey=booleanPreferencesKey("body_privacy")
    val flow = store.data.map { p -> Preferences(p[planKey]?.let(PlanCodec::decode) ?: Plan(), p[enabledKey] ?: false, p[hourKey] ?: 8, p[minuteKey] ?: 0,
        p[energyKey]?.let(EnergyUnit::valueOf) ?: EnergyUnit.KCAL, p[nextPlanKey]?.let(PlanCodec::decode), p[effectiveKey],
        p[profileKey]?.let {ProfileCodec.decode(JSONObject(it))},p[privacyKey] ?: false,VisibilityMigration.read(p),
        p[weightInputUnitKey]?.let(WeightUnit::valueOf) ?: WeightUnit.KG,p[weightFoodShortcutKey] ?: true,
        p[hasReadIntroKey] ?: false,p[skipMuscleInputKey] ?: false) }
    suspend fun savePlan(plan: Plan, day: Long = java.time.LocalDate.now().toEpochDay(),profile:BodyProfile?=null) {
        // Materialize the currently effective plan, then schedule the replacement for tomorrow.
        store.edit { p ->
            val previous=p[planKey]?.let(PlanCodec::decode) ?: Plan()
            val pending=p[nextPlanKey]?.let(PlanCodec::decode)
            val effective=p[effectiveKey]
            val current=if(pending!=null && effective!=null && day>=effective) pending else previous
            p[planKey]=PlanCodec.encode(current); p[nextPlanKey]=PlanCodec.encode(plan); p[effectiveKey]=day+1
            profile?.let {p[profileKey]=ProfileCodec.encode(it).toString()}
        }
    }
    suspend fun setEnergyUnit(unit: EnergyUnit) { store.edit { it[energyKey]=unit.name } }
    suspend fun setWeightInputUnit(unit: WeightUnit) { store.edit { it[weightInputUnitKey]=unit.name } }
    suspend fun setWeightFoodShortcut(enabled: Boolean) { store.edit { it[weightFoodShortcutKey]=enabled } }
    suspend fun markIntroRead() { store.edit { it[hasReadIntroKey]=true } }
    suspend fun setSkipMuscleInput(enabled:Boolean) { store.edit { it[skipMuscleInputKey]=enabled } }
    suspend fun setVisibility(field:BodyField,show:Boolean) {store.edit {it[booleanPreferencesKey(field.key)]=show}}
    suspend fun setAllVisible(show:Boolean) {store.edit {p->BodyField.entries.forEach {p[booleanPreferencesKey(it.key)]=show}}}
    // Compatibility for existing callers; the UI uses individual fields.
    suspend fun setPrivacy(hidden:Boolean) {setAllVisible(!hidden)}
    suspend fun saveProfile(profile:BodyProfile) {
        profile.validate(java.time.LocalDate.now().toEpochDay())
        store.edit {it[profileKey]=ProfileCodec.encode(profile).toString()}
    }
    suspend fun restore(p: Preferences) {
        // Update known keys only; do not clear the DataStore or erase unrelated future preferences.
        store.edit {
            it[planKey]=PlanCodec.encode(p.plan); it[enabledKey]=p.reminder; it[hourKey]=p.hour; it[minuteKey]=p.minute; it[energyKey]=p.energyUnit.name
            if(p.nextPlan!=null && p.effectiveFrom!=null) {it[nextPlanKey]=PlanCodec.encode(p.nextPlan);it[effectiveKey]=p.effectiveFrom}
            else {it.remove(nextPlanKey);it.remove(effectiveKey)}
            if(p.profile!=null) it[profileKey]=ProfileCodec.encode(p.profile).toString() else it.remove(profileKey)
            it[privacyKey]=p.privacy;BodyField.entries.forEach {field->it[booleanPreferencesKey(field.key)]=p.visibility.shows(field)}
            it[weightInputUnitKey]=p.weightInputUnit.name;it[weightFoodShortcutKey]=p.weightFoodShortcut
            // Reading is permanent on this installation, including after an older backup or
            // pre-upgrade pending-settings journal is restored. Never reset an acknowledged intro.
            it[hasReadIntroKey]=(it[hasReadIntroKey] ?: false) || p.hasReadIntro
            it[skipMuscleInputKey]=p.skipMuscleInput
        }
    }
    suspend fun reminder(enabled: Boolean, hour: Int, minute: Int) {
        require(hour in 0..23 && minute in 0..59) { "请输入有效提醒时间" }
        store.edit { it[enabledKey] = enabled; it[hourKey] = hour; it[minuteKey] = minute }
    }
}


