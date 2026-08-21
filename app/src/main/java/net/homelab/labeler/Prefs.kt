package net.homelab.labeler

import android.content.Context

/**
 * Label geometry is stored in millimeters, not pixels. The M220 head is 72 mm
 * (576 dots) but your label stock is almost certainly narrower - 50x30 and
 * 40x30 are the common rolls. Rendering wider than the stock overflows it.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("labeler", Context.MODE_PRIVATE)

    /** BLE address of the chosen printer. Null until the user picks one. */
    var printerMac: String?
        get() = sp.getString(KEY_MAC, null)
        set(v) = sp.edit().putString(KEY_MAC, v).apply()

    /** Cosmetic, so settings can name the saved printer without rescanning. */
    var printerName: String?
        get() = sp.getString(KEY_NAME, null)
        set(v) = sp.edit().putString(KEY_NAME, v).apply()

    /**
     * Which characteristic the job is written to, once the user has confirmed
     * one by eye. Null means fall back to automatic discovery.
     */
    var characteristicUuid: String?
        get() = sp.getString(KEY_CHARACTERISTIC, null)
        set(v) = sp.edit().putString(KEY_CHARACTERISTIC, v).apply()

    var labelWidthMm: Int
        get() = sp.getInt(KEY_W, 50)
        set(v) = sp.edit().putInt(KEY_W, v).apply()

    var labelHeightMm: Int
        get() = sp.getInt(KEY_H, 30)
        set(v) = sp.edit().putInt(KEY_H, v).apply()

    /**
     * Width of the print head in mm, which at 8 dots/mm is also the number of
     * bytes every raster line must contain. 72 for an M220, 48 for M110-class
     * hardware. Getting this wrong desynchronises the raster and the printer
     * rejects the job, so it is adjustable rather than assumed.
     */
    var headWidthMm: Int
        get() = sp.getInt(KEY_HEAD, PhomemoM220.DEFAULT_HEAD_WIDTH_BYTES)
        set(v) = sp.edit().putInt(KEY_HEAD, v.coerceIn(16, 104)).apply()

    /** 0x01 (slow) - 0x05 (fast). Slower generally means crisper QR modules. */
    var speed: Int
        get() = sp.getInt(KEY_SPEED, 3)
        set(v) = sp.edit().putInt(KEY_SPEED, v.coerceIn(1, 5)).apply()

    /**
     * 1-8, mapped to an ESC 7 heat time. Bump it if QR codes scan unreliably.
     * The reference default is 6.
     */
    var density: Int
        get() = sp.getInt(KEY_DENSITY, 6).coerceIn(1, 8)
        set(v) = sp.edit().putInt(KEY_DENSITY, v.coerceIn(1, 8)).apply()

    /**
     * Which command set to speak. The M220 is m-series; M110/M120 are m110.
     * Adjustable because a unit advertising under the Q... scheme matches no
     * known name pattern and cannot be assigned one by inspection.
     */
    var protocol: PhomemoM220.Protocol
        get() = if (sp.getString(KEY_PROTOCOL, null) == "m110") {
            PhomemoM220.Protocol.M110
        } else {
            PhomemoM220.Protocol.M_SERIES
        }
        set(v) = sp.edit()
            .putString(KEY_PROTOCOL, if (v == PhomemoM220.Protocol.M110) "m110" else "m-series")
            .apply()

    /**
     * Label sizes the user has entered and kept. Stored as "WxH" strings, in
     * insertion order, because third-party stock rarely matches the vendor list.
     */
    var customSizes: List<LabelSizes.Size>
        get() = sp.getString(KEY_CUSTOM_SIZES, null)
            ?.split(",")
            ?.mapNotNull { LabelSizes.Size.decode(it) }
            .orEmpty()
        set(v) = sp.edit()
            .putString(KEY_CUSTOM_SIZES, v.joinToString(",") { it.encode() })
            .apply()

    /**
     * Dots fed after a label, at 8 per mm. Defaults to 15 mm rather than the
     * reference's 4 mm, which is nowhere near the head-to-tear-bar distance.
     *
     * Unused by the m110 command set and by the feed-to-gap method, which both
     * let the printer decide the distance from the stock itself.
     */
    var feedDots: Int
        get() = sp.getInt(KEY_FEED, PhomemoM220.DEFAULT_FEED_DOTS)
        set(v) = sp.edit().putInt(KEY_FEED, v.coerceIn(0, 240)).apply()

    /**
     * How the label is advanced after printing.
     *
     * Defaults to blank rows rather than the feed command, because ESC J is
     * confirmed inert on this hardware: the write succeeds, the printer reports
     * nothing, and the paper stays put. Blank rows cannot fail that way - the
     * paper has to travel under the head for the printer to print them.
     */
    var feedMode: PhomemoM220.FeedMode
        get() = when (sp.getString(KEY_FEED_MODE, null)) {
            "command" -> PhomemoM220.FeedMode.COMMAND
            "gap" -> PhomemoM220.FeedMode.GAP
            else -> PhomemoM220.FeedMode.BLANK_ROWS
        }
        set(v) = sp.edit().putString(
            KEY_FEED_MODE,
            when (v) {
                PhomemoM220.FeedMode.BLANK_ROWS -> "blank"
                PhomemoM220.FeedMode.GAP -> "gap"
                PhomemoM220.FeedMode.COMMAND -> "command"
            }
        ).apply()

    /**
     * Where the roll sits under the print head. printers.json records the M220
     * as right-aligned, which is the default here; other models center.
     */
    var alignment: PhomemoM220.Alignment
        get() = when (sp.getString(KEY_ALIGNMENT, null)) {
            "left" -> PhomemoM220.Alignment.LEFT
            "center" -> PhomemoM220.Alignment.CENTER
            else -> PhomemoM220.Alignment.RIGHT
        }
        set(v) = sp.edit().putString(
            KEY_ALIGNMENT,
            when (v) {
                PhomemoM220.Alignment.LEFT -> "left"
                PhomemoM220.Alignment.CENTER -> "center"
                PhomemoM220.Alignment.RIGHT -> "right"
            }
        ).apply()

    /** See PhomemoM220.MEDIA_* constants. */
    var mediaType: Int
        get() = sp.getInt(KEY_MEDIA, PhomemoM220.MEDIA_LABEL_WITH_GAPS)
        set(v) = sp.edit().putInt(KEY_MEDIA, v).apply()

    private companion object {
        const val KEY_MAC = "printer_mac"
        const val KEY_NAME = "printer_name"
        const val KEY_CHARACTERISTIC = "characteristic_uuid"
        const val KEY_HEAD = "head_width_mm"
        const val KEY_PROTOCOL = "protocol"
        const val KEY_W = "label_w_mm"
        const val KEY_H = "label_h_mm"
        const val KEY_SPEED = "speed"
        const val KEY_DENSITY = "density"
        const val KEY_MEDIA = "media_type"
        const val KEY_CUSTOM_SIZES = "custom_sizes"
        const val KEY_FEED = "feed_dots"
        const val KEY_FEED_MODE = "feed_mode"
        const val KEY_ALIGNMENT = "head_alignment"
    }
}
