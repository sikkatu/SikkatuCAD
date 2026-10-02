package com.sikkatu.sikkatucad

import android.content.Context

/**
 * Global string-resource holder for classes that have no Context access
 * (NestingStrategy implementations, NestingOptimizer).
 * Initialized from MainActivity.onCreate.
 */
object AppRes {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun getString(resId: Int): String = appContext.getString(resId)

    fun getString(resId: Int, vararg args: Any?): String =
        appContext.getString(resId, *args)
}
