package com.fitledger.data

import androidx.room.withTransaction
import com.fitledger.domain.*
import java.time.LocalDate

class Repository(private val db: LedgerDatabase) {
    val dao = db.dao()
    suspend fun importFoodCode(code:String,allowed:Boolean) {
        val imported=FoodShareCodec.decode(code).copy(allowed=allowed)
        // The codec never accepts sender IDs or deletion state; import always creates a new row.
        saveFood(imported)
    }
    suspend fun saveFood(food: Food) = db.withTransaction {
        food.validate()
        require(food.id >= 0 && !food.isDeleted) { "食品已删除，请重新添加食品" }
        if (food.id != 0L) {
            val existing = requireNotNull(dao.food(food.id)) { "食品不存在，请重新添加食品" }
            require(!existing.isDeleted) { "食品已删除，请重新添加食品" }
        }
        dao.saveFood(food)
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
        Backup(bodies=dao.allBodies(), targets=dao.allTargets(), foods=dao.allFoods(), intakes=dao.allIntakes(), preferences=preferences,exercises=dao.allExercises())
    }
    suspend fun restoreBackup(backup: Backup) {
        backup.validate()
        db.withTransaction {
            dao.clearExercisesForRestore(); dao.clearIntakesForRestore(); dao.clearTargetsForRestore(); dao.clearBodiesForRestore(); dao.clearFoodsForRestore()
            backup.bodies.forEach { dao.insertBody(it) }; backup.targets.forEach { dao.insertTarget(it) }
            backup.exercises.forEach {dao.insertExercise(it)}; backup.foods.forEach { dao.saveFood(it) }; backup.intakes.forEach { dao.insertIntake(it) }
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
    }
    suspend fun record(day: Long, quantities: Map<Long, Double>, recommendation: Boolean = false) = db.withTransaction {
        require(day == LocalDate.now().toEpochDay()) { "日期已变化，请先记录今日身体数据" }
        require(dao.body(day) != null && dao.target(day) != null) { "请先完成今日身体数据" }
        quantities.forEach { (id, quantity) ->
            require(quantity.isFinite() && quantity > 0) { "摄入量须大于 0" }
            val food = requireNotNull(dao.food(id)) { "食品不存在" }
            require(!food.isDeleted) { "食品已删除，请重新选择食品" }
            if(recommendation) {
                require(food.allowed && quantity <= food.maximum + 1e-6) { "食品推荐设置已变化，请重新推荐" }
                require(kotlin.math.abs(quantity/food.step-kotlin.math.round(quantity/food.step)) < 1e-5) { "推荐调整单位已变化" }
            }
            dao.insertIntake(Intake(day=day, foodId=id, name=food.name, unit=food.unit, quantity=quantity, nutrition=food.nutrition*(quantity/food.basisAmount)))
        }
    }
    suspend fun recordNutrition(day:Long,nutrition:Nutrition)=db.withTransaction {
        require(day==LocalDate.now().toEpochDay()) { "日期已变化，请重新填写今日营养" }
        require(dao.body(day)!=null && dao.target(day)!=null) { "请先完成今日身体数据" }
        val snapshot=DirectNutrition.calculate(nutrition.carbs,nutrition.protein,nutrition.fat)
        dao.insertIntake(Intake(day=day,foodId=null,name="直接添加营养",unit="次",quantity=1.0,nutrition=snapshot,source="DIRECT"))
    }
    suspend fun remove(day: Long, id: Long) {
        require(day == LocalDate.now().toEpochDay() && dao.body(day) != null) { "仅能删除今日摄入" }
        dao.deleteIntake(id, day)
    }
}


