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
    val backupVersion: Int = 9, val databaseVersion: Int = Migrations.DATABASE_VERSION,
    val exportTime: String = Instant.now().toString(), val appVersion: String = BuildConfig.VERSION_NAME,
    val bodies: List<BodyRecord>, val targets: List<DailyTarget>, val foods: List<Food>, val intakes: List<Intake>, val preferences: Preferences, val exercises:List<Exercise> = emptyList()
) {
    fun validate() {
        require(backupVersion in 1..9 && databaseVersion in 1..Migrations.DATABASE_VERSION) { "不支持此备份版本，请使用兼容的新版本 App" }
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
        foods.forEach {require(it.id>0);it.validate()}
        val ids=foods.map {it.id}.toSet()
        intakes.forEach {
            require(it.id>0 && it.day in days && it.name.isNotBlank() && it.quantity.isFinite() && it.quantity>0) { "饮食记录日期或数量无效" }
            nutrition(it.nutrition)
            when(it.source) {
                "FOOD" -> require(it.foodId in ids && it.unit in listOf("g","份")) { "饮食记录食品引用无效" }
                "DIRECT" -> {
                    require(it.foodId==null && it.unit=="次" && it.quantity==1.0) { "直接营养记录格式无效" }
                    val expected=DirectNutrition.calculate(it.nutrition.carbs,it.nutrition.protein,it.nutrition.fat)
                    require(abs(expected.kcal-it.nutrition.kcal)<0.000001) { "直接营养记录的热量与宏量不一致" }
                }
                else -> error("饮食记录来源无效")
            }
        }
        validatePlan(preferences.plan); preferences.nextPlan?.let(::validatePlan)
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
            put("foods",JSONArray(b.foods.map {JSONObject().put("id",it.id).put("name",it.name).put("unit",it.unit).put("basisAmount",it.basisAmount).put("nutrition",n(it.nutrition)).put("step",it.step).put("maximum",it.maximum).put("allowed",it.allowed).put("energyInputUnit",it.energyInputUnit).put("isDeleted",it.isDeleted)}))
            put("intakes",JSONArray(b.intakes.map {JSONObject().put("id",it.id).put("day",it.day).put("foodId",it.foodId ?: JSONObject.NULL).put("name",it.name).put("unit",it.unit).put("quantity",it.quantity).put("nutrition",n(it.nutrition)).put("source",it.source)}))
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
        require(version in 1..9 && databaseVersion in 1..Migrations.DATABASE_VERSION) { "备份来自不支持的新版本，请先升级 App" }
        val payload=if(root.has("payload")) root.getString("payload") else {
            require(version==1) { "缺少备份内容" };root.getJSONObject("data").toString()
        }
        if(version>=2 || root.has("sha256")) require(hash(payload)==root.getString("sha256")) { "备份校验失败，文件可能不完整或被修改" }
        val data=JSONObject(payload)
        fun <T> rows(key:String,read:(JSONObject)->T):List<T> {
            val array=data.getJSONArray(key); require(array.length()<=100000) { "备份记录过多" }
            return (0 until array.length()).map {read(array.getJSONObject(it))}
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
                Food(it.getLong("id"),it.getString("name"),it.getString("unit"),it.getDouble("basisAmount"),n(it.getJSONObject("nutrition")),it.getDouble("step"),it.getDouble("maximum"),it.getBoolean("allowed"),it.optString("energyInputUnit","KCAL"),deleted)
            },
            rows("intakes") {
                require(it.has("foodId")) { "摄入记录缺少食品引用字段" }
                val source=if(version>=9 || it.has("source")) {
                    require(it.opt("source") is String) { "摄入记录缺少有效来源" };it.getString("source")
                } else "FOOD"
                Intake(it.getLong("id"),it.getLong("day"),if(it.isNull("foodId")) null else it.getLong("foodId"),it.getString("name"),it.getString("unit"),it.getDouble("quantity"),n(it.getJSONObject("nutrition")),source)
            },
            PreferencesCodec.decode(data.getJSONObject("preferences").toString()),
            if(version>=5 || data.has("exercises")) rows("exercises") {j->
                fun optional(key:String)=if(j.has(key) && !j.isNull(key)) j.getDouble(key) else null
                Exercise(j.getLong("id"),j.getLong("day"),j.getString("type"),optional("minutes"),optional("met"),optional("weight"),j.getDouble("kcal"),j.getBoolean("included"))
            } else emptyList()).also {it.validate()}
    }
}





