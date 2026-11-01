package com.fitledger.data

import com.fitledger.domain.*
import com.fitledger.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

data class Backup(
    val backupVersion: Int = 17, val databaseVersion: Int = Migrations.DATABASE_VERSION,
    val exportTime: String = Instant.now().toString(), val appVersion: String = BuildConfig.VERSION_NAME,
    val bodies: List<BodyRecord>, val targets: List<DailyTarget>, val foods: List<Food>, val intakes: List<Intake>, val preferences: Preferences, val exercises:List<Exercise> = emptyList(), val zones:List<FoodZone> = emptyList(),
    val dailyFoods:List<DailyFoodTemplate> = emptyList(), val applications:List<DailyFoodApplication> = emptyList(), val dayModes:List<DayMode> = emptyList(),
    val skips:List<DailyFoodSkip> = emptyList(),val combos:List<FoodCombo> = emptyList(),
    val presets:List<FoodPresetPlan> = emptyList(),val presetItems:List<FoodPresetItem> = emptyList(),
    val recipes:List<FoodRecipe> = emptyList(),
    val pendingFoods:List<PendingFood> = emptyList(),val recipeBatches:List<RecipeBatch> = emptyList()
) {
    fun validate() {
        require(backupVersion in 1..17 && databaseVersion in 1..Migrations.DATABASE_VERSION) { "不支持此备份版本，请使用兼容的新版本 App" }
        RecommendationProfilesCodec.encode(preferences.recommendationProfiles)
        require((pendingFoods.isEmpty() && recipeBatches.isEmpty() && preferences.recommendationProfiles.isEmpty() &&
            !preferences.recommendationExtras.rules.enabled && foods.all {it.minimum==0.0 && it.preparationTags.isEmpty()}) ||
            (backupVersion>=17 && databaseVersion>=13)) {"待吃、批次、推荐档案和新规则需要第17版备份与第13版数据库，不能降级保存"}
        preferences.recommendationExtras.validate()
        require((recipes.isEmpty() && combos.all {it.ratioRules.isEmpty()} && presets.all {it.ratioRules.isEmpty() && it.day-it.referenceDay==1L} &&
            preferences.recommendationExtras==RecommendationExtras()) || (backupVersion>=16 && databaseVersion>=12)) {"配方、多日预设和推荐扩展需要第16版备份与第12版数据库，不能降级保存"}
        require((foods.all {it.aliases.isEmpty()} && intakes.none {it.source=="ENERGY"}) || (backupVersion>=15 && databaseVersion>=11)) {"食品别名和仅热量记录需要第15版备份与第11版数据库，不能降级保存"}
        LibraryGroupCollapse.validate(preferences.collapsedLibraryGroups)
        require(preferences.collapsedLibraryGroups.isEmpty() || backupVersion>=14) {"食品分区折叠设置需要第14版备份，不能降级保存"}
        require((presets.isEmpty() && presetItems.isEmpty()) || (backupVersion>=13 && databaseVersion>=10)) {"明日预设需要第13版备份与第10版数据库，不能降级保存"}
        require(presets.size<=100000 && presetItems.size<=100000 && presets.map {it.day}.distinct().size==presets.size && presetItems.map {it.id}.distinct().size==presetItems.size) {"预设数量或编号无效"}
        val presetIntakeIds=presetItems.mapNotNull {it.appliedIntakeId}
        require(presetIntakeIds.distinct().size==presetIntakeIds.size) {"预设包含重复的添加标记"}
        require((zones.all {it.limitUnit=="PORTION"} && foods.none {it.gramsPerPortion!=null}) || (backupVersion>=11 && databaseVersion>=8)) {"克数分区和每份克数需要第 11 版备份与第 8 版数据库，不能降级保存"}
        require((skips.isEmpty() && combos.isEmpty() && foods.none {it.favorite || it.quickAmounts.isNotEmpty()} && dailyFoods.none {it.schedule.isNotEmpty()} && zones.all {it.allowMultipleFoods}) || (backupVersion>=12 && databaseVersion>=9)) {"收藏、常用数量、组合、每日规则和分区混选设置需要第 12 版备份与第 9 版数据库，不能降级保存"}
        require(skips.size<=100000 && skips.map {it.day to it.templateId}.distinct().size==skips.size) {"每日暂停记录数量或编号无效"}
        require(combos.size<=100000 && combos.map {it.id}.distinct().size==combos.size) {"食品组合数量或编号无效"}
        require((zones.isEmpty() && foods.none {it.zoneId!=null || it.zonePortionSize!=1.0 || it.sortOrder!=0L}) || (backupVersion>=10 && databaseVersion>=7)) { "食品分区和排序需要第 10 版备份与第 7 版数据库，不能降级保存" }
        require((foods.none {it.nutritionTags.isNotEmpty() || it.pricePerUnitYuan!=null} && intakes.none {it.costYuan!=null}) || (backupVersion>=10 && databaseVersion>=7)) {"食品营养分区和价格需要第 10 版备份与第 7 版数据库，不能降级保存"}
        require((dailyFoods.isEmpty() && applications.isEmpty() && dayModes.isEmpty() && intakes.none {it.source=="ROUTINE"}) || (backupVersion>=10 && databaseVersion>=7)) {"每日必吃和放纵日数据需要第 10 版备份与第 7 版数据库，不能降级保存"}
        require(listOf(dailyFoods.size,applications.size,dayModes.size).all {it<=100000}) {"每日饮食设置记录过多"}
        require(dailyFoods.map {it.id}.distinct().size==dailyFoods.size &&
            applications.map {it.day to it.templateId}.distinct().size==applications.size &&
            applications.map {it.intakeId}.distinct().size==applications.size &&
            dayModes.map {it.day}.distinct().size==dayModes.size) {"每日饮食设置包含重复编号"}
        require(zones.size<=100000 && zones.map {it.id}.distinct().size==zones.size) {"食品分区数量或编号无效"}
        zones.forEach {require(it.id>0) {"食品分区编号无效"};it.validate()}
        val zoneIds=zones.map {it.id}.toSet()
        require(foods.none {it.isDeleted} || (backupVersion >= 7 && databaseVersion >= 5)) { "已删除食品需要新版备份格式，不能降级保存" }
        require(bodies.none {it.muscle==null} || (backupVersion>=9 && databaseVersion>=6)) { "省略肌肉量的记录需要第 9 版备份与第 6 版数据库，不能降级保存" }
        require(intakes.none {it.source=="DIRECT"} || (backupVersion>=9 && databaseVersion>=6)) { "直接营养记录需要第 9 版备份与第 6 版数据库，不能降级保存" }
        Instant.parse(exportTime); require(appVersion.isNotBlank())
        require(listOf(bodies.size,targets.size,foods.size,intakes.size).all {it<=100000}) { "备份记录过多" }
        require(bodies.map {it.day}.distinct().size==bodies.size && targets.map {it.day}.distinct().size==targets.size && foods.map {it.id}.distinct().size==foods.size && intakes.map {it.id}.distinct().size==intakes.size) { "备份包含重复记录编号" }
        require(exercises.size<=100000 && exercises.map {it.id}.distinct().size==exercises.size) {"运动记录数量或编号无效"}

        val bodyByDay=bodies.associateBy {it.day}
        val days=bodyByDay.keys
        exercises.forEach {require(it.id>0 && it.day in days) {"运动日期缺少身体记录"};it.validate()}
        require(days==targets.map {it.day}.toSet()) { "身体记录和目标快照不完整" }
        bodies.forEach {
            LocalDate.ofEpochDay(it.day)
            val b=Body(it.weight,it.fatPercent,it.muscle);b.validate()
            require(it.lean.isFinite() && it.lean>0 && abs(it.lean-b.lean)<=0.05) { "瘦体重快照无效" }
            it.energy?.validate(it.weight,it.day)
        }
        fun nutrition(n:Nutrition) {require(n.values().all {it.isFinite() && it>=0}) { "营养数据无效" }}
        targets.forEach {
            nutrition(it.nutrition); require(it.nutrition.kcal > 0)
            validatePlan(PlanCodec.decode(it.planSnapshot))
            TargetPlanning.validateSnapshot(it,bodyByDay[it.day])
        }
        foods.forEach {require(it.id>0);it.validate();require(it.zoneId==null || it.zoneId in zoneIds) {"食品分区引用无效"}}
        val ids=foods.map {it.id}.toSet()
        val actualById=intakes.associateBy {it.id}
        require(pendingFoods.size<=100000 && pendingFoods.map {it.id}.distinct().size==pendingFoods.size) {"待吃条目数量或编号无效"}
        val pendingMarkers=pendingFoods.mapNotNull {it.appliedIntakeId}
        require(pendingMarkers.distinct().size==pendingMarkers.size && pendingMarkers.none {it in presetIntakeIds || applications.any {a->a.intakeId==it}}) {"待吃确认标记重复"}
        pendingFoods.forEach {item->
            item.validate();require(item.id>0 && item.day in days && item.foodId in ids) {"待吃引用或日期无效"}
            item.appliedIntakeId?.let {id->actualById[id]?.let {actual->
                require(actual.day==item.day && actual.foodId==item.foodId && actual.unit==item.unit) {"待吃确认标记与实际摄入不一致"}
            }}
        }
        require(recipeBatches.size<=100000 && recipeBatches.map {it.id}.distinct().size==recipeBatches.size) {"配方批次数量或编号无效"}
        val batchFoodIds=recipeBatches.mapNotNull {it.foodId}
        require(batchFoodIds.distinct().size==batchFoodIds.size && batchFoodIds.none {id->recipes.any {it.foodId==id}}) {"批次食品关联重复"}
        recipeBatches.forEach {batch->
            batch.validate();require(batch.id>0 && (batch.foodId==null || batch.foodId in ids)) {"批次编号或食品引用无效"}
            require(RecipeIngredients.decode(batch.ingredients).all {it.foodId in ids}) {"批次食材引用无效"}
        }
        fun validateExtrasRefs(extras:RecommendationExtras) {
            extras.validate()
            require(extras.comboIds.all {id->combos.any {it.id==id}} && extras.ratios.all {it.foodId in ids && it.referenceFoodId in ids}) {"推荐配置食品或组合引用无效"}
            require(extras.rules.cycleRules.all {it.foodId in ids} && extras.rules.conditions.all {
                it.zoneId in zoneIds && (if(it.triggerKind=="FOOD") it.triggerId in ids else it.triggerId in zoneIds)
            }) {"周期或条件搭配引用无效"}
            extras.rules.cycleRules.forEach {r->
                if(foods.first {it.id==r.foodId}.unit=="份") r.maximumQuantity?.takeIf {it>0}?.let {PortionQuantities.requireWhole("份",it)}
            }
        }
        validateExtrasRefs(preferences.recommendationExtras)
        preferences.recommendationProfiles.forEach {validateExtrasRefs(it.extras)}
        require(recipes.size<=100000 && recipes.map {it.id}.distinct().size==recipes.size) {"配方数量或编号无效"}
        require(recipes.mapNotNull {it.foodId}.distinct().size==recipes.count {it.foodId!=null}) {"配方关联食品不能重复"}
        recipes.forEach {recipe->
            require(recipe.id>0 && (recipe.foodId==null || recipe.foodId in ids)) {"配方编号或关联食品无效"}
            recipe.validate()
            require(RecipeIngredients.decode(recipe.ingredients).all {it.foodId in ids}) {"配方食材引用无效"}
        }
        require(preferences.recommendationExtras.comboIds.all {id->combos.any {it.id==id}}) {"推荐组合引用无效"}
        require(preferences.recommendationExtras.ratios.all {it.foodId in ids && it.referenceFoodId in ids}) {"推荐比例食品引用无效"}
        val presetByDay=presets.associateBy {it.day}
        presets.forEach {it.validate();require(it.referenceDay in days) {"预设缺少参考日期的身体与目标记录"}}
        presets.forEach {plan->
            val plannedIds=presetItems.filter {it.day==plan.day}.map {it.foodId}.toSet()
            require(WeightRatios.decode(plan.ratioRules).all {it.foodId in plannedIds && it.referenceFoodId in plannedIds}) {"预设比例食品引用无效"}
        }
        val actualIntakes=intakes.associateBy {it.id}
        presetItems.forEach {item ->
            item.validate()
            require(item.id>0 && item.day in presetByDay && item.foodId in ids) {"预设食品或日期引用无效"}
            item.appliedIntakeId?.let {id ->
                require(item.day in days) {"已添加预设缺少当天身体与目标记录"}
                actualIntakes[id]?.let {actual ->require(actual.day==item.day && actual.foodId==item.foodId && actual.unit==item.unit) {"预设添加标记与摄入记录不一致"}}
            }
        }
        combos.forEach {combo ->
            require(combo.id>0) {"食品组合编号无效"};combo.validate()
            require(ComboCodec.decode(combo.items).all {it.foodId in ids}) {"食品组合引用无效"}
        }
        skips.forEach {skip ->require(skip.day>=0 && skip.templateId>0) {"每日暂停记录无效"};LocalDate.ofEpochDay(skip.day)}
        dailyFoods.forEach {template ->
            require(template.id>0 && template.foodId in ids) {"每日必吃食品引用无效"}
            template.validate()
        }
        val intakeById=intakes.associateBy {it.id}
        val applicationByIntake=applications.associateBy {it.intakeId}
        applications.forEach {application ->
            LocalDate.ofEpochDay(application.day)
            require(application.templateId>0 && application.intakeId>0 && application.day in days) {"每日必吃执行记录无效"}
            intakeById[application.intakeId]?.let {
                require(it.source=="ROUTINE" && it.day==application.day) {"每日必吃执行记录与摄入不一致"}
            }
        }
        dayModes.forEach {LocalDate.ofEpochDay(it.day)}
        intakes.forEach {
            require(it.id>0 && it.day in days && it.name.isNotBlank() && it.quantity.isFinite() && it.quantity>0) { "饮食记录日期或数量无效" }
            require(it.costYuan==null || (it.costYuan.isFinite() && it.costYuan>=0)) {"摄入费用快照无效"}
            nutrition(it.nutrition)
            when(it.source) {
                "FOOD" -> require(it.foodId in ids && it.unit in listOf("g","份")) { "饮食记录食品引用无效" }
                "ROUTINE" -> {
                    require(it.foodId in ids && it.unit in listOf("g","份")) {"每日必吃摄入的食品引用无效"}
                    require(applicationByIntake[it.id]?.day==it.day) {"每日必吃摄入缺少执行记录"}
                }
                "DIRECT" -> {
                    require(it.foodId==null && it.unit=="次" && it.quantity==1.0) { "直接营养记录格式无效" }
                    val expected=DirectNutrition.calculate(it.nutrition.carbs,it.nutrition.protein,it.nutrition.fat)
                    require(abs(expected.kcal-it.nutrition.kcal)<0.000001) { "直接营养记录的热量与宏量不一致" }
                }
                "ENERGY" -> IntakeNutritionTotals.validateEnergy(it)
                else -> error("饮食记录来源无效")
            }
        }
        validatePlan(preferences.plan); preferences.nextPlan?.let(::validatePlan)
        preferences.recommendationPolicy.validate()
        require(preferences.recommendationPolicy==RecommendationPolicy() || backupVersion>=10) {"推荐预算和营养范围需要第 10 版备份格式，不能降级保存"}
        require((preferences.nextPlan==null)==(preferences.effectiveFrom==null)) { "计划生效日期缺失" }
        preferences.effectiveFrom?.let {LocalDate.ofEpochDay(it)}
        require(preferences.hour in 0..23 && preferences.minute in 0..59)
        preferences.profile?.validate(LocalDate.now().toEpochDay())
    }
    private fun validatePlan(p:Plan) {
        p.validateConfiguration()
        require(p.advanced == null || backupVersion >= 8) { "高级计划需要第 8 版备份格式，不能降级保存" }
    }
}

object BackupCodec {
    const val MAX_BYTES=20*1024*1024
    private fun n(n:Nutrition)=JSONObject().put("kcal",n.kcal).put("carbs",n.carbs).put("protein",n.protein).put("fat",n.fat)
    private fun n(i:Intake)=if(i.macrosKnown) n(i.nutrition) else JSONObject().put("kcal",i.nutrition.kcal)
        .put("carbs",JSONObject.NULL).put("protein",JSONObject.NULL).put("fat",JSONObject.NULL)
    private fun n(j:JSONObject)=Nutrition(j.getDouble("kcal"),j.getDouble("carbs"),j.getDouble("protein"),j.getDouble("fat"))
    private fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)}
    fun encode(b:Backup):String {
        b.validate()
        val preferences=JSONObject(PreferencesCodec.encode(b.preferences)).apply {
            if(b.backupVersion<17) {remove("recommendationProfiles");getJSONObject("recommendationExtras").remove("rules")}
        }
        val data=JSONObject().apply {
            put("bodies",JSONArray(b.bodies.map {b -> JSONObject().put("day",b.day).put("weight",b.weight).put("fatPercent",b.fatPercent).put("lean",b.lean).put("muscle",b.muscle ?: JSONObject.NULL).apply {b.energy?.let {put("energy",EnergySnapshotCodec.encode(it))}}}))
            put("targets",JSONArray(b.targets.map {JSONObject().put("day",it.day).put("nutrition",n(it.nutrition)).put("planSnapshot",it.planSnapshot)}))
            put("foods",JSONArray(b.foods.map {JSONObject().put("id",it.id).put("name",it.name).put("unit",it.unit).put("basisAmount",it.basisAmount).put("nutrition",n(it.nutrition)).put("step",it.step).put("maximum",it.maximum).put("allowed",it.allowed).put("energyInputUnit",it.energyInputUnit).put("isDeleted",it.isDeleted).put("zoneId",it.zoneId?:JSONObject.NULL).put("zonePortionSize",it.zonePortionSize).put("sortOrder",it.sortOrder).put("nutritionTags",it.nutritionTags).put("pricePerUnitYuan",it.pricePerUnitYuan?:JSONObject.NULL).put("favorite",it.favorite).put("quickAmounts",it.quickAmounts).put("gramsPerPortion",it.gramsPerPortion?:JSONObject.NULL).put("aliases",it.aliases)}))
            put("zones",JSONArray(b.zones.map {JSONObject().put("id",it.id).put("name",it.name).put("dailyMaximum",it.dailyMaximum?:JSONObject.NULL).put("recommendationMaximum",it.recommendationMaximum?:JSONObject.NULL).put("sortOrder",it.sortOrder).put("allowMultipleFoods",it.allowMultipleFoods).put("limitUnit",it.limitUnit)}))
            put("dailyFoods",JSONArray(b.dailyFoods.map {JSONObject().put("id",it.id).put("foodId",it.foodId).put("unit",it.unit).put("mode",it.mode).put("quantity",it.quantity).put("enabled",it.enabled).put("startDay",it.startDay).put("schedule",it.schedule)}))
            put("skips",JSONArray(b.skips.map {JSONObject().put("day",it.day).put("templateId",it.templateId)}))
            put("combos",JSONArray(b.combos.map {JSONObject().put("id",it.id).put("name",it.name).put("items",it.items).put("ratioRules",it.ratioRules)}))
            put("presets",JSONArray(b.presets.map {JSONObject().put("day",it.day).put("referenceDay",it.referenceDay).put("referenceExpenditureKcal",it.referenceExpenditureKcal?:JSONObject.NULL).put("referenceWeightKg",it.referenceWeightKg).put("ratioRules",it.ratioRules)}))
            put("recipes",JSONArray(b.recipes.map {JSONObject().put("id",it.id).put("name",it.name).put("ingredients",it.ingredients).put("finishedWeightG",it.finishedWeightG).put("foodId",it.foodId?:JSONObject.NULL)}))
            if(b.backupVersion>=17) {
                put("pendingFoods",JSONArray(b.pendingFoods.map {JSONObject().put("id",it.id).put("day",it.day).put("groupId",it.groupId).put("foodId",it.foodId).put("name",it.name).put("unit",it.unit).put("quantity",it.quantity).put("nutrition",n(it.nutrition)).put("costYuan",it.costYuan?:JSONObject.NULL).put("appliedIntakeId",it.appliedIntakeId?:JSONObject.NULL).put("createdAt",it.createdAt)}))
                put("recipeBatches",JSONArray(b.recipeBatches.map {JSONObject().put("id",it.id).put("recipeId",it.recipeId?:JSONObject.NULL).put("name",it.name).put("createdDay",it.createdDay).put("ingredients",it.ingredients).put("finishedWeightG",it.finishedWeightG).put("boxWeights",it.boxWeights).put("nutrition",n(it.nutrition)).put("costYuan",it.costYuan?:JSONObject.NULL).put("foodId",it.foodId?:JSONObject.NULL)}))
                val foodRows=getJSONArray("foods")
                b.foods.forEachIndexed {index,food->foodRows.getJSONObject(index).put("minimum",food.minimum).put("preparationTags",food.preparationTags)}
            }
            put("presetItems",JSONArray(b.presetItems.map {JSONObject().put("id",it.id).put("day",it.day).put("foodId",it.foodId).put("name",it.name).put("unit",it.unit).put("quantity",it.quantity).put("nutrition",n(it.nutrition)).put("costYuan",it.costYuan?:JSONObject.NULL).put("appliedIntakeId",it.appliedIntakeId?:JSONObject.NULL)}))
            put("applications",JSONArray(b.applications.map {JSONObject().put("day",it.day).put("templateId",it.templateId).put("intakeId",it.intakeId)}))
            put("dayModes",JSONArray(b.dayModes.map {JSONObject().put("day",it.day).put("isIndulgence",it.isIndulgence)}))
            put("intakes",JSONArray(b.intakes.map {JSONObject().put("id",it.id).put("day",it.day).put("foodId",it.foodId ?: JSONObject.NULL).put("name",it.name).put("unit",it.unit).put("quantity",it.quantity).put("nutrition",n(it)).put("source",it.source).put("costYuan",it.costYuan?:JSONObject.NULL)}))
            put("exercises",JSONArray(b.exercises.map {JSONObject().put("id",it.id).put("day",it.day).put("type",it.type).put("minutes",it.minutes).put("met",it.met).put("weight",it.weight).put("kcal",it.kcal).put("included",it.included)}))
            put("preferences",preferences)
        }.toString()
        // Hash the exact payload string: independent of JSON object key ordering between runtimes.
        val text=JSONObject().put("backupVersion",b.backupVersion).put("databaseVersion",b.databaseVersion).put("exportTime",b.exportTime)
            .put("appVersion",b.appVersion).put("payload",data).put("sha256",hash(data)).toString(2)
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "备份超过 20 MB，未写入文件" }
        return text
    }
    fun decode(text:String):Backup {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "备份超过 20 MB" }
        val root=JSONObject(text)
        fun version(key:String):Int {
            val value=root.get(key)
            require(value is Number && value.toDouble().isFinite() && value.toDouble()==value.toInt().toDouble()) { "备份版本格式无效" }
            return value.toInt()
        }
        val version=version("backupVersion"); val databaseVersion=version("databaseVersion")
        require(version in 1..17 && databaseVersion in 1..Migrations.DATABASE_VERSION) { "备份来自不支持的新版本，请先升级 App" }
        val payload=if(root.has("payload")) root.getString("payload") else {
            require(version==1) { "缺少备份内容" };root.getJSONObject("data").toString()
        }
        if(version>=2 || root.has("sha256")) require(hash(payload)==root.getString("sha256")) { "备份校验失败，文件可能不完整或被修改" }
        val data=JSONObject(payload)
        if(version>=14) require(data.getJSONObject("preferences").has("collapsedLibraryGroups")) {"备份缺少食品分区折叠设置"}
        if(version>=16) require(data.getJSONObject("preferences").has("recommendationExtras")) {"备份缺少推荐扩展设置"}
        if(version>=17) require(data.getJSONObject("preferences").has("recommendationProfiles") && data.getJSONObject("preferences").getJSONObject("recommendationExtras").has("rules")) {"备份缺少推荐档案或规则"}
        fun <T> rows(key:String,read:(JSONObject)->T):List<T> {
            val array=data.getJSONArray(key); require(array.length()<=100000) { "备份记录过多" }
            return (0 until array.length()).map {read(array.getJSONObject(it))}
        }
        fun optionalNumber(j:JSONObject,key:String):Double? {
            if(j.isNull(key)) return null
            val value=j.get(key)
            require(value is Number && value.toDouble().isFinite()) {"食品分区上限或价格格式无效"}
            return value.toDouble()
        }
        fun positiveId(j:JSONObject,key:String):Long? {
            if(j.isNull(key)) return null
            val value=j.get(key)
            require(value is Number && value.toDouble().isFinite()) {"食品分区编号无效"}
            val number=runCatching {value.toString().toBigDecimal().longValueExact()}.getOrNull()
            require(number!=null && number>0) {"食品分区编号无效"}
            return number
        }
        fun exactLong(j:JSONObject,key:String):Long {
            val value=j.get(key)
            require(value is Number && value.toDouble().isFinite()) {"排序、每日饮食记录编号或日期格式无效"}
            return requireNotNull(runCatching {value.toString().toBigDecimal().longValueExact()}.getOrNull()) {"排序、每日饮食记录编号或日期格式无效"}
        }
        fun boolean(j:JSONObject,key:String):Boolean {
            val value=j.get(key);require(value is Boolean) {"每日饮食开关格式无效"};return value
        }
        fun presetNumber(j:JSONObject,key:String):Double {
            val value=j.get(key)
            require(value is Number && value.toDouble().isFinite()) {"预设数值格式无效"}
            return value.toDouble()
        }
        fun newText(j:JSONObject,key:String):String {
            if(!j.has(key)) {require(version<16) {"新版备份字段缺失：$key"};return ""}
            val value=j.get(key);require(value is String) {"备份字段格式无效：$key"};return value
        }
        return Backup(version,databaseVersion,root.getString("exportTime"),root.getString("appVersion"),
            rows("bodies") {
                require(it.has("muscle")) { "身体记录缺少肌肉量字段" }
                val muscle=if(it.isNull("muscle")) {
                    require(version>=9 && databaseVersion>=6) { "旧版备份的肌肉量不能为空" }; null
                } else it.getDouble("muscle")
                BodyRecord(it.getLong("day"),it.getDouble("weight"),it.getDouble("fatPercent"),it.getDouble("lean"),muscle,it.optJSONObject("energy")?.let(EnergySnapshotCodec::decode))
            },
            rows("targets") {DailyTarget(it.getLong("day"),n(it.getJSONObject("nutrition")),it.getString("planSnapshot"))},
            rows("foods") {
                val deleted = if (version >= 7 || it.has("isDeleted")) {
                    require(it.opt("isDeleted") is Boolean) { "食品删除状态缺失或无效" }
                    it.getBoolean("isDeleted")
                } else false
                if(version>=10) require(it.has("zoneId") && it.has("zonePortionSize") && it.has("sortOrder") && it.has("nutritionTags") && it.has("pricePerUnitYuan")) {"食品分区、排序或价格字段缺失"}
                if(version>=11) require(it.has("gramsPerPortion")) {"食品每份克数字段缺失"}
                if(version>=12) require(it.has("favorite") && it.has("quickAmounts")) {"食品收藏或常用数量字段缺失"}
                if(version>=15) require(it.has("aliases")) {"食品别名字段缺失"}
                if(version>=17) require(it.has("minimum") && it.has("preparationTags")) {"食品最小量或准备条件字段缺失"}
                val zoneId=if(it.has("zoneId")) positiveId(it,"zoneId") else null
                val portionSize=if(it.has("zonePortionSize")) {
                    val value=it.get("zonePortionSize");require(value is Number && value.toDouble().isFinite()) {"食品分区换算无效"};value.toDouble()
                } else 1.0
                val tags=if(it.has("nutritionTags")) {
                    val value=it.get("nutritionTags");require(value is String) {"食品营养分区格式无效"};value
                } else ""
                val amounts=if(it.has("quickAmounts")) {val value=it.get("quickAmounts");require(value is String) {"常用数量格式无效"};value} else ""
                Food(it.getLong("id"),it.getString("name"),it.getString("unit"),it.getDouble("basisAmount"),n(it.getJSONObject("nutrition")),it.getDouble("step"),it.getDouble("maximum"),it.getBoolean("allowed"),it.optString("energyInputUnit","KCAL"),deleted,zoneId,portionSize,if(it.has("sortOrder")) exactLong(it,"sortOrder") else 0,tags,if(it.has("pricePerUnitYuan")) optionalNumber(it,"pricePerUnitYuan") else null,if(it.has("favorite")) boolean(it,"favorite") else false,amounts,if(it.has("gramsPerPortion")) optionalNumber(it,"gramsPerPortion") else null,if(it.has("aliases")) {val value=it.get("aliases");require(value is String) {"食品别名格式无效"};value} else "").copy(
                    minimum=if(it.has("minimum")) presetNumber(it,"minimum") else 0.0,
                    preparationTags=if(it.has("preparationTags")) {val value=it.get("preparationTags");require(value is String) {"准备条件格式无效"};value} else "")
            },
            rows("intakes") {
                require(it.has("foodId")) { "摄入记录缺少食品引用字段" }
                val source=if(version>=9 || it.has("source")) {
                    require(it.opt("source") is String) { "摄入记录缺少有效来源" };it.getString("source")
                } else "FOOD"
                if(version>=10) require(it.has("costYuan")) {"摄入费用快照字段缺失"}
                val nutrients=it.getJSONObject("nutrition")
                val snapshot=if(source=="ENERGY") {
                    require(version>=15 && databaseVersion>=11) {"仅热量记录需要新版备份格式"}
                    require(listOf("carbs","protein","fat").all {key->nutrients.has(key) && nutrients.isNull(key)}) {"仅热量记录的三大营养素必须保持未知"}
                    val kcal=nutrients.get("kcal");require(kcal is Number) {"热量格式无效"}
                    IntakeNutritionTotals.energy(kcal.toDouble())
                } else n(nutrients)
                Intake(it.getLong("id"),it.getLong("day"),if(it.isNull("foodId")) null else it.getLong("foodId"),it.getString("name"),it.getString("unit"),it.getDouble("quantity"),snapshot,source,if(it.has("costYuan")) optionalNumber(it,"costYuan") else null)
            },
            PreferencesCodec.decode(data.getJSONObject("preferences").toString()),
            if(version>=5 || data.has("exercises")) rows("exercises") {j->
                fun optional(key:String)=if(j.has(key) && !j.isNull(key)) j.getDouble(key) else null
                Exercise(j.getLong("id"),j.getLong("day"),j.getString("type"),optional("minutes"),optional("met"),optional("weight"),j.getDouble("kcal"),j.getBoolean("included"))
            } else emptyList(),
            if(version>=10 || data.has("zones")) rows("zones") {j ->
                require(j.has("dailyMaximum") && j.has("recommendationMaximum") && j.has("sortOrder")) {"食品分区上限或排序字段缺失"}
                if(version>=11) require(j.has("limitUnit")) {"食品分区计量单位字段缺失"}
                if(version>=12) require(j.has("allowMultipleFoods")) {"食品分区混选设置字段缺失"}
                val name=j.get("name");require(name is String) {"食品分区名称无效"}
                val limitUnit=if(j.has("limitUnit")) {val value=j.get("limitUnit");require(value is String) {"食品分区计量单位无效"};value} else "PORTION"
                FoodZone(requireNotNull(positiveId(j,"id")) {"食品分区编号缺失"},name,optionalNumber(j,"dailyMaximum"),optionalNumber(j,"recommendationMaximum"),exactLong(j,"sortOrder"),if(j.has("allowMultipleFoods")) boolean(j,"allowMultipleFoods") else true,limitUnit)
            } else emptyList(),
            if(version>=10 || data.has("dailyFoods")) rows("dailyFoods") {j ->
                val unit=j.get("unit");val mode=j.get("mode");val quantity=j.get("quantity")
                require(unit is String && mode is String && quantity is Number && quantity.toDouble().isFinite()) {"每日必吃食品格式无效"}
                if(version>=12) require(j.has("schedule")) {"每日规则字段缺失"}
                val schedule=if(j.has("schedule")) {val value=j.get("schedule");require(value is String) {"每日规则格式无效"};value} else ""
                DailyFoodTemplate(requireNotNull(positiveId(j,"id")),requireNotNull(positiveId(j,"foodId")),unit,mode,quantity.toDouble(),boolean(j,"enabled"),exactLong(j,"startDay"),schedule)
            } else emptyList(),
            if(version>=10 || data.has("applications")) rows("applications") {j ->
                DailyFoodApplication(exactLong(j,"day"),requireNotNull(positiveId(j,"templateId")),requireNotNull(positiveId(j,"intakeId")))
            } else emptyList(),
            if(version>=10 || data.has("dayModes")) rows("dayModes") {j ->DayMode(exactLong(j,"day"),boolean(j,"isIndulgence"))} else emptyList(),
            if(version>=12 || data.has("skips")) rows("skips") {j ->DailyFoodSkip(exactLong(j,"day"),requireNotNull(positiveId(j,"templateId")))} else emptyList(),
            if(version>=12 || data.has("combos")) rows("combos") {j ->
                val name=j.get("name");val items=j.get("items");require(name is String && items is String) {"食品组合格式无效"}
                FoodCombo(requireNotNull(positiveId(j,"id")),name,items,newText(j,"ratioRules"))
            } else emptyList(),
            if(version>=13 || data.has("presets")) rows("presets") {j ->
                require(j.has("referenceExpenditureKcal")) {"预设缺少参考消耗字段"}
                FoodPresetPlan(exactLong(j,"day"),exactLong(j,"referenceDay"),optionalNumber(j,"referenceExpenditureKcal"),presetNumber(j,"referenceWeightKg"),newText(j,"ratioRules"))
            } else emptyList(),
            if(version>=13 || data.has("presetItems")) rows("presetItems") {j ->
                require(j.has("costYuan") && j.has("appliedIntakeId")) {"预设食品缺少费用或添加标记字段"}
                val name=j.get("name");val unit=j.get("unit")
                require(name is String && unit is String) {"预设食品名称或单位格式无效"}
                val nutrients=j.getJSONObject("nutrition")
                val nutrition=Nutrition(presetNumber(nutrients,"kcal"),presetNumber(nutrients,"carbs"),presetNumber(nutrients,"protein"),presetNumber(nutrients,"fat"))
                FoodPresetItem(requireNotNull(positiveId(j,"id")),exactLong(j,"day"),requireNotNull(positiveId(j,"foodId")),name,unit,presetNumber(j,"quantity"),nutrition,optionalNumber(j,"costYuan"),positiveId(j,"appliedIntakeId"))
            } else emptyList(),
            if(version>=16 || data.has("recipes")) rows("recipes") {j->
                require(j.has("foodId")) {"配方缺少关联食品字段"}
                val name=j.get("name");val ingredients=j.get("ingredients")
                require(name is String && ingredients is String) {"配方字段无效"}
                FoodRecipe(requireNotNull(positiveId(j,"id")),name,ingredients,presetNumber(j,"finishedWeightG"),positiveId(j,"foodId"))
            } else emptyList(),
            if(version>=17 || data.has("pendingFoods")) rows("pendingFoods") {j->
                require(j.has("costYuan") && j.has("appliedIntakeId")) {"待吃缺少费用或确认标记"}
                val nutrients=j.getJSONObject("nutrition")
                PendingFood(requireNotNull(positiveId(j,"id")),exactLong(j,"day"),newText(j,"groupId"),requireNotNull(positiveId(j,"foodId")),newText(j,"name"),newText(j,"unit"),presetNumber(j,"quantity"),
                    Nutrition(presetNumber(nutrients,"kcal"),presetNumber(nutrients,"carbs"),presetNumber(nutrients,"protein"),presetNumber(nutrients,"fat")),
                    optionalNumber(j,"costYuan"),positiveId(j,"appliedIntakeId"),exactLong(j,"createdAt"))
            } else emptyList(),
            if(version>=17 || data.has("recipeBatches")) rows("recipeBatches") {j->
                require(j.has("recipeId") && j.has("foodId") && j.has("costYuan")) {"批次缺少来源或费用字段"}
                val nutrients=j.getJSONObject("nutrition")
                RecipeBatch(requireNotNull(positiveId(j,"id")),positiveId(j,"recipeId"),newText(j,"name"),exactLong(j,"createdDay"),newText(j,"ingredients"),presetNumber(j,"finishedWeightG"),newText(j,"boxWeights"),
                    Nutrition(presetNumber(nutrients,"kcal"),presetNumber(nutrients,"carbs"),presetNumber(nutrients,"protein"),presetNumber(nutrients,"fat")),optionalNumber(j,"costYuan"),positiveId(j,"foodId"))
            } else emptyList()).also {it.validate()}
    }
}





