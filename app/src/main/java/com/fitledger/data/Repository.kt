package com.fitledger.data

import androidx.room.withTransaction
import com.fitledger.domain.*
import java.time.LocalDate

data class ZoneFoodAssignment(val foodId:Long,val zonePortionSize:Double,val gramsPerPortion:Double?)

class Repository(private val db: LedgerDatabase) {
    val dao = db.dao()
    suspend fun saveZone(zone:FoodZone) = db.withTransaction {
        val normalized=zone.copy(name=zone.name.trim())
        normalized.validate()
        val existing=if(normalized.id!=0L) requireNotNull(dao.zone(normalized.id)) {"分区已删除，请重新添加分区"} else null
        val members=dao.allFoods().filter {it.zoneId==normalized.id && !it.isDeleted}
        require(members.all {FoodZoneQuotas.matchesUnit(it,normalized)}) {"分区只能加入按 ${FoodZoneQuotas.unitFor(normalized)} 记录的食品，请先移出其他单位食品"}
        dao.saveZone(normalized.copy(sortOrder=existing?.sortOrder?:nextTopLevelOrder()))
        // Only an explicit zone save confirms native portion limits; ordinary food edits cannot.
        members.filter {it.zonePortionSize!=1.0}.forEach {dao.saveFood(it.copy(zonePortionSize=1.0))}
    }
    suspend fun deleteZone(id:Long) = db.withTransaction {
        requireNotNull(dao.zone(id)) {"分区不存在"}
        val foods=dao.allFoods()
        val moved=foods.filter {it.zoneId==id && !it.isDeleted}.sortedWith(compareBy<Food> {it.sortOrder}.thenBy {it.name}.thenBy {it.id})
        val maxOther=(dao.allZones().filter {it.id!=id}.map {it.sortOrder}+foods.filter {it.zoneId==null && !it.isDeleted}.map {it.sortOrder}).maxOrNull()?:-1L
        require(maxOther<=Long.MAX_VALUE-moved.size) {"排序值已达上限，请调整一次顺序后重试"}
        // Include soft-deleted foods so complete backups never retain a dangling zone reference.
        dao.unassignZone(id)
        moved.forEachIndexed {index,food ->check(dao.setFoodSortOrder(food.id,maxOther+index+1L)==1)}
        check(dao.deleteZone(id)==1)
    }
    private suspend fun nextTopLevelOrder():Long {
        val maximum=(dao.allZones().map {it.sortOrder}+dao.allFoods().filter {it.zoneId==null && !it.isDeleted}.map {it.sortOrder}).maxOrNull()?:-1L
        require(maximum<Long.MAX_VALUE) {"排序值已达上限，请调整一次顺序后重试"}
        return maximum+1
    }
    private suspend fun nextZoneOrder(zoneId:Long):Long {
        val maximum=dao.allFoods().filter {it.zoneId==zoneId && !it.isDeleted}.maxOfOrNull {it.sortOrder}?:-1L
        require(maximum<Long.MAX_VALUE) {"排序值已达上限，请调整一次顺序后重试"}
        return maximum+1
    }
    suspend fun reorderLibrary(keys:List<String>)=db.withTransaction {
        val expected=dao.allZones().map {"zone:${it.id}"}.toSet()+dao.allFoods().filter {it.zoneId==null && !it.isDeleted}.map {"food:${it.id}"}
        require(keys.size==keys.distinct().size && keys.size==expected.size && keys.toSet()==expected) {"食品或分区已变化，请刷新后重新排序"}
        keys.forEachIndexed {index,key ->
            val id=key.substringAfter(':').toLong()
            val updated=if(key.startsWith("zone:")) dao.setZoneSortOrder(id,index.toLong()) else dao.setFoodSortOrder(id,index.toLong())
            check(updated==1)
        }
    }
    suspend fun reorderZone(zoneId:Long,foodIds:List<Long>)=db.withTransaction {
        requireNotNull(dao.zone(zoneId)) {"分区已删除，请刷新后重新排序"}
        val expected=dao.allFoods().filter {it.zoneId==zoneId && !it.isDeleted}.map {it.id}.toSet()
        require(foodIds.size==foodIds.distinct().size && foodIds.size==expected.size && foodIds.toSet()==expected) {"分区食品已变化，请刷新后重新排序"}
        foodIds.forEachIndexed {index,id ->check(dao.setFoodSortOrder(id,index.toLong())==1)}
    }
    /** Re-read every row in one transaction so picker state cannot overwrite newer food edits. */
    suspend fun assignFoodsToZone(zoneId:Long,assignments:List<ZoneFoodAssignment>)=db.withTransaction {
        val zone=requireNotNull(dao.zone(zoneId)) {"食品分区已删除，请刷新后重新添加"}
        zone.validate()
        require(assignments.map {it.foodId}.distinct().size==assignments.size) {"所选食品重复，请重新选择"}
        val foods=dao.allFoods()
        val byId=foods.associateBy {it.id}
        require(FoodZoneQuotas.zoneIssues(zone,foods).isEmpty()) {"本分区有旧单位或份数规则，请先核对分区上限并保存"}
        val updates=assignments.mapNotNull {assignment ->
            val food=requireNotNull(byId[assignment.foodId]) {"食品不存在，请刷新后重新选择"}
            require(!food.isDeleted) {"食品已删除，请刷新后重新选择"}
            require(FoodZoneQuotas.matchesUnit(food,zone)) {"「${zone.name}」只能加入按 ${FoodZoneQuotas.unitFor(zone)} 记录的食品"}
            if(food.zoneId==zoneId) return@mapNotNull null
            require(assignment.zonePortionSize==1.0) {"分区数量按食品的 g 或份直接累计，不使用换算"}
            food.copy(zoneId=zoneId,zonePortionSize=1.0).also {
                it.validate()
                require(FoodZoneQuotas.quotaSize(it,zone)!=null) {"食品单位与分区不一致"}
            }
        }
        val maximum=foods.filter {it.zoneId==zoneId && !it.isDeleted}.maxOfOrNull {it.sortOrder}?:-1L
        require(maximum<=Long.MAX_VALUE-updates.size) {"排序值已达上限，请调整一次顺序后重试"}
        updates.forEachIndexed {index,food ->dao.saveFood(food.copy(sortOrder=maximum+index+1L))}
    }
    /** Move selected members without changing any food/intake snapshots; all rows validate before writes. */
    suspend fun removeFoodsFromZone(zoneId:Long,foodIds:List<Long>)=db.withTransaction {
        requireNotNull(dao.zone(zoneId)) {"分区已删除，请刷新后重试"}
        require(foodIds.size==foodIds.distinct().size) {"所选食品重复，请重新选择"}
        val foods=dao.allFoods()
        val byId=foods.associateBy {it.id}
        val selected=foodIds.map {id->
            requireNotNull(byId[id]) {"食品不存在，请刷新后重试"}.also {
                require(!it.isDeleted && it.zoneId==zoneId) {"食品已删除或所属分区已变化，请刷新后重试"}
            }
        }
        val maximum=(dao.allZones().map {it.sortOrder}+foods.filter {it.zoneId==null && !it.isDeleted}.map {it.sortOrder}).maxOrNull()?:-1L
        require(maximum<=Long.MAX_VALUE-selected.size) {"排序值已达上限，请调整一次顺序后重试"}
        selected.forEachIndexed {index,food->dao.saveFood(food.copy(zoneId=null,sortOrder=maximum+index+1L))}
    }
    suspend fun importFoodCode(code:String,allowed:Boolean) {
        val imported=FoodShareCodec.decode(code).copy(allowed=allowed)
        // The codec never accepts sender IDs or deletion state; import always creates a new row.
        saveFood(imported)
    }
    suspend fun saveFood(food: Food) = db.withTransaction {
        food.validate()
        require(food.id >= 0 && !food.isDeleted) { "食品已删除，请重新添加食品" }
        food.zoneId?.let {requireNotNull(dao.zone(it)) {"食品分区已删除，请重新选择分区"}}
        val existing=if (food.id != 0L) {
            val existing = requireNotNull(dao.food(food.id)) { "食品不存在，请重新添加食品" }
            require(!existing.isDeleted) { "食品已删除，请重新添加食品" }
            existing
        } else null
        food.zoneId?.let {id->
            val zone=requireNotNull(dao.zone(id)) {"食品分区已删除，请重新选择分区"}
            require(FoodZoneQuotas.matchesUnit(food,zone)) {"「${zone.name}」只能加入按 ${FoodZoneQuotas.unitFor(zone)} 记录的食品"}
            require(zone.limitUnit!="PORTION" || food.zonePortionSize==1.0) {"请先到分区核对上限并保存，份数直接累计，不使用旧换算"}
            require(existing==null || existing.zoneId!=id || zone.limitUnit!="PORTION" ||
                (FoodZoneQuotas.matchesUnit(existing,zone) && existing.zonePortionSize==1.0)) {
                "旧分区单位或份数规则尚未确认，请先到分区调整单位，或移出食品并确认上限"
            }
        }
        val sortOrder=if(existing!=null && existing.zoneId==food.zoneId) existing.sortOrder
            else food.zoneId?.let {nextZoneOrder(it)}?:nextTopLevelOrder()
        dao.saveFood(food.copy(sortOrder=sortOrder))
    }
    suspend fun deleteFood(id: Long) = db.withTransaction {
        val food = requireNotNull(dao.food(id)) { "食品不存在" }
        require(!food.isDeleted) { "食品已经删除" }
        check(dao.markFoodDeleted(id) == 1)
    }
    suspend fun exercise(day:Long,type:Cardio?,minutes:Double?,manualKcal:Double?,included:Boolean,updateTarget:Boolean=false)=db.withTransaction {
        require(day==LocalDate.now().toEpochDay()) {"日期已变化，请重新记录"}
        val body=requireNotNull(dao.body(day)) {"请先完成今日身体数据"}
        requireNotNull(dao.target(day)) {"请先生成今日目标"}
        val record=if(type==null) Exercise(day=day,type="MANUAL",minutes=null,met=null,weight=null,kcal=requireNotNull(manualKcal),included=included)
            else Exercise(day=day,type=type.name,minutes=requireNotNull(minutes),met=type.met,weight=body.weight,kcal=ExerciseCalculator.extra(body.weight,minutes,type.met),included=included)
        record.validate();dao.insertExercise(record)
        if(updateTarget) refreshExerciseTarget(day)
    }
    suspend fun removeExercise(day:Long,id:Long,updateTarget:Boolean=false) = db.withTransaction {
        require(day==LocalDate.now().toEpochDay() && dao.body(day)!=null) {"仅能删除今日运动"}
        dao.deleteExercise(id,day)
        if(updateTarget) refreshExerciseTarget(day)
    }
    suspend fun setInitialPlan(day:Long,preferences:Preferences)=db.withTransaction {
        require(day==LocalDate.now().toEpochDay()) {"日期已变化，请重新确认"}
        require(dao.body(day)==null && dao.target(day)==null) {"今日快照已经生成，请使用立即生效"}
        preferences.planFor(day).validateConfiguration()
        requireNotNull(preferences.profile) {"请先补全身体资料"}.validate(day)
        dao.stageSettings(PendingSettings(payload=PreferencesCodec.encode(preferences)))
    }
    suspend fun applyToday(day: Long, preferences: Preferences) = db.withTransaction {
        require(day == LocalDate.now().toEpochDay()) { "日期已变化，请重新确认" }
        val saved = requireNotNull(dao.body(day)) { "请先完成今日身体数据，才能立即生效" }
        requireNotNull(dao.target(day)) { "今日目标不存在，不能覆盖" }
        val plan=preferences.planFor(day)
        val energy=EnergyExpenditureCalculator.snapshot(saved.weight,requireNotNull(preferences.profile) {"请先补全身体资料"},day)
        val target=TargetPlanning.create(plan,saved.copy(energy=energy),dao.allBodies(),dao.allTargets(),dao.allExercises())
        check(dao.updateBody(saved.copy(energy=energy)) == 1)
        check(dao.updateTarget(target) == 1)
        // Intake and history tables are deliberately not touched.
        dao.stageSettings(PendingSettings(payload=PreferencesCodec.encode(preferences)))
    }
    suspend fun applyTodayOnly(day:Long,plan:Plan,profile:BodyProfile)=db.withTransaction {
        require(day==LocalDate.now().toEpochDay()) { "日期已变化，请重新确认" }
        val saved=requireNotNull(dao.body(day)) { "请先完成今日身体记录" }
        requireNotNull(dao.target(day)) { "今日目标不存在" }
        val updated=saved.copy(energy=EnergyExpenditureCalculator.snapshot(saved.weight,profile,day))
        val target=TargetPlanning.create(plan,updated,dao.allBodies(),dao.allTargets(),dao.allExercises())
        check(dao.updateBody(updated)==1);check(dao.updateTarget(target)==1)
        // A one-day override never writes/stages preferences or changes scheduled future plans.
    }
    suspend fun recalculateExerciseTarget(day:Long)=db.withTransaction {
        require(day==LocalDate.now().toEpochDay()) { "日期已变化，请重新确认" }
        refreshExerciseTarget(day)
    }
    private suspend fun refreshExerciseTarget(day:Long) {
        val saved=requireNotNull(dao.body(day)) { "请先完成今日身体记录" }
        val original=requireNotNull(dao.target(day)) { "请先生成今日目标" }
        val plan=PlanCodec.decode(original.planSnapshot)
        requireNotNull(plan.advanced) { "请先在高级计划中配置运动补回规则" }
        val exercises=dao.allExercises()
        val frozen=requireNotNull(TargetPlanning.readContext(original)) { "今日目标缺少计算快照" }
            .copy(extraExerciseKcal=exercises.filter {it.day==day && it.included}.sumOf {it.kcal})
        val target=TargetPlanning.create(plan,saved,dao.allBodies(),dao.allTargets(),exercises,frozenContext=frozen)
        check(dao.updateTarget(target)==1)
    }
    suspend fun finishPendingSettings(settings: Settings, afterWrite: suspend (Preferences)->Unit) {
        val pending=dao.pendingSettings() ?: return
        val preferences=PreferencesCodec.decode(pending.payload)
        settings.restore(preferences)
        afterWrite(preferences)
        dao.completeSettings()
    }
    suspend fun exportBackup(preferences: Preferences): Backup = db.withTransaction {
        val daily=db.dailyEatingDao()
        Backup(bodies=dao.allBodies(), targets=dao.allTargets(), foods=dao.allFoods(), intakes=dao.allIntakes(), preferences=preferences,exercises=dao.allExercises(),zones=dao.allZones(),
            dailyFoods=daily.allTemplates(),applications=daily.allApplications(),dayModes=daily.allDayModes())
    }
    suspend fun restoreBackup(backup: Backup) {
        backup.validate()
        db.withTransaction {
            val daily=db.dailyEatingDao()
            daily.clearApplicationsForRestore();daily.clearTemplatesForRestore();daily.clearDayModesForRestore()
            dao.clearExercisesForRestore(); dao.clearIntakesForRestore(); dao.clearTargetsForRestore(); dao.clearBodiesForRestore(); dao.clearFoodsForRestore();dao.clearZonesForRestore()
            backup.bodies.forEach { dao.insertBody(it) }; backup.targets.forEach { dao.insertTarget(it) }
            backup.exercises.forEach {dao.insertExercise(it)};backup.zones.forEach {dao.saveZone(it)}; backup.foods.forEach { dao.saveFood(it) }; backup.intakes.forEach { dao.insertIntake(it) }
            backup.dailyFoods.forEach {daily.saveTemplate(it)};backup.applications.forEach {daily.insertApplication(it)};backup.dayModes.forEach {daily.saveDayMode(it)}
            // Deleted templates leave execution markers. Never reuse one of those identities after restore.
            val templateFloor=(backup.dailyFoods.map {it.id}+backup.applications.map {it.templateId}).maxOrNull()?:0L
            val sqlite=db.openHelper.writableDatabase
            sqlite.execSQL("UPDATE sqlite_sequence SET seq=MAX(seq,?) WHERE name='daily_food_templates'",arrayOf(templateFloor))
            sqlite.execSQL("INSERT INTO sqlite_sequence (name,seq) SELECT 'daily_food_templates',? WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name='daily_food_templates')",arrayOf(templateFloor))
            val intakeFloor=(backup.intakes.map {it.id}+backup.applications.map {it.intakeId}).maxOrNull()?:0L
            sqlite.execSQL("UPDATE sqlite_sequence SET seq=MAX(seq,?) WHERE name='intakes'",arrayOf(intakeFloor))
            sqlite.execSQL("INSERT INTO sqlite_sequence (name,seq) SELECT 'intakes',? WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name='intakes')",arrayOf(intakeFloor))
            dao.stageSettings(PendingSettings(payload=PreferencesCodec.encode(backup.preferences)))
        }
    }
    suspend fun weigh(day: Long, body: Body, plan: Plan, profile:BodyProfile, omitMuscle:Boolean=false) = db.withTransaction {
        require(day == LocalDate.now().toEpochDay()) { "日期已变化，请重新录入" }
        require(omitMuscle || body.muscle!=null) { "请填写肌肉量，或在设置中开启省略肌肉量输入" }
        val measured=if(omitMuscle) body.copy(muscle=null) else body
        measured.validate()
        require(dao.body(day) == null) { "今日身体数据已经保存" }
        val energy=EnergyExpenditureCalculator.snapshot(measured.weight,profile,day)
        val record=BodyRecord(day, measured.weight, measured.fatPercent, measured.lean, measured.muscle,energy)
        val target=TargetPlanning.create(plan,record,dao.allBodies(),dao.allTargets(),dao.allExercises())
        dao.insertBody(record)
        dao.insertTarget(target)
        DailyEatingStore(db).apply(day)
        Unit
    }
    suspend fun record(day: Long, quantities: Map<Long, Double>, recommendation: Boolean = false,policy:RecommendationPolicy=RecommendationPolicy()) = db.withTransaction {
        require(day == LocalDate.now().toEpochDay()) { "日期已变化，请先记录今日身体数据" }
        require(dao.body(day) != null && dao.target(day) != null) { "请先完成今日身体数据" }
        if(recommendation) require(db.dailyEatingDao().dayMode(day)?.isIndulgence!=true) {"放纵日已暂停智能推荐，可如实手动记录食品"}
        if(recommendation) {
            val foods=dao.allFoods();val zones=dao.allZones();val intakes=dao.allIntakes()
            FoodZoneQuotas.validateRecommendation(quantities,foods,zones,intakes,day)
            val today=intakes.filter {it.day==day}
            val consumed=today.fold(Nutrition()) {sum,intake->sum+intake.nutrition}
            Recommender.validateProposal(FoodZoneQuotas.candidates(foods,zones),requireNotNull(dao.target(day)).nutrition-consumed,quantities,
                FoodZoneQuotas.limits(zones,foods,intakes,day),policy,spentYuan(today))
        }
        quantities.forEach { (id, quantity) ->
            require(quantity.isFinite() && quantity > 0) { "摄入量须大于 0" }
            val food = requireNotNull(dao.food(id)) { "食品不存在" }
            require(!food.isDeleted) { "食品已删除，请重新选择食品" }
            if(recommendation) {
                require(food.allowed && quantity <= food.maximum + 1e-6) { "食品推荐设置已变化，请重新推荐" }
                require(kotlin.math.abs(quantity/food.step-kotlin.math.round(quantity/food.step)) < 1e-5) { "推荐调整单位已变化" }
            }
            val cost=FoodPricing.cost(food.pricePerUnitYuan,quantity)
            val snapshot=food.nutrition*(quantity/food.basisAmount)
            require(snapshot.values().all {it.isFinite() && it>=0} && snapshot.kcal>0) {"摄入营养超出计算范围，请核对数量和食品标注基准"}
            dao.insertIntake(Intake(day=day, foodId=id, name=food.name, unit=food.unit, quantity=quantity, nutrition=snapshot,costYuan=cost))
        }
    }
    suspend fun recommend(day:Long,locks:Map<Long,Double>,policy:RecommendationPolicy=RecommendationPolicy(),preferValue:Boolean=false):Suggestion = db.withTransaction {
        require(day==LocalDate.now().toEpochDay()) {"日期已变化，请先记录今日身体数据"}
        requireNotNull(dao.body(day)) {"请先完成今日身体数据"}
        val target=requireNotNull(dao.target(day)) {"请先生成今日目标"}
        require(db.dailyEatingDao().dayMode(day)?.isIndulgence!=true) {"放纵日已暂停智能推荐，可如实手动记录食品"}
        val foods=dao.allFoods()
        val intakes=dao.allIntakes()
        val consumed=intakes.filter {it.day==day}.fold(Nutrition()) {sum,intake ->sum+intake.nutrition}
        val remaining=target.nutrition-consumed
        val zones=dao.allZones()
        Recommender.recommend(FoodZoneQuotas.candidates(foods,zones),remaining,locks,FoodZoneQuotas.limits(zones,foods,intakes,day),policy,spentYuan(intakes.filter {it.day==day}),preferValue)
    }
    private fun spentYuan(intakes:List<Intake>):Double? {
        if(intakes.any {it.costYuan==null}) return null
        return intakes.sumOf {requireNotNull(it.costYuan)}.also {require(it.isFinite() && it>=0) {"今日已记录费用超出计算范围"}}
    }
    suspend fun recordNutrition(day:Long,nutrition:Nutrition)=db.withTransaction {
        require(day==LocalDate.now().toEpochDay()) { "日期已变化，请重新填写今日营养" }
        require(dao.body(day)!=null && dao.target(day)!=null) { "请先完成今日身体数据" }
        val snapshot=DirectNutrition.calculate(nutrition.carbs,nutrition.protein,nutrition.fat)
        dao.insertIntake(Intake(day=day,foodId=null,name="直接添加营养",unit="次",quantity=1.0,nutrition=snapshot,source="DIRECT"))
        Unit
    }
    suspend fun remove(day: Long, id: Long) {
        require(day == LocalDate.now().toEpochDay() && dao.body(day) != null) { "仅能删除今日摄入" }
        dao.deleteIntake(id, day)
    }
}


