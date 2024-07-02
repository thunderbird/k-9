package com.fsck.k9.job

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import org.koin.core.parameter.parametersOf
import org.koin.java.KoinJavaComponent.getKoin

class K9WorkerFactory : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        val workerClass = Class.forName(workerClassName).kotlin
        return getKoin().getOrNull(workerClass) { parametersOf(workerParameters) }
    }
}
