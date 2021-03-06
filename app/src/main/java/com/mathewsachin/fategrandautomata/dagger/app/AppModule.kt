package com.mathewsachin.fategrandautomata.dagger.app

import com.mathewsachin.fategrandautomata.dagger.service.ScriptRunnerServiceComponent
import com.mathewsachin.fategrandautomata.prefs.PreferencesImpl
import com.mathewsachin.fategrandautomata.scripts.IImageLoader
import com.mathewsachin.fategrandautomata.scripts.prefs.IPreferences
import com.mathewsachin.fategrandautomata.util.ImageLoader
import dagger.Binds
import dagger.Module
import javax.inject.Singleton

@Module(subcomponents = [ScriptRunnerServiceComponent::class])
interface AppModule {
    @Singleton
    @Binds
    fun bindImageLoader(imageLoader: ImageLoader): IImageLoader

    @Singleton
    @Binds
    fun bindPrefs(prefs: PreferencesImpl): IPreferences
}