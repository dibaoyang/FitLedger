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
import androidx.room.withTransaction
import com.fitledger.widget.Widgets

data class UndoNotice(val token:Long,val text:String)
private sealed interface UndoEntry {
    data class FoodRemoved(val snapshot:Food):UndoEntry
    data class IntakeRemoved(val snapshot:Intake):UndoEntry
    data class ExerciseRemoved(val snapshot:Exercise,val before:DailyTarget?=null,val after:DailyTarget?=null):UndoEntry
}
private data class ComparisonContext(val day:Long,val foods:List<Food>,val zones:List<FoodZone>,val intakes:List<Intake>,val body:BodyRecord,val target:DailyTarget,val policy:RecommendationPolicy,val locks:Map<Long,Double>,val allIntakes:List<Intake>,val combos:List<FoodCombo>,val extras:RecommendationExtras)

data class LedgerState(val bodies: List<BodyRecord> = emptyList(), val targets: List<DailyTarget> = emptyList(),
    val foods: List<Food> = emptyList(), val intakes: List<Intake> = emptyList(), val preferences: Preferences = Preferences(), val loaded: Boolean = false, val exercises:List<Exercise> = emptyList(),
    val zones:List<FoodZone> = emptyList(),val allFoods:List<Food> = emptyList(),
    val dailyFoods:List<DailyFoodTemplate> = emptyList(),val dailyApplications:List<DailyFoodApplication> = emptyList(),val dayModes:List<DayMode> = emptyList(),
    val combos:List<FoodCombo> = emptyList(),val dailySkips:List<DailyFoodSkip> = emptyList(),
    val presets:List<FoodPresetPlan> = emptyList(),val presetItems:List<FoodPresetItem> = emptyList(),
    val recipes:List<FoodRecipe> = emptyList(),val pendingFoods:List<PendingFood> = emptyList(),val recipeBatches:List<RecipeBatch> = emptyList())
class LedgerViewModel(application: Application): AndroidViewModel(application) {
    private val app = application as FitApp
    private val repo = app.repository
    private val daily=DailyEatingStore(app.database)
    private val features=FeatureStore(app.database)
    private val foodPresets=FoodPresetStore(app.database)
    private val recipeStore=RecipeStore(app.database)
    private val batchFoods=BatchFoodStore(app.database)
    private val pendingFoods=PendingFoodStore(app.database)
    private val recipeBatches=RecipeBatchStore(app.database)
    val today = MutableStateFlow(LocalDate.now().toEpochDay())
    val message = MutableStateFlow<String?>(null)
    val muscleOmittedNotice=MutableStateFlow<Long?>(null)
    val busy = MutableStateFlow(false)
    val suggestion = MutableStateFlow<Suggestion?>(null)
    val diagnostics=MutableStateFlow<List<Diagnostic>>(emptyList())
    val recommendationTrial=MutableStateFlow<RecommendationTrialResult?>(null)
    val comparisons=MutableStateFlow<ComparisonBatch?>(null)
    private var comparisonContext:ComparisonContext?=null
    private var comparisonSeed=0
    val undoNotice=MutableStateFlow<UndoNotice?>(null)
    private var undoEntry:Pair<Long,UndoEntry>?=null
    private var suggestionDay:Long?=null
    private var diagnosticDay:Long?=null
    private var diagnosticBasePolicy:RecommendationPolicy?=null
    val dataReady=MutableStateFlow(false)
    val restorePreview=MutableStateFlow<Backup?>(null)
    private var restoreData:Backup?=null
    val state = flow {
        app.prepareData()
        app.dataLock.withLock {daily.apply(today.value)}
        Widgets.requestRefresh(app)
        dataReady.value=true
        val core=combine(repo.dao.bodies(), repo.dao.targets(), repo.dao.foods(), repo.dao.intakes(), app.settings.flow) { b,t,f,i,p -> LedgerState(b,t,f,i,p,true) }
        val library=combine(core,repo.dao.exercises(),repo.dao.zones(),repo.dao.allFoodsFlow()) {s,e,z,f->s.copy(exercises=e,zones=z,allFoods=f)}
        val eating=combine(library,daily.dao.templates(),daily.dao.applications(),daily.dao.dayModes()) {s,f,a,m->s.copy(dailyFoods=f,dailyApplications=a,dayModes=m)}
        val convenient=combine(eating,features.dao.combos(),daily.dao.skips()) {s,c,k->s.copy(combos=c,dailySkips=k)}
        val planned=combine(convenient,foodPresets.dao.plans(),foodPresets.dao.items()) {s,p,i->s.copy(presets=p,presetItems=i)}
        val recipes=combine(planned,app.database.recipeDao().recipes()) {s,r->s.copy(recipes=r)}
        val waiting=combine(recipes,app.database.pendingFoodDao().pendingFoods()) {s,p->s.copy(pendingFoods=p)}
        emitAll(combine(waiting,app.database.recipeBatchDao().batches()) {s,b->s.copy(recipeBatches=b)})
    }.retryWhen { error, _ -> message.value="读取失败：${error.message}。未清空任何数据。"; delay(5000);true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LedgerState())
    init { viewModelScope.launch { while(isActive) { refreshDate(); delay(1000) } } }
    fun refreshDate() { val day = LocalDate.now().toEpochDay(); if(today.value != day) { today.value = day; invalidateRecommendation() } }
    fun invalidateRecommendation() {suggestion.value=null;diagnostics.value=emptyList();recommendationTrial.value=null;diagnosticDay=null;diagnosticBasePolicy=null;comparisons.value=null;comparisonContext=null}
    private fun action(keepRecommendation:Boolean=false,block: suspend () -> Unit) {
        if(busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { app.dataLock.withLock { app.completePendingSettings(); block() };if(!keepRecommendation) invalidateRecommendation();Widgets.requestRefresh(app) } catch(e: CancellationException) { throw e } catch(e: Exception) {
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
    fun deleteZone(id:Long)=action {
        val p=app.settings.flow.first()
        fun prune(e:RecommendationExtras)=e.copy(rules=e.rules.copy(conditions=e.rules.conditions.filterNot {it.zoneId==id || it.triggerKind=="ZONE" && it.triggerId==id}))
        val next=p.copy(recommendationExtras=prune(p.recommendationExtras),recommendationProfiles=p.recommendationProfiles.map {it.copy(extras=prune(it.extras))})
        app.database.withTransaction {repo.deleteZone(id);if(next!=p) repo.dao.stageSettings(PendingSettings(payload=PreferencesCodec.encode(next)))}
        if(next!=p) {dataReady.value=false;completeSettingsAndAwait();dataReady.value=true}
        message.value="分区已删除，食品移至未分区；引用此分区的搭配条件已移除"
    }
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
    private fun presetAction(keepRecommendation:Boolean=true,block:suspend ()->Unit) {
        val expectedToday=today.value
        action(keepRecommendation) {
            require(expectedToday==today.value && expectedToday==LocalDate.now().toEpochDay()) {"日期已变化，请重新打开预设后操作"}
            block()
        }
    }
    fun savePresetItem(day:Long,foodId:Long,quantity:Double,itemId:Long?=null,expectedUnit:String?=null,onSaved:()->Unit={})=presetAction {
        foodPresets.saveItem(day,foodId,quantity,itemId,expectedUnit);onSaved()
        message.value=if(day==today.value) "预设已更新，确认添加后才计入今日摄入" else "未来预设已保存，不计入今日摄入"
    }
    fun removePresetItem(day:Long,itemId:Long)=presetAction {
        foodPresets.removeItem(day,itemId);message.value="未添加的预设已删除，已有摄入和历史记录保持不变"
    }
    fun clearPreset(day:Long)=presetAction {
        foodPresets.clear(day);message.value="未添加的预设已清空，已经添加的摄入与标记保持不变"
    }
    fun refreshPresetReference(day:Long)=presetAction {
        foodPresets.refreshReference(day);message.value="已按今天保存的消耗与计入的额外运动更新预估基准"
    }
    fun applyPreset(day:Long,itemIds:List<Long>)=presetAction(keepRecommendation=false) {
        val count=foodPresets.apply(day,itemIds)
        message.value="已将 $count 项预设加入今日摄入；今日身体数据、目标和其他记录保持不变"
    }
    fun indulgence(day:Long,enabled:Boolean)=action {daily.setIndulgence(day,enabled);suggestion.value=null}
    fun savePresetRatios(day:Long,encoded:String)=presetAction {
        foodPresets.saveRatios(day,encoded);message.value="预设重量比例已保存，请预览并确认取整后的数量"
    }
    fun applyPresetRatios(preview:PresetRatioPreview)=presetAction {
        foodPresets.applyRatios(preview);message.value="已按确认的重量比例更新预设，实际摄入不变"
    }
    fun editQuantity(id:Long,quantity:Double)=action {daily.editIntakeQuantity(today.value,id,quantity);suggestion.value=null}
    fun skipDailyFood(id:Long,skip:Boolean,removeRecorded:Boolean=false)=action {
        var removed:Intake?=null
        app.database.withTransaction {
            daily.setSkipped(today.value,id,skip)
            if(skip && removeRecorded) {
                val application=daily.dao.application(today.value,id)
                val intake=application?.let {repo.dao.intake(it.intakeId)}
                if(intake!=null && intake.day==today.value && intake.source=="ROUTINE") removed=features.removeIntake(today.value,intake.id)
            }
        }
        removed?.let {offerUndo(UndoEntry.IntakeRemoved(it),"今日已暂停，并删除这次自动摄入；撤销仅恢复摄入")}
        if(removed==null) message.value=if(skip) "今天暂停此固定项，已经记录的摄入保留；其他日期按原规则执行" else "今日暂停已取消；已经添加或手动删除过的项目不会重复补记"
    }
    fun saveCombo(combo:FoodCombo,onSaved:()->Unit={})=action {features.saveCombo(combo);onSaved();message.value="常吃组合已保存"}
    fun saveRecipe(recipe:FoodRecipe,publish:Boolean,expected:FoodRecipe?,expectedFood:Food?,onSaved:()->Unit={})=action {
        recipeStore.save(recipe,publish,expected,expectedFood);onSaved()
        message.value=if(publish) "配方及食品已保存，历史摄入不变" else "配方已保存，尚未修改食品库"
    }
    fun deleteRecipe(recipe:FoodRecipe)=action {recipeStore.delete(recipe);message.value="配方已删除，已有食品与历史摄入保留"}
    fun saveRecipeBatch(batch:RecipeBatch,recipe:FoodRecipe,publish:Boolean,onSaved:()->Unit={})=action {
        recipeBatches.save(batch,recipe,publish);onSaved();message.value="本批成品与分装快照已保存，其他批次和历史摄入保留"
    }
    fun deleteRecipeBatch(batch:RecipeBatch)=action {recipeBatches.delete(batch);message.value="批次已删除，生成食品和历史摄入保留"}
    fun markPendingEaten(item:PendingFood)=action {pendingFoods.markEaten(item);message.value="已吃，已计入今日实际摄入"}
    fun updatePendingFood(preview:PendingFoodUpdate)=action {pendingFoods.update(preview);message.value="待吃数量与资料已按确认预览更新"}
    fun removePendingFood(item:PendingFood)=action {pendingFoods.remove(item);message.value="未吃的安排已移除，实际摄入不变"}
    fun applyFoodBatch(expected:List<Food>,patch:FoodBatchPatch,onSaved:()->Unit={})=action {
        val count=batchFoods.apply(expected,patch);onSaved();message.value="已批量更新 $count 种食品，历史记录不变"
    }
    fun recommendationExtras(extras:RecommendationExtras)=action {
        app.database.withTransaction {
            ExtendedRecommendations.validateConfiguration(repo.dao.allFoods(),features.dao.allCombos(),extras)
            RecommendationRuleContext.validateReferences(repo.dao.allFoods(),repo.dao.allZones(),extras.rules)
        }
        app.settings.setRecommendationExtras(extras);message.value="推荐扩展设置已保存，历史目标不变"
    }
    fun recommendationRules(rules:RecommendationRules)=action {
        val p=app.settings.flow.first();val extras=p.recommendationExtras.copy(rules=rules)
        app.database.withTransaction {
            ExtendedRecommendations.validateConfiguration(repo.dao.allFoods(),features.dao.allCombos(),extras)
            RecommendationRuleContext.validateReferences(repo.dao.allFoods(),repo.dao.allZones(),rules)
        }
        app.settings.setRecommendationExtras(extras);message.value="食品周期、搭配和准备条件已保存"
    }
    fun saveRecommendationProfile(profile:RecommendationProfile)=action {
        profile.validate();val p=app.settings.flow.first()
        val old=p.recommendationProfiles.find {it.id==profile.id}
        if(old==null || old.policy!=profile.policy || old.extras!=profile.extras) app.database.withTransaction {
            ExtendedRecommendations.validateConfiguration(repo.dao.allFoods(),features.dao.allCombos(),profile.extras)
            RecommendationRuleContext.validateReferences(repo.dao.allFoods(),repo.dao.allZones(),profile.extras.rules)
        }
        val next=if(old==null) p.recommendationProfiles+profile else p.recommendationProfiles.map {if(it.id==profile.id) profile else it}
        app.settings.setRecommendationProfiles(next);message.value="推荐条件档案已保存"
    }
    fun deleteRecommendationProfile(id:String)=action {
        val p=app.settings.flow.first();require(p.recommendationProfiles.any {it.id==id}) {"档案已删除，请刷新"}
        app.settings.setRecommendationProfiles(p.recommendationProfiles.filterNot {it.id==id});message.value="档案已删除，当前推荐条件保留"
    }
    fun applyRecommendationProfile(profile:RecommendationProfile)=action {
        val p=app.settings.flow.first();require(p.recommendationProfiles.any {it==profile}) {"档案已变化，请重新核对"}
        app.database.withTransaction {
            ExtendedRecommendations.validateConfiguration(repo.dao.allFoods(),features.dao.allCombos(),profile.extras)
            RecommendationRuleContext.validateReferences(repo.dao.allFoods(),repo.dao.allZones(),profile.extras.rules)
            repo.dao.stageSettings(PendingSettings(payload=PreferencesCodec.encode(p.copy(recommendationPolicy=profile.policy,recommendationExtras=profile.extras))))
        }
        dataReady.value=false;completeSettingsAndAwait();dataReady.value=true;message.value="推荐条件已切换，请重新计算；目标和摄入保持原样"
    }
    fun deleteCombo(id:Long)=action {
        val p=app.settings.flow.first()
        val next=p.copy(recommendationExtras=p.recommendationExtras.copy(comboIds=p.recommendationExtras.comboIds-id),
            recommendationProfiles=p.recommendationProfiles.map {it.copy(extras=it.extras.copy(comboIds=it.extras.comboIds-id))})
        app.database.withTransaction {
            features.deleteCombo(id)
            if(next!=p) repo.dao.stageSettings(PendingSettings(payload=PreferencesCodec.encode(next)))
        }
        if(next!=p) {dataReady.value=false;completeSettingsAndAwait();dataReady.value=true}
        message.value="组合已删除，已经记录的摄入保留"
    }
    fun recordCombo(id:Long,quantities:Map<Long,Double>)=action {require(today.value==LocalDate.now().toEpochDay()) {"日期已变化，请重新进入今日后添加组合"};features.recordCombo(today.value,id,quantities);suggestion.value=null;message.value="组合已加入今日摄入，模板保持不变"}
    fun restoreFood(id:Long)=action {
        val movedToUnassigned=features.restoreFood(id);suggestion.value=null
        message.value=if(movedToUnassigned) "食品已恢复至未分区，原分区单位或规则已变化，历史记录不变" else "食品已恢复，历史记录保持不变"
    }
    fun favorite(food:Food)=action {val saved=requireNotNull(repo.dao.food(food.id)) {"食品不存在"};repo.saveFood(saved.copy(favorite=!saved.favorite))}
    fun foodAllowed(id:Long,allowed:Boolean)=action {val saved=requireNotNull(repo.dao.food(id)) {"食品不存在"};repo.saveFood(saved.copy(allowed=allowed))}
    fun libraryGroupsCollapsed(keys:Set<String>,collapsed:Boolean)=action(keepRecommendation=true) {
        app.settings.setLibraryGroupsCollapsed(keys,collapsed)
    }
    fun exportRecords(uri:Uri,request:RecordExportRequest,format:RecordExportFormat)=action(keepRecommendation=true) {
        val preferences=app.settings.flow.first()
        val source=app.database.withTransaction {
            RecordExportSource(repo.dao.allBodies(),repo.dao.allTargets(),repo.dao.allIntakes(),repo.dao.allExercises())
        }
        val report=withContext(Dispatchers.Default) {
            RecordExports.build(request,source,preferences.visibility,preferences.energyUnit)
        }
        withContext(Dispatchers.IO) {
            when(format) {
                RecordExportFormat.CSV -> {
                    val bytes=RecordExports.csv(report).toByteArray(Charsets.UTF_8)
                    requireNotNull(app.contentResolver.openOutputStream(uri,"wt")) {"无法写入所选文件"}.use {it.write(bytes)}
                }
                RecordExportFormat.PNG -> {
                    val bitmap=RecordExportImages.render(report)
                    try {
                        requireNotNull(app.contentResolver.openOutputStream(uri,"wt")) {"无法写入所选文件"}.use {
                            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) {"图片写入失败，请重新导出"}
                        }
                    } finally {bitmap.recycle()}
                }
            }
        }
        message.value="所选记录已导出；隐藏身体项目已按当前隐私设置排除"
    }
    private fun offerUndo(entry:UndoEntry,text:String) {
        val token=System.nanoTime();undoEntry=token to entry;undoNotice.value=UndoNotice(token,text)
    }
    fun clearUndo(token:Long?=null) {if(token==null || undoEntry?.first==token) {undoEntry=null;undoNotice.value=null}}
    fun undo(token:Long) {
        if(busy.value) {viewModelScope.launch {busy.first {!it};if(undoEntry?.first==token) undo(token)};return}
        action {
        val entry=undoEntry?.takeIf {it.first==token}?.second ?: error("这次撤销已失效")
        var foodMovedToUnassigned=false
        app.database.withTransaction {
            when(entry) {
                is UndoEntry.FoodRemoved -> {
                    require(repo.dao.food(entry.snapshot.id)==entry.snapshot.copy(isDeleted=true)) {"食品已发生变化，请到回收站核对后恢复"}
                    foodMovedToUnassigned=features.restoreFood(entry.snapshot.id)
                }
                is UndoEntry.IntakeRemoved -> features.restoreIntake(entry.snapshot)
                is UndoEntry.ExerciseRemoved -> {
                    if(entry.before!=null) require(entry.snapshot.day==LocalDate.now().toEpochDay()) {"日期已变化，不能撤销昨天的目标回补；可在历史日历仅补回运动并保留原目标"}
                    if(entry.before!=null) require(repo.dao.target(entry.snapshot.day)==entry.after) {"目标已发生变化，请在运动记录中重新添加并确认回补"}
                    features.restoreExercise(entry.snapshot)
                    entry.before?.let {check(repo.dao.updateTarget(it)==1)}
                }
            }
        }
        clearUndo(token);suggestion.value=null
        message.value=if(foodMovedToUnassigned) "已撤销删除，食品恢复至未分区；原分区单位或规则已变化，历史记录不变" else "已撤销删除"
        }
    }
    fun historyRecordFood(day:Long,id:Long,q:Double)=action {features.recordFood(day,id,q);suggestion.value=null;message.value="摄入已保存，该日身体和目标快照不变"}
    fun historyRecordDirect(day:Long,c:Double,p:Double,f:Double,editingId:Long?=null)=action {
        if(editingId==null) features.addDirect(day,c,p,f) else features.editDirect(day,editingId,c,p,f)
        suggestion.value=null;message.value="营养记录已保存，该日身体和目标快照不变"
    }
    fun historyEditQuantity(day:Long,id:Long,q:Double)=action {features.editIntake(day,id,q);suggestion.value=null;message.value="本次摄入量已更正，按原记录营养和费用同比调整"}
    fun historyRemoveIntake(day:Long,id:Long)=action {val snapshot=features.removeIntake(day,id);suggestion.value=null;offerUndo(UndoEntry.IntakeRemoved(snapshot),"已删除 ${snapshot.name} 这次摄入")}
    fun historySaveExercise(day:Long,id:Long?,type:Cardio?,minutes:Double?,kcal:Double?,included:Boolean)=action {
        features.saveExercise(day,id,type,minutes,kcal,included);suggestion.value=null;message.value="运动记录已保存，身体和目标快照保持不变"
    }
    fun historyRemoveExercise(day:Long,id:Long)=action {val snapshot=features.removeExercise(day,id);suggestion.value=null;offerUndo(UndoEntry.ExerciseRemoved(snapshot),"运动记录已删除")}
    fun copyHistory(items:Map<Long,Double>,onDone:()->Unit={})=action {
        require(items.isNotEmpty()) {"请先选择要复制的记录"}
        val entries=repo.dao.allIntakes().filter {it.id in items}
        require(entries.size==items.size && entries.map {it.day}.distinct().size==1) {"原记录已变化，请重新选择同一天的摄入"}
        val result=features.copyIntakesToToday(entries.first().day,items)
        suggestion.value=null;onDone();message.value="已复制 ${result.count} 条摄入到今天"+result.warnings.takeIf {it.isNotEmpty()}?.joinToString("；",prefix="；").orEmpty()
    }
    fun recommendationPolicy(policy:RecommendationPolicy)=action {app.settings.setRecommendationPolicy(policy);suggestion.value=null;message.value="推荐约束已保存，手动记录不受限制"}
    fun importFoodCode(code:String,allowed:Boolean,onSaved:()->Unit)=action {
        repo.importFoodCode(code,allowed);suggestion.value=null;onSaved();message.value="食品已添加，原有食品和饮食记录保持不变"
    }
    fun deleteFood(id:Long,onDeleted:()->Unit={}) = action {
        val snapshot=requireNotNull(repo.dao.food(id)) {"食品不存在"}
        repo.deleteFood(id);suggestion.value=null
        // Wait for the active-food list before accepting another record/recommendation action.
        state.first {it.loaded && it.foods.none {food->food.id==id}}
        onDeleted();offerUndo(UndoEntry.FoodRemoved(snapshot),"${snapshot.name}已放入食品回收站，摄入记录保留")
    }
    fun record(id: Long, quantity: Double) = action { repo.record(today.value, mapOf(id to quantity)); suggestion.value = null }
    fun recordEnergy(day:Long,kcal:Double,name:String="仅热量记录",costYuan:Double?=null)=action {
        repo.recordEnergy(day,kcal,name,costYuan);message.value="热量已记录，碳水、蛋白质和脂肪保留未知"
    }
    fun historyRecordEnergy(day:Long,kcal:Double,name:String,costYuan:Double?,editingId:Long?=null)=action {
        if(editingId==null) features.addEnergy(day,kcal,name,costYuan) else features.editEnergy(day,editingId,kcal,name,costYuan)
        message.value="仅热量记录已保存，身体、目标和其他日期保持不变"
    }
    fun completeEnergy(day:Long,id:Long,carbs:Double,protein:Double,fat:Double)=action {
        features.completeEnergy(day,id,carbs,protein,fat);message.value="营养已补全，已按确认的三大营养重新计算本条热量"
    }
    fun recordNutrition(day:Long,carbs:String,protein:String,fat:String)=action {
        repo.recordNutrition(day,DirectNutrition.parse(carbs,protein,fat));suggestion.value=null
        message.value="营养已添加到今日摄入；输入内容保留，可修改后再次添加或点击清空"
    }
    fun exercise(day:Long,type:Cardio?,minutes:Double?,kcal:Double?,included:Boolean,updateTarget:Boolean=false)=action {
        repo.exercise(day,type,minutes,kcal,included,updateTarget);suggestion.value=null
        if(updateTarget) message.value="运动已记录，按今日计划更新目标；饮食记录和历史不变"
    }
    fun removeExercise(day:Long,id:Long,updateTarget:Boolean=false)=action {
        val snapshot=requireNotNull(repo.dao.allExercises().find {it.id==id && it.day==day}) {"运动记录已删除"}
        val before=if(updateTarget) repo.dao.target(day) else null
        repo.removeExercise(day,id,updateTarget);suggestion.value=null
        offerUndo(UndoEntry.ExerciseRemoved(snapshot,before,if(updateTarget) repo.dao.target(day) else null),"运动记录已删除")
    }
    fun recalculateExerciseTarget(day:Long)=action {repo.recalculateExerciseTarget(day);suggestion.value=null;message.value="已按今日运动重算目标，已有饮食及历史保持不变"}
    fun remove(id: Long) = historyRemoveIntake(today.value,id)
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
        clearUndo();diagnostics.value=emptyList();recommendationTrial.value=null
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
    fun recommend(locks: Map<Long,Double>,preferValue:Boolean=false) = action(true) {
        suggestion.value=null
        diagnostics.value=emptyList();recommendationTrial.value=null
        val calculationDay=today.value
        val preferences=app.settings.flow.first()
        val policy=preferences.recommendationPolicy
        val result=try {withContext(Dispatchers.Default) {repo.recommend(calculationDay,locks,policy,preferValue,preferences.recommendationExtras)}} catch(e:IllegalArgumentException) {
            if(preferences.recommendationExtras.enabled || repo.dao.allFoods().any {!it.isDeleted && it.allowed && it.minimum>0}) throw e
            val foods=repo.dao.allFoods();val intakes=repo.dao.allIntakes()
            val target=repo.dao.target(calculationDay)?.nutrition
            if(target!=null && repo.dao.body(calculationDay)!=null && daily.dao.dayMode(calculationDay)?.isIndulgence!=true && calculationDay==LocalDate.now().toEpochDay()) {
                val entries=intakes.filter {it.day==calculationDay}
                val consumed=IntakeNutritionTotals.requireKnown(entries)
                val spent=if(entries.any {it.costYuan==null}) null else entries.sumOf {it.costYuan!!}
                val zones=repo.dao.allZones()
                diagnostics.value=RecommendationDiagnostics.analyze(FoodZoneQuotas.candidates(foods,zones),target-consumed,locks,FoodZoneQuotas.limits(zones,foods,intakes,calculationDay),policy,spent,e.message,preferValue)
                diagnosticDay=calculationDay;diagnosticBasePolicy=policy
            }
            throw e
        }
        require(calculationDay==LocalDate.now().toEpochDay() && today.value==calculationDay) {"日期已变化，请重新计算今日搭配"}
        suggestionDay=calculationDay;suggestion.value=result
    }
    fun trialRecommendation(option:RecommendationTrialOption,locks:Map<Long,Double>,preferValue:Boolean)=action(true) {
        suggestion.value=null
        val day=today.value
        require(diagnosticDay==day && day==LocalDate.now().toEpochDay() && diagnostics.value.any {it.trial==option} && app.settings.flow.first().recommendationPolicy==diagnosticBasePolicy) {"推荐条件已变化，请重新正式计算以查看当前诊断"}
        val result=try {withContext(Dispatchers.Default) {repo.recommend(day,locks,option.policy,preferValue,app.settings.flow.first().recommendationExtras)}} catch(e:IllegalArgumentException) {
            recommendationTrial.value=RecommendationTrialResult(option,failure=e.message);return@action
        }
        require(day==LocalDate.now().toEpochDay()) {"日期已变化，请重新试算"}
        recommendationTrial.value=RecommendationTrialResult(option,suggestion=result)
    }
    fun applyTrialPolicy(option:RecommendationTrialOption)=action {
        require(diagnosticDay==today.value && today.value==LocalDate.now().toEpochDay() && recommendationTrial.value?.let {it.option==option && it.suggestion!=null}==true && diagnostics.value.any {it.trial==option} && app.settings.flow.first().recommendationPolicy==diagnosticBasePolicy) {"试算或正式条件已变化，请重新试算后再确认"}
        app.settings.setRecommendationPolicy(option.policy);suggestion.value=null;diagnostics.value=emptyList();recommendationTrial.value=null
        message.value="推荐条件已按确认的试算条件保存，请重新正式计算后再采用"
    }
    private suspend fun comparisonSnapshot(locks:Map<Long,Double>):ComparisonContext=app.database.withTransaction {
        val day=LocalDate.now().toEpochDay()
        require(today.value==day) {"日期已变化，请重新进入今天后生成方案"}
        val body=requireNotNull(repo.dao.body(day)) {"请先完成今日身体记录"}
        val target=requireNotNull(repo.dao.target(day)) {"请先生成今日目标"}
        require(daily.dao.dayMode(day)?.isIndulgence!=true) {"放纵日已暂停推荐，请先恢复今日记录"}
        val allIntakes=repo.dao.allIntakes()
        val intakes=allIntakes.filter {it.day==day}
        IntakeNutritionTotals.requireKnown(intakes)
        val p=app.settings.flow.first()
        ComparisonContext(day,repo.dao.allFoods(),repo.dao.allZones(),intakes,body,target,p.recommendationPolicy,locks.toMap(),allIntakes,features.dao.allCombos(),p.recommendationExtras)
    }
    fun compareRecommendations(locks:Map<Long,Double>,more:Boolean=false)=action(true) {
        val context=comparisonSnapshot(locks)
        if(more) require(context==comparisonContext && comparisons.value!=null) {"食品、摄入或条件已变化，请重新生成对比"}
        suggestion.value=null;diagnostics.value=emptyList();recommendationTrial.value=null
        if(!more) {comparisonSeed=0;comparisons.value=null}
        val existing=if(more) comparisons.value?.options.orEmpty() else emptyList()
        require(existing.size<50) {"本轮已达到 50 套上限"}
        val batch=withContext(Dispatchers.Default) {
            ExtendedRecommendations.generate(context.day,context.foods,context.zones,context.allIntakes,context.combos,context.target.nutrition,
                context.locks,context.policy,context.extras,existing,10,comparisonSeed++)
        }
        require(context.day==LocalDate.now().toEpochDay() && today.value==context.day) {"日期已变化，请重新生成方案"}
        comparisonContext=context;comparisons.value=batch
        if(batch.options.isEmpty()) message.value=batch.note
    }
    fun adoptComparison(option:ComparisonOption,adjustPolicy:Boolean)=action {
        val context=requireNotNull(comparisonContext) {"这轮方案已失效，请重新生成"}
        require(comparisons.value?.options?.any {it==option}==true) {"这套方案已变化，请重新查看表格"}
        require(comparisonSnapshot(context.locks)==context) {"食品、摄入或设置已经变化，请重新生成，未记录任何食品"}
        val quantities=option.suggestion.quantities
        require(context.locks.all {(id,q)->quantities[id]==q}) {"方案与必吃食品不一致，请重新生成"}
        val policy=if(adjustPolicy) option.proposedPolicy else context.policy
        require(adjustPolicy || option.formalPolicySatisfied) {"该方案需要先确认调整推荐条件"}
        policy.validate()
        val preferences=app.settings.flow.first()
        app.database.withTransaction {
            // Save an intention only; actual intake is written after the explicit 已吃 action.
            pendingFoods.saveRecommendation(context.day,quantities,policy,context.extras)
            if(adjustPolicy && policy!=preferences.recommendationPolicy) repo.dao.stageSettings(PendingSettings(payload=PreferencesCodec.encode(preferences.copy(recommendationPolicy=policy))))
        }
        if(adjustPolicy && policy!=preferences.recommendationPolicy) {
            dataReady.value=false;completeSettingsAndAwait();dataReady.value=true
        }
        message.value=if(adjustPolicy) "条件已调整，方案已存为待吃；吃后点击已吃" else "方案已加入待吃清单，吃后点击已吃"
    }
    fun adopt() = action {
        val value = requireNotNull(suggestion.value)
        require(suggestionDay==today.value && today.value==LocalDate.now().toEpochDay()) {"日期已变化，请重新计算搭配"}
        val p=app.settings.flow.first()
        pendingFoods.saveRecommendation(today.value,value.quantities,p.recommendationPolicy,p.recommendationExtras); suggestion.value = null
        message.value="推荐已存为待吃清单，吃后点击已吃才计入摄入"
    }
    fun reminder(enabled: Boolean, hour: Int, minute: Int) = action {
        app.settings.reminder(enabled,hour,minute)
        Reminders.schedule(app,enabled,hour,minute)
        message.value = if(enabled) "每日称重提醒已开启（系统可能延迟）" else "提醒已关闭"
    }
}



