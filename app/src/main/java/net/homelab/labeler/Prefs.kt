package net.homelab.labeler

import android.content.Context

/**
 * Label geometry is stored in millimetres, not pixels. The M220 head is 72 mm
 * (576 dots) but your label stock is almost certainly narrower - 50x30 and
 * 40x30 are the common rolls. Rendering wider than the stock overflows it.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("labeler", Context.MODE_PRIVATE)

    var printerMac: String?
        get() = sp.getString(KEY_MAC, null)
        set(v) = sp.edit().putString(KEY_MAC, v).apply()

    var labelWidthMm: Int
        get() = sp.getInt(KEY_W, 50)
        set(v) = sp.edit().putInt(KEY_W, v).apply()

    var labelHeightMm: Int
        get() = sp.getInt(KEY_H, 30)
        set(v) = sp.edit().putInt(KEY_H, v).apply()

    /** 0x01 (slow) - 0x05 (fast). Slower generally means crisper QR modules. */
    var speed: Int
        get() = sp.getInt(KEY_SPEED, 3)
        set(v) = sp.edit().putInt(KEY_SPEED, v.coerceIn(1, 5)).apply()

    /** 0x01 - 0x0f. Bump this if QR codes scan unreliably. */
    var density: Int
        get() = sp.getInt(KEY_DENSITY, 8)
        set(v) = sp.edit().putInt(KEY_DENSITY, v.coerceIn(1, 15)).apply()

    /** See PhomemoM220.MEDIA_* constants. */
    var mediaType: Int
        get() = sp.getInt(KEY_MEDIA, PhomemoM220.MEDIA_LABEL_WITH_GAPS)
        set(v) = sp.edit().putInt(KEY_MEDIA, v).apply()

    private companion object {
        const val KEY_MAC = "printer_mac"
        const val KEY_W = "label_w_mm"
        const val KEY_H = "label_h_mm"
        const val KEY_SPEED = "speed"
        const val KEY_DENSITY = "density"
        const val KEY_MEDIA = "media_type"
    }
}
