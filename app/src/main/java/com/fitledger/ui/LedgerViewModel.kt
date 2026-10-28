package com.fitledger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitledger.FitApp
import com.fitledger.data.*
import com.fitledger.domain.*
import com.fitledger.work.Reminders
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.sync.withLock

data class LedgerState(val bodies: List<BodyRecord> = emptyList(), val targets: List<DailyTarget> = emptyList(),
    val foods: List<Food> = emptyList(), val intakes: List<Intake> = emptyList(), val preferences: Preferences = Preferences(), val loaded: Boolean = false, val exercises:List<Exercise> = emptyList(),
    val zones:List<FoodZone> = emptyList(),val allFoods:List<Food> = emptyList(),
    val dailyFoods:List<DailyFoodTemplate> = emptyList(),val dailyApplications:List<DailyFoodApplication> = emptyList(),val dayModes:List<DayMode> = emptyList())
class LedgerViewModel(application: Application): AndroidViewModel(application) {
    private val app = application as FitApp
    private val repo = app.repository
    private val daily=DailyEatingStore(app.database)
    val today = MutableStateFlow(LocalDate.now().toEpochDay())
    val message = MutableStateFlow<String?>(null)
    val muscleOmittedNotice=MutableStateFlow<Long?>(null)
    val busy = MutableStateFlow(false)
    val suggestion = MutableStateFlow<Suggestion?>(null)
    private var suggestionDay:Long?=null
    val dataReady=MutableStateFlow(false)
    val restorePreview=MutableStateFlow<Backup?>(null)
    private var restoreData:Backup?=null
    val state = flow {
        app.prepareData()
        app.dataLock.withLock {daily.apply(today.value)}
        dataReady.value=true
        val core=combine(repo.dao.bodies(), repo.dao.targets(), repo.dao.foods(), repo.dao.intakes(), app.settings.flow) { b,t,f,i,p -> LedgerState(b,t,f,i,p,true) }
        val library=combine(core,repo.dao.exercises(),repo.dao.zones(),repo.dao.allFoodsFlow()) {s,e,z,f->s.copy(exercises=e,zones=z,allFoods=f)}
        emitAll(combine(library,daily.dao.templates(),daily.dao.applications(),daily.dao.dayModes()) {s,f,a,m->s.copy(dailyFoods=f,dailyApplications=a,dayModes=m)})
    }.retryWhen { error, _ -> message.value="读取失败：${error.message}。未清空任何数据。"; delay(5000);true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LedgerState())
    init { viewModelScope.launch { while(isActive) { refreshDate(); delay(1000) } } }
    fun refreshDate() { val day = LocalDate.now().toEpochDay(); if(today.value != day) { today.value = day; suggestion.value = null } }
    private fun action(block: suspend () -> Unit) {
        if(busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { app.dataLock.withLock { app.completePendingSettings(); block() } } catch(e: CancellationException) { throw e } catch(e: Exception) {
                message.value = e.message ?: "操作失败"
                // Failed validation/rolled-back transactions must not strand the UI behind the sync gate.
                if(!dataReady.value && runCatching {repo.dao.pendingSettings()==null}.getOrDefault(false)) dataReady.value=true
            }
            finally { busy.value = false }
        }
    }
    fun weigh(body: Body,trainingDay:Boolean?=null) = action {
        refreshDate();val p=app.settings.flow.first()
        val configured=p.planFor(today.value)
        val plan=if(trainingDay!=null && configured.advanced?.schedule?.mode==ScheduleMode.TRAINING_REST)
            configured.copy(advanced=configured.advanced.copy(schedule=configured.advanced.schedule.copy(trainingDay=trainingDay))) else configured
        repo.weigh(today.value,body,plan,requireNotNull(p.profile) {"请先补全并保存身体资料"},omitMuscle=p.skipMuscleInput)
    }
    fun saveFood(food: Food, onSaved: () -> Unit = {}) = action { repo.saveFood(food); suggestion.value = null; onSaved(); message.value = "食品已保存" }
    fun saveZone(zone:FoodZone,onSaved:()->Unit={})=action {repo.saveZone(zone);suggestion.value=null;onSaved();message.value="分区已保存"}
    fun assignFoodsToZone(zoneId:Long,assignments:List<ZoneFoodAssignment>,onSaved:()->Unit)=action {
        repo.assignFoodsToZone(zoneId,assignments);suggestion.value=null
        state.first {s->s.loaded && assignments.all {a->s.foods.any {it.id==a.foodId && it.zoneId==zoneId}}}
        onSaved();message.value="食品已加入分区，营养、价格和历史摄入保持不变"
    }
    fun removeFoodsFromZone(zoneId:Long,foodIds:List<Long>,onSaved:()->Unit)=action {
        repo.removeFoodsFromZone(zoneId,foodIds);suggestion.value=null
        state.first {s->s.loaded && foodIds.all {id->s.foods.any {it.id==id && it.zoneId==null}}}
        onSaved();message.value="食品已移至未分区，资料和历史摄入保留；请重新确认分区上限"
    }
    fun deleteZone(id:Long)=action {repo.deleteZone(id);suggestion.value=null;message.value="分区已删除，食品保留并移至未分区"}
    fun reorderLibrary(keys:List<String>)=action {repo.reorderLibrary(keys)}
    fun reorderZone(id:Long,foodIds:List<Long>)=action {repo.reorderZone(id,foodIds)}
    fun saveDailyFood(template:DailyFoodTemplate,onSaved:()->Unit={})=action {
        val warnings=daily.saveTemplate(template,today.value);suggestion.value=null;onSaved()
        message.value=warnings.takeIf {it.isNotEmpty()}?.joinToString("；") ?: "每日吃的已保存；已生成的摄入不变，未添加的今日项目会自动补记"
    }
    fun deleteDailyFood(id:Long)=action {daily.deleteTemplate(id);suggestion.value=null;message.value="每日设置已删除，今天和历史摄入保留"}
    fun applyDailyFoods()=action {
        val warnings=daily.apply(today.value);suggestion.value=null
        message.value=warnings.takeIf {it.isNotEmpty()}?.joinToString("；") ?: "已检查今日固定食品；已经添加或手动删除的项目不会重复添加"
    }
    fun indulgence(day:Long,enabled:Boolean)=action {daily.setIndulgence(day,enabled);suggestion.value=null}
    fun editQuantity(id:Long,quantity:Double)=action {daily.editIntakeQuantity(today.value,id,quantity);suggestion.value=null}
    fun recommendationPolicy(policy:RecommendationPolicy)=action {app.settings.setRecommendationPolicy(policy);suggestion.value=null;message.value="推荐约束已保存，手动记录不受限制"}
    fun importFoodCode(code:String,allowed:Boolean,onSaved:()->Unit)=action {
        repo.importFoodCode(code,allowed);suggestion.value=null;onSaved();message.value="食品已添加，原有食品和饮食记录保持不变"
    }
    fun deleteFood(id:Long,onDeleted:()->Unit={}) = action {
        repo.deleteFood(id);suggestion.value=null
        // Wait for the active-food list before accepting another record/recommendation action.
        state.first {it.loaded && it.foods.none {food->food.id==id}}
        onDeleted();message.value="食品已删除，已记录的摄入和历史数据保留"
    }
    fun record(id: Long, quantity: Double) = action { repo.record(today.value, mapOf(id to quantity)); suggestion.value = null }
    fun recordNutrition(day:Long,carbs:String,protein:String,fat:String)=action {
        repo.recordNutrition(day,DirectNutrition.parse(carbs,protein,fat));suggestion.value=null
        message.value="营养已添加到今日摄入；输入内容保留，可修改后再次添加或点击清空"
    }
    fun exercise(day:Long,type:Cardio?,minutes:Double?,kcal:Double?,included:Boolean,updateTarget:Boolean=false)=action {
        repo.exercise(day,type,minutes,kcal,included,updateTarget);suggestion.value=null
        if(updateTarget) message.value="运动已记录，按今日计划更新目标；饮食记录和历史不变"
    }
    fun removeExercise(day:Long,id:Long,updateTarget:Boolean=false)=action {repo.removeExercise(day,id,updateTarget);suggestion.value=null}
    fun recalculateExerciseTarget(day:Long)=action {repo.recalculateExerciseTarget(day);suggestion.value=null;message.value="已按今日运动重算目标，已有饮食及历史保持不变"}
    fun remove(id: Long) = action { repo.remove(today.value, id); suggestion.value = null }
    fun savePlan(plan: Plan,profile:BodyProfile) = action {
        refreshDate();plan.validateConfiguration();profile.validate(today.value)
        repo.dao.body(today.value)?.let {b ->
            // Tomorrow's schedule can select a different template. Preflight it using the last
            // measurement as a reference only; no future body/target record is created here.
            val calculationDay=if(plan.advanced!=null) today.value+1 else today.value
            val expenditure=EnergyExpenditureCalculator.snapshot(b.weight,profile,calculationDay).expenditureKcal
            TargetPlanning.preview(plan,b.copy(day=calculationDay),repo.dao.allBodies(),repo.dao.allTargets(),repo.dao.allExercises(),expenditure)
        }
        app.settings.savePlan(plan,profile=profile); message.value = "资料已保存用于后续身体记录，计划明天生效；今日及历史快照不变"
    }
    fun setInitialPlan(plan:Plan,confirmedDay:Long,profile:BodyProfile)=action {
        val old=app.settings.flow.first()
        val future=old.effectiveFrom?.let {it>confirmedDay}==true
        val p=old.copy(plan=plan,profile=profile,nextPlan=if(future) old.nextPlan else null,effectiveFrom=if(future) old.effectiveFrom else null)
        dataReady.value=false
        repo.setInitialPlan(confirmedDay,p);completeSettingsAndAwait();dataReady.value=true
        message.value="今日待生成计划已更新，请完成称重；历史快照不变"
    }
    fun applyToday(plan:Plan,confirmedDay:Long,profile:BodyProfile) = action {
        val p=app.settings.flow.first().copy(plan=plan,nextPlan=null,effectiveFrom=null,profile=profile)
        dataReady.value=false
        repo.applyToday(confirmedDay,p)
        suggestion.value=null
        completeSettingsAndAwait();dataReady.value=true
        message.value="新计划已立即生效，今日摄入与历史记录保持不变"
    }
    fun applyTodayOnly(plan:Plan,confirmedDay:Long,profile:BodyProfile)=action {
        repo.applyTodayOnly(confirmedDay,plan,profile);suggestion.value=null
        message.value="仅今日目标与能量快照已更新，明日计划、资料设置及历史记录不变"
    }
    fun saveProfile(profile:BodyProfile)=action {app.settings.saveProfile(profile);message.value="身体资料已保存；后续身体记录使用新资料，今日和历史快照未更改"}
    fun completeFirstSetup(profile:BodyProfile)=action {
        require(app.settings.flow.first().profile==null) {"基础资料已经保存，请在设置中修改"}
        app.settings.saveProfile(profile)
        state.first {it.loaded && it.preferences.profile==profile}
    }
    fun visibility(field:BodyField,show:Boolean)=action {app.settings.setVisibility(field,show)}
    fun allVisible(show:Boolean)=action {app.settings.setAllVisible(show)}
    fun retryRecovery() = action { app.completePendingSettings();val p=app.settings.flow.first();state.first {it.preferences==p};dataReady.value=true }
    fun energyUnit(unit:EnergyUnit) = action {app.settings.setEnergyUnit(unit)}
    fun weightInputUnit(unit:WeightUnit) = action {app.settings.setWeightInputUnit(unit)}
    fun weightFoodShortcut(enabled:Boolean) = action {app.settings.setWeightFoodShortcut(enabled)}
    fun acknowledgeIntro() = action {
        app.settings.markIntroRead()
        state.first {it.loaded && it.preferences.hasReadIntro}
    }
    fun skipMuscleInput(enabled:Boolean) = action {app.settings.setSkipMuscleInput(enabled);muscleOmittedNotice.value=null}
    fun omitMuscleFromWeighing()=action {
        app.settings.setSkipMuscleInput(true)
        state.first {it.loaded && it.preferences.skipMuscleInput}
        muscleOmittedNotice.value=android.os.SystemClock.elapsedRealtime()+2000
    }
    private suspend fun completeSettingsAndAwait() {
        app.completePendingSettings()
        val actual=app.settings.flow.first()
        state.first {it.loaded && it.preferences==actual}
    }
    fun exportBackup(uri:Uri,previous:Boolean=false) = action {
        withContext(Dispatchers.IO) {
            val text=if(previous) {
                val file=File(app.filesDir,"pre-restore-backup.json")
                require(file.exists() || File(file.path+".bak").exists()) { "尚无恢复前副本" }
                AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use {it.readText()}
            } else BackupCodec.encode(repo.exportBackup(app.settings.flow.first()))
            requireNotNull(app.contentResolver.openOutputStream(uri,"wt")) { "无法写入所选文件" }.use {it.write(text.toByteArray(Charsets.UTF_8))}
        }
        message.value="备份已导出，请另外保存在电脑或网盘"
    }
    fun previewRestore(uri:Uri) = action {
        restoreData=null;restorePreview.value=null
        val backup=withContext(Dispatchers.IO) {
            val bytes=requireNotNull(app.contentResolver.openInputStream(uri)) {"无法读取文件"}.use { stream ->
                val buffer=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192)
                while(true) {val count=stream.read(chunk);if(count<0) break; require(buffer.size()+count<=BackupCodec.MAX_BYTES) {"备份超过 20 MB"};buffer.write(chunk,0,count)}
                buffer.toByteArray()
            }
            BackupCodec.decode(bytes.toString(Charsets.UTF_8))
        }
        restoreData=backup;restorePreview.value=backup
    }
    fun cancelRestore() {restoreData=null;restorePreview.value=null}
    fun confirmRestore() = action {
        val backup=requireNotNull(restoreData) {"请重新选择备份文件"}
        dataReady.value=false
        withContext(Dispatchers.IO) {
            val previous=BackupCodec.encode(repo.exportBackup(app.settings.flow.first()))
            val safety=AtomicFile(File(app.filesDir,"pre-restore-backup.json"))
            val stream=safety.startWrite()
            try {stream.write(previous.toByteArray(Charsets.UTF_8));safety.finishWrite(stream)} catch(e:Exception) {safety.failWrite(stream);throw e}
            repo.restoreBackup(backup)
        }
        cancelRestore();suggestion.value=null
        completeSettingsAndAwait();refreshDate();dataReady.value=true
        message.value="恢复完成。恢复前的数据副本可在数据管理中导出。提醒仍受系统通知权限控制。"
    }
    fun recommend(locks: Map<Long,Double>,preferValue:Boolean=false) = action {
        suggestion.value=null
        val calculationDay=today.value
        val policy=app.settings.flow.first().recommendationPolicy
        val result=withContext(Dispatchers.Default) {repo.recommend(calculationDay,locks,policy,preferValue)}
        require(calculationDay==LocalDate.now().toEpochDay() && today.value==calculationDay) {"日期已变化，请重新计算今日搭配"}
        suggestionDay=calculationDay;suggestion.value=result
    }
    fun adopt() = action {
        val value = requireNotNull(suggestion.value)
        require(suggestionDay==today.value && today.value==LocalDate.now().toEpochDay()) {"日期已变化，请重新计算搭配"}
        repo.record(today.value, value.quantities, true,app.settings.flow.first().recommendationPolicy); suggestion.value = null
    }
    fun reminder(enabled: Boolean, hour: Int, minute: Int) = action {
        app.settings.reminder(enabled,hour,minute)
        Reminders.schedule(app,enabled,hour,minute)
        message.value = if(enabled) "每日称重提醒已开启（系统可能延迟）" else "提醒已关闭"
    }
}



