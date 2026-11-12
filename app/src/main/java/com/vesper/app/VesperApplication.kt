package com.vesper.app

import android.app.Application
import android.content.Context
import com.vesper.app.data.context.LocationWeatherProvider
import com.vesper.app.data.local.SettingsRepository
import com.vesper.app.data.remote.GeminiClient
import com.vesper.app.data.wallpaper.WallpaperProcessor
import com.vesper.app.domain.usecase.WallpaperPipelineUseCase
import com.vesper.app.worker.ScheduleManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val settingsRepository = SettingsRepository(appContext)
    val locationWeatherProvider = LocationWeatherProvider(appContext, httpClient)
    val geminiClient = GeminiClient(httpClient)
    val wallpaperProcessor = WallpaperProcessor(appContext)
    val wallpaperPipeline = WallpaperPipelineUseCase(
        context = appContext,
        settingsRepo = settingsRepository,
        contextProvider = locationWeatherProvider,
        geminiClient = geminiClient,
        wallpaperProcessor = wallpaperProcessor
    )
}

class VesperApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ScheduleManager.ensureNotificationChannel(this)
    }
}
