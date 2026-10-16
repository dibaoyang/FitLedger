package com.fitledger.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.fitledger.data.Preferences

enum class StartupStage { LOADING, SYNC, INTRO, SETUP, WEIGH, MAIN }

fun startupStage(loaded:Boolean,ready:Boolean,p:Preferences,hasTodayBody:Boolean):StartupStage=when {
    !loaded -> StartupStage.LOADING
    !ready -> StartupStage.SYNC
    !p.hasReadIntro -> StartupStage.INTRO
    p.profile==null -> StartupStage.SETUP
    !hasTodayBody -> StartupStage.WEIGH
    else -> StartupStage.MAIN
}

@Composable fun FirstSetupScreen(vm:LedgerViewModel,p:Preferences,busy:Boolean,day:Long) {
    Text("首次设置",style=MaterialTheme.typography.headlineSmall)
    Text("先填写只需保存一次的基础资料。以后每天只录入当天身体数据，这些资料可随时在设置中修改。")
    ProfileEditor(vm,p.profile,busy,day,firstSetup=true)
}
