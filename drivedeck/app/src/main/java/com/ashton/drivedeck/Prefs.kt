package com.ashton.drivedeck

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("dd", Context.MODE_PRIVATE)
    fun getInt(c: Context, k: String, d: Int): Int = sp(c).getInt(k, d)
    fun setInt(c: Context, k: String, v: Int) { sp(c).edit().putInt(k, v).apply() }
}
