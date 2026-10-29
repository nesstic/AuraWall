package com.aura.livewall.data

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

enum class Palette(val label: String, val rgb: FloatArray) {
    VIOLET("Чёрный ↔ Фиолетовый", floatArrayOf(124f, 58f, 237f)),
    GREEN("Чёрный ↔ Зелёный", floatArrayOf(16f, 185f, 96f))
}

@Suppress("UNCHECKED_CAST")
private class P<T : Any>(val sp: SharedPreferences, val k: String, val d: T) : ReadWriteProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = when (d) {
        is Float -> sp.getFloat(k, d)
        is Int -> sp.getInt(k, d)
        is Long -> sp.getLong(k, d)
        is Boolean -> sp.getBoolean(k, d)
        else -> d
    } as T

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        val e = sp.edit()
        when (value) {
            is Float -> e.putFloat(k, value)
            is Int -> e.putInt(k, value)
            is Long -> e.putLong(k, value)
            is Boolean -> e.putBoolean(k, value)
            else -> {}
        }
        e.apply()
    }
}

class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("aura", Context.MODE_PRIVATE)
    var characterId by P(sp, "cid", 0L)
    var palette by P(sp, "pal", 0)
    var speed by P(sp, "spd", 0.08f)        // Гц "дыхания" градиента
    var intensity by P(sp, "int", 0.55f)    // яркость градиента (низ всегда #000000)
    var scale by P(sp, "scl", 1f)
    var offX by P(sp, "ox", 0f)
    var offY by P(sp, "oy", 0f)
    var yaw by P(sp, "yaw", 0f)
    var spring by P(sp, "spr", 1f)
    var fps by P(sp, "fps", 60)
    var llmEnabled by P(sp, "llm", true)
    var thoughtMin by P(sp, "tm", 20)
}
