package com.aeonaxi.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Calendar
import kotlin.math.pow
import kotlin.math.sin

// ---------------------------------------------------------------------------
// AeonAxi — Fusion Matter Earth Map
// A digital world map that tracks Fusion Matter consuming Earth across
// October 1–31. It opens LIVE: synced to the real calendar date, animating
// from Oct 1 up to today, and it re-checks the real date every 30 seconds
// while open, so at midnight it rolls to the next day on its own.
// Scale: the full map width at the equator is Earth's circumference,
// 24,901 miles. Earth's surface area derived from that is ~197.2M sq mi,
// which is what the "consumed" square-mileage is calculated from.
// ---------------------------------------------------------------------------

object FusionModel {
    const val EARTH_CIRCUMFERENCE_MI = 24901.0
    // r = C / 2pi = 3,963 mi ; surface = 4 pi r^2 = C^2 / pi = ~197.36M sq mi
    val EARTH_SURFACE_SQMI: Double = EARTH_CIRCUMFERENCE_MI * EARTH_CIRCUMFERENCE_MI / Math.PI

    /** Percent of Earth's surface consumed on [day] (1..31). Oct 1 = 6.2%, Oct 31 = 71.4%. */
    fun percent(day: Int): Double {
        val f = (day.coerceIn(1, 31) - 1) / 30.0
        return 6.2 + 65.2 * f.pow(1.22)
    }

    fun consumedSqMi(day: Int): Double = EARTH_SURFACE_SQMI * percent(day) / 100.0

    /** Real-time day: in October the real day-of-month, clamped outside October. */
    fun realTimeDay(now: Calendar = Calendar.getInstance()): Int {
        val month = now.get(Calendar.MONTH) // 0-based; October = 9
        return when {
            month < Calendar.OCTOBER -> 1
            month > Calendar.OCTOBER -> 31
            else -> now.get(Calendar.DAY_OF_MONTH).coerceIn(1, 31)
        }
    }

    fun todayLabel(now: Calendar = Calendar.getInstance()): String {
        val months = arrayOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )
        return "${months[now.get(Calendar.MONTH)]} ${now.get(Calendar.DAY_OF_MONTH)}, ${now.get(Calendar.YEAR)}"
    }

    val events = arrayOf(
        "Oct 1 — Planet Fusion looms over Earth. First Fusion Matter fall: corrupted zones open in the eastern US.",
        "Oct 2 — Corrupted zones spread across the American Midwest.",
        "Oct 3 — First fusion-matter ocean bloom in the Atlantic.",
        "Oct 4 — A fusion scar tears across North America.",
        "Oct 5 — The first Future Zone locks in around the eastern seaboard.",
        "Oct 6 — Corrupted zones ignite in South America and West Africa.",
        "Oct 7 — Pacific fusion-matter ocean swells outward.",
        "Oct 8 — First district erased: a US city core goes fully lime.",
        "Oct 9 — Fusion scars cut across Africa toward the Cape.",
        "Oct 10 — Europe's first corrupted zones merge into one mass.",
        "Oct 11 — Indian Ocean turns: fusion-matter ocean bloom.",
        "Oct 12 — A second Future Zone forms over central Europe.",
        "Oct 13 — South American scar reaches the southern cone.",
        "Oct 14 — Corrupted zones take hold across South Asia.",
        "Oct 15 — Halfway mark: over a third of Earth is lime.",
        "Oct 16 — London district erased. The Thames runs green.",
        "Oct 17 — Eurasian scar stretches from Europe to Siberia.",
        "Oct 18 — Arctic ocean bloom: the top of the world goes lime.",
        "Oct 19 — East Asia corrupted zones link up along the coast.",
        "Oct 20 — A third Future Zone forms over East Asia.",
        "Oct 21 — Paris district erased. Fusion matter in the Seine.",
        "Oct 22 — Australia's first corrupted zones, inland and east coast.",
        "Oct 23 — Southern Ocean bloom joins the Pacific mass.",
        "Oct 24 — Tokyo district erased. Scars cross the Pacific rim.",
        "Oct 25 — Two thirds of the oceans now carry fusion matter.",
        "Oct 26 — Australian scar runs from Perth to Sydney.",
        "Oct 27 — Cairo and Rio districts erased on the same day.",
        "Oct 28 — Remaining clean land shrinks to pockets and poles.",
        "Oct 29 — Sydney district erased. Oceania nearly fully lime.",
        "Oct 30 — Antarctica's coast blooms green at the edge of the world.",
        "Oct 31 — Day 31: 71.4% of Earth consumed by Fusion Matter."
    )
}

private data class Land(val color: Int, val pts: List<Pair<Double, Double>>)
private data class Blob(val lon: Double, val lat: Double, val startDay: Int, val maxR: Double, val seed: Double)
private data class Scar(val pts: List<Pair<Double, Double>>, val startDay: Int)
private data class City(val name: String, val lon: Double, val lat: Double, val eraseDay: Int)

private fun land(color: Int, vararg flat: Double): Land {
    val pts = ArrayList<Pair<Double, Double>>()
    var i = 0
    while (i + 1 < flat.size) { pts.add(flat[i] to flat[i + 1]); i += 2 }
    return Land(color, pts)
}

private fun pts(vararg flat: Double): List<Pair<Double, Double>> {
    val out = ArrayList<Pair<Double, Double>>()
    var i = 0
    while (i + 1 < flat.size) { out.add(flat[i] to flat[i + 1]); i += 2 }
    return out
}

class FusionMapView(context: android.content.Context) : View(context) {

    var day: Int = 1
        set(value) { field = value.coerceIn(1, 31); invalidate() }

    companion object {
        val LIME: Int = Color.rgb(61, 255, 26)       // fusion matter lime green
        val LIME_DARK: Int = Color.rgb(24, 160, 8)
        val OCEAN: Int = Color.rgb(20, 61, 94)       // normal ocean blue
    }

    private val oceanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OCEAN; style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(46, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val landEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 10, 30, 12); style = Paint.Style.STROKE; strokeWidth = 1.6f
    }
    private val limePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LIME; style = Paint.Style.FILL }
    private val limeSoftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 61, 255, 26); style = Paint.Style.FILL
    }
    private val futurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 61, 255, 26); style = Paint.Style.FILL
    }
    private val futureEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LIME; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val scarGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 61, 255, 26); style = Paint.Style.STROKE
        strokeWidth = 11f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val scarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LIME; style = Paint.Style.STROKE
        strokeWidth = 4.5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val cityDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 235, 160); style = Paint.Style.FILL
    }
    private val erasedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LIME; style = Paint.Style.FILL }
    private val erasedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LIME; style = Paint.Style.STROKE; strokeWidth = 2.4f
    }
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(6, 19, 10); style = Paint.Style.STROKE; strokeWidth = 2.6f; strokeCap = Paint.Cap.ROUND
    }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.4f
    }
    private val scaleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val scaleAltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(6, 19, 10); style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 26f; isFakeBoldText = true
    }
    private val bgPaint = Paint().apply { color = Color.rgb(6, 19, 10) }

    // --- Normal-colour landmasses (recognisable simplified outlines) -------
    private val lands = listOf(
        // North America — natural green
        land(Color.rgb(91, 140, 74),
            -168.0, 66.0, -140.0, 70.0, -125.0, 71.0, -110.0, 68.0, -95.0, 69.0, -85.0, 66.0,
            -80.0, 73.0, -76.0, 62.0, -81.0, 58.0, -65.0, 50.0, -56.0, 47.0, -65.0, 44.0,
            -70.0, 42.0, -74.0, 39.0, -76.0, 35.0, -80.0, 32.0, -81.0, 26.0, -84.0, 30.0,
            -89.0, 29.0, -94.0, 29.0, -97.0, 26.0, -97.0, 22.0, -94.0, 18.0, -87.0, 21.0,
            -90.0, 14.0, -83.0, 9.0, -79.0, 9.0, -83.0, 15.0, -92.0, 15.0, -97.0, 16.0,
            -105.0, 20.0, -110.0, 23.0, -114.0, 29.0, -117.0, 33.0, -122.0, 37.0, -124.0, 46.0,
            -130.0, 55.0, -150.0, 60.0, -165.0, 60.0),
        // Greenland — icy
        land(Color.rgb(225, 238, 240),
            -45.0, 60.0, -30.0, 68.0, -22.0, 70.0, -20.0, 76.0, -30.0, 82.0, -50.0, 80.0,
            -58.0, 76.0, -55.0, 70.0, -53.0, 64.0),
        // South America — rainforest green
        land(Color.rgb(62, 122, 58),
            -79.0, 9.0, -77.0, 4.0, -80.0, -3.0, -81.0, -6.0, -77.0, -12.0, -70.0, -18.0,
            -70.0, -23.0, -70.0, -30.0, -72.0, -38.0, -73.0, -46.0, -74.0, -52.0, -68.0, -55.0,
            -65.0, -45.0, -62.0, -39.0, -58.0, -34.0, -48.0, -28.0, -41.0, -23.0, -39.0, -15.0,
            -35.0, -8.0, -44.0, -2.0, -50.0, 0.0, -53.0, 4.0, -60.0, 8.0, -68.0, 11.0, -75.0, 10.0),
        // Africa — savanna tan
        land(Color.rgb(194, 161, 92),
            -17.0, 15.0, -10.0, 29.0, -6.0, 35.0, 10.0, 37.0, 20.0, 32.0, 30.0, 31.0,
            34.0, 28.0, 38.0, 22.0, 43.0, 12.0, 51.0, 12.0, 48.0, 5.0, 41.0, -2.0,
            40.0, -10.0, 36.0, -18.0, 33.0, -26.0, 20.0, -35.0, 17.0, -32.0, 14.0, -22.0,
            12.0, -15.0, 9.0, -5.0, 9.0, 4.0, -8.0, 5.0, -13.0, 8.0, -17.0, 12.0),
        // Madagascar
        land(Color.rgb(150, 140, 80),
            43.0, -12.0, 47.0, -14.0, 50.0, -16.0, 47.0, -24.0, 45.0, -25.0, 43.0, -22.0, 43.0, -17.0),
        // Europe — green
        land(Color.rgb(93, 145, 80),
            -10.0, 36.0, -9.0, 37.0, -9.0, 44.0, -2.0, 49.0, 2.0, 51.0, 7.0, 54.0,
            8.0, 57.0, 18.0, 59.0, 21.0, 60.0, 25.0, 66.0, 18.0, 69.0, 31.0, 70.0,
            30.0, 62.0, 28.0, 59.0, 24.0, 58.0, 20.0, 54.0, 14.0, 54.0, 18.0, 46.0,
            28.0, 41.0, 23.0, 36.0, 19.0, 40.0, 15.0, 38.0, 12.0, 44.0, 7.0, 44.0,
            3.0, 42.0, 0.0, 39.0, -2.0, 37.0),
        // UK
        land(Color.rgb(93, 145, 80),
            -5.0, 50.0, -3.0, 53.0, -5.0, 55.0, -3.0, 58.0, -1.0, 57.0, 0.0, 53.0, 1.0, 51.0),
        // Iceland
        land(Color.rgb(200, 220, 215), -22.0, 64.0, -14.0, 64.0, -14.0, 66.5, -22.0, 66.5),
        // Asia — green/tan mix
        land(Color.rgb(106, 143, 90),
            30.0, 70.0, 50.0, 68.0, 70.0, 72.0, 90.0, 75.0, 110.0, 74.0, 130.0, 72.0,
            150.0, 70.0, 170.0, 66.0, 178.0, 65.0, 170.0, 60.0, 162.0, 58.0, 156.0, 51.0,
            142.0, 50.0, 140.0, 40.0, 129.0, 33.0, 122.0, 31.0, 120.0, 25.0, 110.0, 20.0,
            108.0, 12.0, 105.0, 9.0, 103.0, 5.0, 100.0, 8.0, 98.0, 15.0, 92.0, 16.0,
            88.0, 22.0, 86.0, 20.0, 80.0, 15.0, 77.0, 8.0, 72.0, 20.0, 66.0, 25.0,
            57.0, 26.0, 48.0, 30.0, 50.0, 26.0, 55.0, 24.0, 58.0, 22.0, 55.0, 17.0,
            43.0, 12.0, 39.0, 20.0, 35.0, 28.0, 36.0, 36.0, 30.0, 41.0, 28.0, 46.0,
            30.0, 55.0, 32.0, 62.0),
        // Indonesia / SE Asia islands (blobs read fine at map scale)
        land(Color.rgb(62, 122, 58), 95.0, 5.0, 102.0, -2.0, 106.0, -6.0, 100.0, -4.0, 96.0, 2.0),
        land(Color.rgb(62, 122, 58), 109.0, 1.0, 117.0, 3.0, 119.0, -2.0, 110.0, -3.0),
        land(Color.rgb(62, 122, 58), 131.0, -1.0, 141.0, -3.0, 146.0, -6.0, 136.0, -4.0, 130.0, -3.0),
        // Japan
        land(Color.rgb(106, 143, 90),
            130.0, 31.0, 133.0, 34.0, 137.0, 35.0, 140.0, 36.0, 141.0, 40.0, 142.0, 44.0,
            140.0, 44.0, 139.0, 38.0, 135.0, 34.0, 131.0, 33.0),
        // Australia — outback tan
        land(Color.rgb(193, 154, 91),
            113.0, -22.0, 114.0, -28.0, 115.0, -34.0, 118.0, -35.0, 124.0, -33.0, 129.0, -32.0,
            132.0, -32.0, 135.0, -35.0, 138.0, -35.0, 140.0, -38.0, 145.0, -38.0, 150.0, -37.0,
            153.0, -30.0, 153.0, -25.0, 151.0, -24.0, 146.0, -19.0, 142.0, -11.0, 142.0, -15.0,
            136.0, -15.0, 135.0, -12.0, 132.0, -11.0, 126.0, -14.0, 122.0, -17.0, 117.0, -20.0),
        // New Zealand
        land(Color.rgb(91, 140, 74), 167.0, -45.0, 172.0, -41.0, 174.0, -39.0, 176.0, -38.0, 173.0, -41.0, 169.0, -44.0),
        // Antarctica — white strip
        land(Color.rgb(232, 240, 242),
            -180.0, -70.0, -120.0, -73.0, -60.0, -68.0, 0.0, -70.0, 60.0, -68.0, 120.0, -72.0, 180.0, -70.0,
            180.0, -90.0, -180.0, -90.0)
    )

    // --- Fusion Matter layers (ALL lime green, per spec) --------------------
    private val oceanBlobs = listOf(
        Blob(-30.0, 25.0, 3, 34.0, 1.0),    // Atlantic
        Blob(-150.0, 5.0, 7, 42.0, 2.0),   // Pacific west
        Blob(150.0, -5.0, 9, 38.0, 3.0),    // Pacific east / Oceania
        Blob(80.0, -15.0, 11, 34.0, 4.0),   // Indian
        Blob(0.0, 78.0, 18, 40.0, 5.0),     // Arctic
        Blob(-40.0, -45.0, 23, 40.0, 6.0),  // Southern Ocean
        Blob(-90.0, -25.0, 25, 34.0, 7.0)   // South Pacific
    )
    private val corruptedZones = listOf(
        Blob(-95.0, 42.0, 1, 20.0, 11.0), Blob(-110.0, 35.0, 2, 15.0, 12.0),
        Blob(-60.0, -15.0, 6, 19.0, 13.0), Blob(-45.0, -30.0, 8, 14.0, 14.0),
        Blob(5.0, 10.0, 6, 18.0, 15.0), Blob(22.0, -12.0, 9, 17.0, 16.0),
        Blob(28.0, -28.0, 12, 13.0, 17.0),
        Blob(15.0, 50.0, 10, 16.0, 18.0), Blob(45.0, 55.0, 14, 20.0, 19.0),
        Blob(85.0, 28.0, 14, 18.0, 20.0), Blob(110.0, 35.0, 17, 18.0, 21.0),
        Blob(135.0, 48.0, 20, 17.0, 22.0),
        Blob(134.0, -25.0, 22, 17.0, 23.0), Blob(148.0, -32.0, 24, 12.0, 24.0),
        Blob(-125.0, 52.0, 16, 14.0, 25.0), Blob(65.0, 68.0, 22, 22.0, 26.0),
        Blob(-35.0, 74.0, 24, 16.0, 27.0), Blob(0.0, -78.0, 30, 30.0, 28.0)
    )
    private val futureZones = listOf(
        Blob(-77.0, 38.0, 5, 19.0, 31.0),   // The Future — eastern US
        Blob(15.0, 50.0, 12, 17.0, 32.0),   // central Europe
        Blob(120.0, 32.0, 20, 18.0, 33.0)   // East Asia
    )
    private val scars = listOf(
        Scar(pts(-122.0, 50.0, -108.0, 44.0, -94.0, 43.0, -82.0, 39.0, -74.0, 38.0), 4),
        Scar(pts(8.0, 6.0, 16.0, -6.0, 22.0, -18.0, 28.0, -30.0), 9),
        Scar(pts(-72.0, -8.0, -64.0, -20.0, -66.0, -34.0, -68.0, -48.0), 13),
        Scar(pts(-5.0, 48.0, 18.0, 50.0, 45.0, 55.0, 80.0, 58.0, 115.0, 52.0), 17),
        Scar(pts(88.0, 24.0, 102.0, 28.0, 116.0, 33.0, 128.0, 38.0), 24),
        Scar(pts(116.0, -24.0, 128.0, -27.0, 140.0, -32.0, 150.0, -35.0), 26)
    )
    private val cities = listOf(
        City("New York", -74.0, 40.7, 8), City("Los Angeles", -118.2, 34.0, 15),
        City("London", -0.1, 51.5, 16), City("Paris", 2.35, 48.9, 21),
        City("Tokyo", 139.7, 35.7, 24), City("Cairo", 31.2, 30.0, 27),
        City("Rio", -43.2, -22.9, 27), City("Sydney", 151.2, -33.9, 29),
        City("Chicago", -87.6, 41.8, 12), City("Moscow", 37.6, 55.8, 19),
        City("Mumbai", 72.9, 19.1, 22), City("Cape Town", 18.4, -33.9, 26),
        City("Toronto", -79.4, 43.7, 18), City("Jakarta", 106.8, -6.2, 28)
    )

    private fun x(lon: Double, w: Int): Float = ((lon + 180.0) / 360.0 * w).toFloat()
    private fun y(lat: Double, h: Int): Float = ((90.0 - lat) / 180.0 * h).toFloat()

    private fun growth(b: Blob): Double {
        if (day < b.startDay) return 0.0
        val span = (31 - b.startDay).coerceAtLeast(1)
        val f = ((day - b.startDay + 1).toDouble() / (span + 1)).coerceIn(0.0, 1.0)
        return 0.22 + 0.78 * f
    }

    private fun blobPath(cx: Float, cy: Float, rx: Float, ry: Float, seed: Double): Path {
        val path = Path()
        val n = 14
        for (i in 0..n) {
            val a = 2.0 * Math.PI * i / n
            val wobble = 0.74 + 0.26 * sin(seed * 12.99 + i * 2.399)
            val px = cx + (rx * wobble * kotlin.math.cos(a)).toFloat()
            val py = cy + (ry * wobble * kotlin.math.sin(a)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        return path
    }

    private fun drawBlob(canvas: Canvas, b: Blob, w: Int, h: Int, paint: Paint) {
        val g = growth(b)
        if (g <= 0.0) return
        val rx = (b.maxR * g / 360.0 * w).toFloat()
        val ry = (b.maxR * g / 180.0 * h).toFloat()
        canvas.drawPath(blobPath(x(b.lon, w), y(b.lat, h), rx, ry, b.seed), paint)
        // satellite splatter
        canvas.drawPath(
            blobPath(x(b.lon + b.maxR * 0.55, w), y(b.lat + b.maxR * 0.3, h), rx * 0.4f, ry * 0.4f, b.seed + 7.0),
            paint
        )
        canvas.drawPath(
            blobPath(x(b.lon - b.maxR * 0.5, w), y(b.lat - b.maxR * 0.35, h), rx * 0.32f, ry * 0.32f, b.seed + 13.0),
            paint
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        // 1. Normal ocean
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), oceanPaint)

        // 2. Fusion-matter oceans (lime green) under the land
        for (b in oceanBlobs) drawBlob(canvas, b, w, h, limePaint)

        // graticule
        var lon = -150.0
        while (lon <= 150.0) { canvas.drawLine(x(lon, w), 0f, x(lon, w), h.toFloat(), gridPaint); lon += 30.0 }
        var lat = -60.0
        while (lat <= 60.0) { canvas.drawLine(0f, y(lat, h), w.toFloat(), y(lat, h), gridPaint); lat += 30.0 }

        // 3. Normal land in normal colours
        for (landmass in lands) {
            val path = Path()
            landmass.pts.forEachIndexed { i, (lo, la) ->
                if (i == 0) path.moveTo(x(lo, w), y(la, h)) else path.lineTo(x(lo, w), y(la, h))
            }
            path.close()
            landPaint.color = landmass.color
            canvas.drawPath(path, landPaint)
            canvas.drawPath(path, landEdgePaint)
        }

        // 4. Future zones (lime, ringed)
        for (b in futureZones) {
            val g = growth(b)
            if (g <= 0.0) continue
            val rx = (b.maxR * g / 360.0 * w).toFloat()
            val ry = (b.maxR * g / 180.0 * h).toFloat()
            val p = blobPath(x(b.lon, w), y(b.lat, h), rx, ry, b.seed)
            canvas.drawPath(p, futurePaint)
            canvas.drawPath(p, futureEdgePaint)
        }

        // 5. Corrupted zones (lime)
        for (b in corruptedZones) drawBlob(canvas, b, w, h, limePaint)
        // soft outer glow pass for big corrupted masses
        for (b in corruptedZones) {
            if (day >= b.startDay + 6) drawBlob(canvas, b, w, h, limeSoftPaint)
        }

        // 6. Fusion-matter scars (lime cracks that lengthen daily)
        for (scar in scars) {
            if (day < scar.startDay) continue
            val span = (31 - scar.startDay).coerceAtLeast(1)
            val f = ((day - scar.startDay + 1).toDouble() / (span + 1)).coerceIn(0.0, 1.0)
            val segs = scar.pts.size - 1
            val upto = (f * segs).coerceAtLeast(0.35)
            val path = Path()
            path.moveTo(x(scar.pts[0].first, w), y(scar.pts[0].second, h))
            var i = 1
            while (i <= upto.toInt() && i < scar.pts.size) {
                path.lineTo(x(scar.pts[i].first, w), y(scar.pts[i].second, h)); i++
            }
            if (i < scar.pts.size) {
                val frac = upto - upto.toInt()
                if (frac > 0.0) {
                    val (lo1, la1) = scar.pts[i - 1]
                    val (lo2, la2) = scar.pts[i]
                    path.lineTo(
                        x(lo1 + (lo2 - lo1) * frac, w),
                        y(la1 + (la2 - la1) * frac, h)
                    )
                }
            }
            canvas.drawPath(path, scarGlowPaint)
            canvas.drawPath(path, scarPaint)
        }

        // 7. Districts: dots until erased, then lime disc with an X
        val r = (w / 90f).coerceIn(7f, 13f)
        for (city in cities) {
            val cx = x(city.lon, w)
            val cy = y(city.lat, h)
            if (day >= city.eraseDay) {
                canvas.drawCircle(cx, cy, r, erasedPaint)
                canvas.drawCircle(cx, cy, r + 3.5f, erasedRingPaint)
                canvas.drawLine(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.55f, cy + r * 0.55f, xPaint)
                canvas.drawLine(cx + r * 0.55f, cy - r * 0.55f, cx - r * 0.55f, cy + r * 0.55f, xPaint)
            } else {
                canvas.drawCircle(cx, cy, 3.2f, cityDotPaint)
            }
        }

        drawScaleBar(canvas, w, h)
    }

    /** Scale bar from the real scale: full map width at equator = 24,901 miles. */
    private fun drawScaleBar(canvas: Canvas, w: Int, h: Int) {
        val segMiles = 2500.0
        val segPx = (segMiles / FusionModel.EARTH_CIRCUMFERENCE_MI * w).toFloat()
        val baseY = h - 34f
        val left = 18f
        val barH = 9f
        // two segments: 0–2,500 (filled) and 2,500–5,000 (hollow), total 5,000 mi
        scaleFillPaint.color = Color.WHITE
        canvas.drawRect(left, baseY, left + segPx, baseY + barH, scaleFillPaint)
        canvas.drawRect(left, baseY, left + segPx * 2, baseY + barH, scalePaint)
        canvas.drawLine(left + segPx, baseY, left + segPx, baseY + barH, scalePaint)
        labelPaint.textSize = (w / 34f).coerceIn(11f, 15f)
        canvas.drawText("0", left - 2f, baseY - 6f, labelPaint)
        val mid = "2,500"
        canvas.drawText(mid, left + segPx - labelPaint.measureText(mid) / 2f, baseY - 6f, labelPaint)
        val end = "5,000 miles"
        canvas.drawText(end, left + segPx * 2 - labelPaint.measureText(end) / 2f, baseY - 6f, labelPaint)
    }
}

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var day = 1
    private var playing = false
    private var live = true
    private var liveTarget = 1

    private lateinit var mapView: FusionMapView
    private lateinit var dateText: TextView
    private lateinit var statText: TextView
    private lateinit var areaText: TextView
    private lateinit var eventText: TextView
    private lateinit var liveText: TextView
    private lateinit var playButton: Button
    private lateinit var liveButton: Button
    private lateinit var seek: SeekBar

    private val playTick = object : Runnable {
        override fun run() {
            if (!playing) return
            if (day >= 31) {
                playing = false
                playButton.text = "▶ Replay 31 days"
                return
            }
            setDay(day + 1, fromUser = false)
            handler.postDelayed(this, 650)
        }
    }

    /** Opening animation: sweep Oct 1 -> today's real day, then stay LIVE. */
    private val introTick = object : Runnable {
        override fun run() {
            if (day < liveTarget) {
                setDay(day + 1, fromUser = false)
                handler.postDelayed(this, 170)
            }
        }
    }

    /** Real-time watcher: every 30s, if LIVE and the real date rolled over, follow it. */
    private val liveWatch = object : Runnable {
        override fun run() {
            if (live) {
                val real = FusionModel.realTimeDay()
                liveText.text = liveLabel()
                if (real != day) {
                    liveTarget = real
                    setDay(real, fromUser = false)
                }
            }
            handler.postDelayed(this, 30_000)
        }
    }

    private fun liveLabel(): String =
        if (live) "● LIVE — real time: ${FusionModel.todayLabel()} · auto-updates daily"
        else "○ LIVE off — scrub or press GO LIVE"

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val lime = FusionMapView.LIME
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(6, 19, 10))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val title = TextView(this).apply {
            text = "AEONAXI — Fusion Matter Earth Map"
            textSize = 19f
            setTextColor(lime)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(title)

        val tagline = TextView(this).apply {
            text = "FusionFall — Planet Fusion's Fusion Matter takeover of Earth"
            textSize = 12.5f
            setTextColor(Color.rgb(140, 220, 140))
        }
        root.addView(tagline)

        liveText = TextView(this).apply { textSize = 12.5f; setTextColor(Color.rgb(170, 255, 170)) }
        root.addView(liveText)

        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dateText = TextView(this).apply {
            textSize = 24f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD)
        }
        statText = TextView(this).apply {
            textSize = 15f; setTextColor(lime); setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.END
        }
        headerRow.addView(dateText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(statText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(headerRow)

        areaText = TextView(this).apply { textSize = 12.5f; setTextColor(Color.rgb(200, 230, 200)) }
        root.addView(areaText)

        mapView = FusionMapView(this)
        root.addView(
            mapView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        eventText = TextView(this).apply {
            textSize = 13.5f; setTextColor(Color.rgb(230, 255, 230))
            setPadding(0, dp(6), 0, dp(2))
        }
        root.addView(eventText)

        seek = SeekBar(this).apply {
            max = 30
            progressTintList = ColorStateList.valueOf(lime)
            thumbTintList = ColorStateList.valueOf(lime)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        stopPlay()
                        live = false
                        setDay(progress + 1, fromUser = true)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(seek)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun ctrlBtn(label: String): Button = Button(this).apply {
            text = label; textSize = 14f
            setTextColor(Color.rgb(6, 19, 10))
            backgroundTintList = ColorStateList.valueOf(lime)
        }
        val prevBtn = ctrlBtn("◀ Day")
        playButton = ctrlBtn("▶ Play 31 days")
        val nextBtn = ctrlBtn("Day ▶")
        liveButton = ctrlBtn("● GO LIVE")
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(3), dp(4), dp(3), dp(4))
        }
        controls.addView(prevBtn, lp)
        controls.addView(playButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f).apply {
            setMargins(dp(3), dp(4), dp(3), dp(4))
        })
        controls.addView(nextBtn, lp)
        controls.addView(liveButton, lp)
        root.addView(controls)

        prevBtn.setOnClickListener { stopPlay(); live = false; setDay(day - 1, fromUser = true) }
        nextBtn.setOnClickListener { stopPlay(); live = false; setDay(day + 1, fromUser = true) }
        playButton.setOnClickListener {
            if (playing) { stopPlay(); return@setOnClickListener }
            live = false
            if (day >= 31) setDay(1, fromUser = true)
            playing = true
            playButton.text = "⏸ Pause"
            handler.postDelayed(playTick, 650)
        }
        liveButton.setOnClickListener { goLive(animate = true) }

        root.addView(legend(lime))

        setContentView(root)

        // Open LIVE on the real date: sweep from Oct 1 up to today automatically.
        goLive(animate = true)
        handler.postDelayed(liveWatch, 30_000)
    }

    private fun legend(lime: Int): View {
        fun item(color: Int, label: String): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(2), dp(4), dp(2))
            }
            val swatch = View(this).apply { setBackgroundColor(color) }
            row.addView(swatch, LinearLayout.LayoutParams(dp(14), dp(14)))
            val tv = TextView(this).apply {
                text = " $label"; textSize = 11.5f; setTextColor(Color.rgb(220, 240, 220))
            }
            row.addView(tv)
            return row
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r1.addView(item(Color.rgb(91, 140, 74), "Normal land"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r1.addView(item(Color.rgb(20, 61, 94), "Normal ocean"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r1.addView(item(lime, "Corrupted zones"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r2.addView(item(lime, "Fusion-matter oceans"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r2.addView(item(lime, "Fusion-matter scars"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r2.addView(item(lime, "Future zones"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val r3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r3.addView(item(lime, "Erased districts ✕"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r3.addView(item(Color.rgb(225, 238, 240), "Ice / Antarctica"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r3.addView(item(Color.rgb(194, 161, 92), "Desert land"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(r1); col.addView(r2); col.addView(r3)
        return col
    }

    private fun goLive(animate: Boolean) {
        stopPlay()
        live = true
        liveTarget = FusionModel.realTimeDay()
        liveText.text = liveLabel()
        if (animate && liveTarget > 1) {
            setDay(1, fromUser = false)
            handler.removeCallbacks(introTick)
            handler.postDelayed(introTick, 350)
        } else {
            setDay(liveTarget, fromUser = false)
        }
    }

    private fun stopPlay() {
        playing = false
        handler.removeCallbacks(playTick)
        if (::playButton.isInitialized) playButton.text = "▶ Play 31 days"
    }

    private fun setDay(newDay: Int, fromUser: Boolean) {
        day = newDay.coerceIn(1, 31)
        mapView.day = day
        if (seek.progress != day - 1) seek.progress = day - 1
        dateText.text = "October $day"
        val pct = FusionModel.percent(day)
        statText.text = String.format("%.1f%% consumed", pct)
        val sq = FusionModel.consumedSqMi(day)
        areaText.text = String.format(
            "Day %d of 31 · Earth: 24,901 mi around · ~%,.0f of ~197.4M sq mi consumed",
            day, sq
        )
        eventText.text = FusionModel.events[day - 1]
        if (!live) liveText.text = liveLabel()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
