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
    val foods: List<Food> = emptyList(), val intakes: List<Intake> = emptyList(), val preferences: Preferences = Preferences(), val loaded: Boolean = false, val exercises:List<Exercise> = emptyList())
class LedgerViewModel(application: Application): AndroidViewModel(application) {
    private val app = application as FitApp
    private val repo = app.repository
    val today = MutableStateFlow(LocalDate.now().toEpochDay())
    val message = MutableStateFlow<String?>(null)
    val muscleOmittedNotice=MutableStateFlow<Long?>(null)
    val busy = MutableStateFlow(false)
    val suggestion = MutableStateFlow<Suggestion?>(null)
    val dataReady=MutableStateFlow(false)
    val restorePreview=MutableStateFlow<Backup?>(null)
    private var restoreData:Backup?=null
    val state = flow {
        app.prepareData(); dataReady.value=true
        val core=combine(repo.dao.bodies(), repo.dao.targets(), repo.dao.foods(), repo.dao.intakes(), app.settings.flow) { b,t,f,i,p -> LedgerState(b,t,f,i,p,true) }
        emitAll(combine(core,repo.dao.exercises()) {s,e->s.copy(exercises=e)})
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
    fun recommend(locks: Map<Long,Double>) = action {
        val s = state.value
        val target = requireNotNull(s.targets.find { it.day == today.value }) { "请先称重" }
        val consumed = s.intakes.filter { it.day == today.value }.fold(Nutrition()) { a,i -> a+i.nutrition }
        suggestion.value = withContext(Dispatchers.Default) { Recommender.recommend(s.foods.map { it.candidate() }, target.nutrition-consumed, locks) }
    }
    fun adopt() = action {
        val value = requireNotNull(suggestion.value)
        repo.record(today.value, value.quantities, true); suggestion.value = null
    }
    fun reminder(enabled: Boolean, hour: Int, minute: Int) = action {
        app.settings.reminder(enabled,hour,minute)
        Reminders.schedule(app,enabled,hour,minute)
        message.value = if(enabled) "每日称重提醒已开启（系统可能延迟）" else "提醒已关闭"
    }
}



