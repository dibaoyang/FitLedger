package com.fitledger.ui

import com.fitledger.data.Preferences
import com.fitledger.domain.BodyProfile
import com.fitledger.domain.Sex
import org.junit.Assert.*
import org.junit.Test

class StartupFlowTest {
    private val configured=Preferences(hasReadIntro=true,profile=BodyProfile(Sex.MALE,175.0,10957,1.2))
    @Test fun loadingAndRecoveryAlwaysPrecedeEntry() {
        assertEquals(StartupStage.LOADING,startupStage(false,true,configured,true))
        assertEquals(StartupStage.SYNC,startupStage(true,false,configured,true))
    }
    @Test fun introAppearsOnUpgradeButExistingProfileIsNotAskedAgain() {
        assertEquals(StartupStage.INTRO,startupStage(true,true,configured.copy(hasReadIntro=false),true))
        assertEquals(StartupStage.MAIN,startupStage(true,true,configured,true))
        assertEquals(StartupStage.WEIGH,startupStage(true,true,configured,false))
    }
    @Test fun firstSetupPrecedesDailyEntryAndCannotSkipByExistingBody() {
        for(hasBody in listOf(false,true)) assertEquals(StartupStage.SETUP,startupStage(true,true,Preferences(hasReadIntro=true),hasBody))
        assertEquals(StartupStage.INTRO,startupStage(true,true,Preferences(),false))
    }
}
