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
    val backupVersion: Int = 10, val databaseVersion: Int = Migrations.DATABASE_VERSION,
    val exportTime: String = Instant.now().toString(), val appVersion: String = BuildConfig.VERSION_NAME,
    val bodies: List<BodyRecord>, val targets: List<DailyTarget>, val foods: List<Food>, val intakes: List<Intake>, val preferences: Preferences, val exercises:List<Exercise> = emptyList(), val zones:List<FoodZone> = emptyList(),
    val dailyFoods:List<DailyFoodTemplate> = emptyList(), val applications:List<DailyFoodApplication> = emptyList(), val dayModes:List<DayMode> = emptyList()
) {
    fun validate() {
        require(backupVersion in 1..10 && databaseVersion in 1..Migrations.DATABASE_VERSION) { "不支持此备份版本，请使用兼容的新版本 App" }
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
    private fun n(j:JSONObject)=Nutrition(j.getDouble("kcal"),j.getDouble("carbs"),j.getDouble("protein"),j.getDouble("fat"))
    private fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)}
    fun encode(b:Backup):String {
        b.validate()
        val data=JSONObject().apply {
            put("bodies",JSONArray(b.bodies.map {b -> JSONObject().put("day",b.day).put("weight",b.weight).put("fatPercent",b.fatPercent).put("lean",b.lean).put("muscle",b.muscle ?: JSONObject.NULL).apply {b.energy?.let {put("energy",EnergySnapshotCodec.encode(it))}}}))
            put("targets",JSONArray(b.targets.map {JSONObject().put("day",it.day).put("nutrition",n(it.nutrition)).put("planSnapshot",it.planSnapshot)}))
            put("foods",JSONArray(b.foods.map {JSONObject().put("id",it.id).put("name",it.name).put("unit",it.unit).put("basisAmount",it.basisAmount).put("nutrition",n(it.nutrition)).put("step",it.step).put("maximum",it.maximum).put("allowed",it.allowed).put("energyInputUnit",it.energyInputUnit).put("isDeleted",it.isDeleted).put("zoneId",it.zoneId?:JSONObject.NULL).put("zonePortionSize",it.zonePortionSize).put("sortOrder",it.sortOrder).put("nutritionTags",it.nutritionTags).put("pricePerUnitYuan",it.pricePerUnitYuan?:JSONObject.NULL)}))
            put("zones",JSONArray(b.zones.map {JSONObject().put("id",it.id).put("name",it.name).put("dailyMaximum",it.dailyMaximum?:JSONObject.NULL).put("recommendationMaximum",it.recommendationMaximum?:JSONObject.NULL).put("sortOrder",it.sortOrder)}))
            put("dailyFoods",JSONArray(b.dailyFoods.map {JSONObject().put("id",it.id).put("foodId",it.foodId).put("unit",it.unit).put("mode",it.mode).put("quantity",it.quantity).put("enabled",it.enabled).put("startDay",it.startDay)}))
            put("applications",JSONArray(b.applications.map {JSONObject().put("day",it.day).put("templateId",it.templateId).put("intakeId",it.intakeId)}))
            put("dayModes",JSONArray(b.dayModes.map {JSONObject().put("day",it.day).put("isIndulgence",it.isIndulgence)}))
            put("intakes",JSONArray(b.intakes.map {JSONObject().put("id",it.id).put("day",it.day).put("foodId",it.foodId ?: JSONObject.NULL).put("name",it.name).put("unit",it.unit).put("quantity",it.quantity).put("nutrition",n(it.nutrition)).put("source",it.source).put("costYuan",it.costYuan?:JSONObject.NULL)}))
            put("exercises",JSONArray(b.exercises.map {JSONObject().put("id",it.id).put("day",it.day).put("type",it.type).put("minutes",it.minutes).put("met",it.met).put("weight",it.weight).put("kcal",it.kcal).put("included",it.included)}))
            put("preferences",JSONObject(PreferencesCodec.encode(b.preferences)))
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
        require(version in 1..10 && databaseVersion in 1..Migrations.DATABASE_VERSION) { "备份来自不支持的新版本，请先升级 App" }
        val payload=if(root.has("payload")) root.getString("payload") else {
            require(version==1) { "缺少备份内容" };root.getJSONObject("data").toString()
        }
        if(version>=2 || root.has("sha256")) require(hash(payload)==root.getString("sha256")) { "备份校验失败，文件可能不完整或被修改" }
        val data=JSONObject(payload)
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
                val zoneId=if(it.has("zoneId")) positiveId(it,"zoneId") else null
                val portionSize=if(it.has("zonePortionSize")) {
                    val value=it.get("zonePortionSize");require(value is Number && value.toDouble().isFinite()) {"食品分区换算无效"};value.toDouble()
                } else 1.0
                val tags=if(it.has("nutritionTags")) {
                    val value=it.get("nutritionTags");require(value is String) {"食品营养分区格式无效"};value
                } else ""
                Food(it.getLong("id"),it.getString("name"),it.getString("unit"),it.getDouble("basisAmount"),n(it.getJSONObject("nutrition")),it.getDouble("step"),it.getDouble("maximum"),it.getBoolean("allowed"),it.optString("energyInputUnit","KCAL"),deleted,zoneId,portionSize,if(it.has("sortOrder")) exactLong(it,"sortOrder") else 0,tags,if(it.has("pricePerUnitYuan")) optionalNumber(it,"pricePerUnitYuan") else null)
            },
            rows("intakes") {
                require(it.has("foodId")) { "摄入记录缺少食品引用字段" }
                val source=if(version>=9 || it.has("source")) {
                    require(it.opt("source") is String) { "摄入记录缺少有效来源" };it.getString("source")
                } else "FOOD"
                if(version>=10) require(it.has("costYuan")) {"摄入费用快照字段缺失"}
                Intake(it.getLong("id"),it.getLong("day"),if(it.isNull("foodId")) null else it.getLong("foodId"),it.getString("name"),it.getString("unit"),it.getDouble("quantity"),n(it.getJSONObject("nutrition")),source,if(it.has("costYuan")) optionalNumber(it,"costYuan") else null)
            },
            PreferencesCodec.decode(data.getJSONObject("preferences").toString()),
            if(version>=5 || data.has("exercises")) rows("exercises") {j->
                fun optional(key:String)=if(j.has(key) && !j.isNull(key)) j.getDouble(key) else null
                Exercise(j.getLong("id"),j.getLong("day"),j.getString("type"),optional("minutes"),optional("met"),optional("weight"),j.getDouble("kcal"),j.getBoolean("included"))
            } else emptyList(),
            if(version>=10 || data.has("zones")) rows("zones") {j ->
                require(j.has("dailyMaximum") && j.has("recommendationMaximum") && j.has("sortOrder")) {"食品分区上限或排序字段缺失"}
                val name=j.get("name");require(name is String) {"食品分区名称无效"}
                FoodZone(requireNotNull(positiveId(j,"id")) {"食品分区编号缺失"},name,optionalNumber(j,"dailyMaximum"),optionalNumber(j,"recommendationMaximum"),exactLong(j,"sortOrder"))
            } else emptyList(),
            if(version>=10 || data.has("dailyFoods")) rows("dailyFoods") {j ->
                val unit=j.get("unit");val mode=j.get("mode");val quantity=j.get("quantity")
                require(unit is String && mode is String && quantity is Number && quantity.toDouble().isFinite()) {"每日必吃食品格式无效"}
                DailyFoodTemplate(requireNotNull(positiveId(j,"id")),requireNotNull(positiveId(j,"foodId")),unit,mode,quantity.toDouble(),boolean(j,"enabled"),exactLong(j,"startDay"))
            } else emptyList(),
            if(version>=10 || data.has("applications")) rows("applications") {j ->
                DailyFoodApplication(exactLong(j,"day"),requireNotNull(positiveId(j,"templateId")),requireNotNull(positiveId(j,"intakeId")))
            } else emptyList(),
            if(version>=10 || data.has("dayModes")) rows("dayModes") {j ->DayMode(exactLong(j,"day"),boolean(j,"isIndulgence"))} else emptyList()).also {it.validate()}
    }
}





