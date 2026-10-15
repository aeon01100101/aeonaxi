package com.aeonaxi.app

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

// ---------------------------------------------------------------------------
// AeonAxi — FusionFall Fusion Matter Earth Map
// Detailed world map: 180 real country borders (Natural Earth via
// world.geo.json), all 3,221 US counties ( Census FIPS GeoJSON), and 150+
// cities. Pinch / buttons to zoom, drag to pan, tap to identify a place.
// The Fusion Matter layer tracks Planet Fusion's takeover across
// October 1–31, LIVE-synced to the real calendar date.
// Scale: full map width at the equator = Earth's circumference, 24,901 mi.
// ---------------------------------------------------------------------------

object FusionModel {
    const val EARTH_CIRCUMFERENCE_MI = 24901.0
    val EARTH_SURFACE_SQMI: Double = EARTH_CIRCUMFERENCE_MI * EARTH_CIRCUMFERENCE_MI / Math.PI

    fun percent(day: Int): Double {
        val f = (day.coerceIn(1, 31) - 1) / 30.0
        return 6.2 + 65.2 * f.pow(1.22)
    }

    fun consumedSqMi(day: Int): Double = EARTH_SURFACE_SQMI * percent(day) / 100.0

    fun realTimeDay(now: Calendar = Calendar.getInstance()): Int {
        val month = now.get(Calendar.MONTH)
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

// --- Geo data ---------------------------------------------------------------
class GeoFeature(
    val name: String,
    val rings: List<FloatArray>, // interleaved lon,lat per ring
    val minLon: Double, val maxLon: Double, val minLat: Double, val maxLat: Double,
    val cenLon: Double, val cenLat: Double,
    val weight: Double, // census area for counties, bbox area for countries
    val stateFips: String
)

private fun parseGeoJson(text: String, counties: Boolean): List<GeoFeature> {
    val root = JSONObject(text)
    val features = root.getJSONArray("features")
    val out = ArrayList<GeoFeature>(features.length())
    for (i in 0 until features.length()) {
        val f = features.getJSONObject(i)
        val geom = f.optJSONObject("geometry") ?: continue
        val props = f.getJSONObject("properties")
        val name: String
        val weight: Double
        var fips = ""
        if (counties) {
            val lsad = props.optString("LSAD", "County")
            name = props.getString("NAME") + " " + lsad
            weight = props.optDouble("CENSUSAREA", 1.0)
            fips = props.optString("STATE", "")
        } else {
            name = props.getString("name")
            weight = 0.0
        }
        val rings = ArrayList<FloatArray>()
        val type = geom.getString("type")
        val coords = geom.getJSONArray("coordinates")
        fun addRing(ringArr: org.json.JSONArray) {
            val arr = FloatArray(ringArr.length() * 2)
            for (j in 0 until ringArr.length()) {
                val p = ringArr.getJSONArray(j)
                arr[j * 2] = p.getDouble(0).toFloat()
                arr[j * 2 + 1] = p.getDouble(1).toFloat()
            }
            rings.add(arr)
        }
        when (type) {
            "Polygon" -> for (r in 0 until coords.length()) addRing(coords.getJSONArray(r))
            "MultiPolygon" -> for (p in 0 until coords.length()) {
                val poly = coords.getJSONArray(p)
                for (r in 0 until poly.length()) addRing(poly.getJSONArray(r))
            }
            else -> continue
        }
        if (rings.isEmpty()) continue
        var minLo = 180.0; var maxLo = -180.0; var minLa = 90.0; var maxLa = -90.0
        var largest: FloatArray = rings[0]
        for (ring in rings) {
            if (ring.size > largest.size) largest = ring
            var j = 0
            while (j + 1 < ring.size) {
                val lo = ring[j].toDouble(); val la = ring[j + 1].toDouble()
                if (lo < minLo) minLo = lo; if (lo > maxLo) maxLo = lo
                if (la < minLa) minLa = la; if (la > maxLa) maxLa = la
                j += 2
            }
        }
        var sLo = 0.0; var sLa = 0.0; var n = 0
        var j = 0
        while (j + 1 < largest.size) { sLo += largest[j]; sLa += largest[j + 1]; n++; j += 2 }
        val wgt = if (counties) weight else (maxLo - minLo) * (maxLa - minLa)
        out.add(
            GeoFeature(
                name, rings, minLo, maxLo, minLa, maxLa,
                if (n > 0) sLo / n else (minLo + maxLo) / 2,
                if (n > 0) sLa / n else (minLa + maxLa) / 2,
                wgt, fips
            )
        )
    }
    return out
}

val STATE_NAMES = mapOf(
    "01" to "Alabama", "02" to "Alaska", "04" to "Arizona", "05" to "Arkansas",
    "06" to "California", "08" to "Colorado", "09" to "Connecticut", "10" to "Delaware",
    "11" to "District of Columbia", "12" to "Florida", "13" to "Georgia", "15" to "Hawaii",
    "16" to "Idaho", "17" to "Illinois", "18" to "Indiana", "19" to "Iowa",
    "20" to "Kansas", "21" to "Kentucky", "22" to "Louisiana", "23" to "Maine",
    "24" to "Maryland", "25" to "Massachusetts", "26" to "Michigan", "27" to "Minnesota",
    "28" to "Mississippi", "29" to "Missouri", "30" to "Montana", "31" to "Nebraska",
    "32" to "Nevada", "33" to "New Hampshire", "34" to "New Jersey", "35" to "New Mexico",
    "36" to "New York", "37" to "North Carolina", "38" to "North Dakota", "39" to "Ohio",
    "40" to "Oklahoma", "41" to "Oregon", "42" to "Pennsylvania", "44" to "Rhode Island",
    "45" to "South Carolina", "46" to "South Dakota", "47" to "Tennessee", "48" to "Texas",
    "49" to "Utah", "50" to "Vermont", "51" to "Virginia", "53" to "Washington",
    "54" to "West Virginia", "55" to "Wisconsin", "56" to "Wyoming", "72" to "Puerto Rico"
)

data class CityPin(val name: String, val country: String, val lon: Double, val lat: Double, val tier: Int)

val CITIES = listOf(
    // North America
    CityPin("New York", "United States", -74.0, 40.7, 1), CityPin("Los Angeles", "United States", -118.2, 34.1, 1),
    CityPin("Chicago", "United States", -87.6, 41.8, 1), CityPin("Houston", "United States", -95.4, 29.8, 1),
    CityPin("Phoenix", "United States", -112.1, 33.4, 2), CityPin("Philadelphia", "United States", -75.2, 39.9, 2),
    CityPin("San Antonio", "United States", -98.5, 29.4, 2), CityPin("San Diego", "United States", -117.2, 32.7, 2),
    CityPin("Dallas", "United States", -96.8, 32.8, 1), CityPin("Austin", "United States", -97.7, 30.3, 2),
    CityPin("Seattle", "United States", -122.3, 47.6, 1), CityPin("Denver", "United States", -105.0, 39.7, 1),
    CityPin("Miami", "United States", -80.2, 25.8, 1), CityPin("Atlanta", "United States", -84.4, 33.7, 1),
    CityPin("Boston", "United States", -71.1, 42.4, 1), CityPin("San Francisco", "United States", -122.4, 37.8, 1),
    CityPin("Las Vegas", "United States", -115.1, 36.2, 2), CityPin("Portland", "United States", -122.7, 45.5, 2),
    CityPin("Kansas City", "United States", -94.6, 39.1, 2), CityPin("Salt Lake City", "United States", -111.9, 40.8, 2),
    CityPin("New Orleans", "United States", -90.1, 30.0, 2), CityPin("Nashville", "United States", -86.8, 36.2, 2),
    CityPin("Oklahoma City", "United States", -97.5, 35.5, 2), CityPin("Albuquerque", "United States", -106.7, 35.1, 2),
    CityPin("Honolulu", "United States", -157.8, 21.3, 2), CityPin("Anchorage", "United States", -149.9, 61.2, 2),
    CityPin("Toronto", "Canada", -79.4, 43.7, 1), CityPin("Vancouver", "Canada", -123.1, 49.3, 1),
    CityPin("Montreal", "Canada", -73.6, 45.5, 1), CityPin("Calgary", "Canada", -114.1, 51.0, 2),
    CityPin("Ottawa", "Canada", -75.7, 45.4, 2), CityPin("Winnipeg", "Canada", -97.1, 49.9, 2),
    CityPin("Mexico City", "Mexico", -99.1, 19.4, 1), CityPin("Guadalajara", "Mexico", -103.3, 20.7, 2),
    CityPin("Monterrey", "Mexico", -100.3, 25.7, 2), CityPin("Tijuana", "Mexico", -117.0, 32.5, 2),
    CityPin("Havana", "Cuba", -82.4, 23.1, 2), CityPin("Panama City", "Panama", -79.5, 9.0, 2),
    CityPin("Guatemala City", "Guatemala", -90.5, 14.6, 2),
    // South America
    CityPin("São Paulo", "Brazil", -46.6, -23.6, 1), CityPin("Rio de Janeiro", "Brazil", -43.2, -22.9, 1),
    CityPin("Brasília", "Brazil", -47.9, -15.8, 2), CityPin("Salvador", "Brazil", -38.5, -13.0, 2),
    CityPin("Manaus", "Brazil", -60.0, -3.1, 2), CityPin("Buenos Aires", "Argentina", -58.4, -34.6, 1),
    CityPin("Santiago", "Chile", -70.7, -33.4, 1), CityPin("Lima", "Peru", -77.0, -12.0, 1),
    CityPin("Bogotá", "Colombia", -74.1, 4.7, 1), CityPin("Medellín", "Colombia", -75.6, 6.2, 2),
    CityPin("Caracas", "Venezuela", -66.9, 10.5, 2), CityPin("Quito", "Ecuador", -78.5, -0.2, 2),
    CityPin("La Paz", "Bolivia", -68.2, -16.5, 2), CityPin("Montevideo", "Uruguay", -56.2, -34.9, 2),
    // Europe
    CityPin("London", "United Kingdom", -0.1, 51.5, 1), CityPin("Paris", "France", 2.35, 48.9, 1),
    CityPin("Berlin", "Germany", 13.4, 52.5, 1), CityPin("Madrid", "Spain", -3.7, 40.4, 1),
    CityPin("Rome", "Italy", 12.5, 41.9, 1), CityPin("Amsterdam", "Netherlands", 4.9, 52.4, 2),
    CityPin("Brussels", "Belgium", 4.35, 50.8, 2), CityPin("Vienna", "Austria", 16.4, 48.2, 2),
    CityPin("Warsaw", "Poland", 21.0, 52.2, 1), CityPin("Prague", "Czechia", 14.4, 50.1, 2),
    CityPin("Budapest", "Hungary", 19.0, 47.5, 2), CityPin("Stockholm", "Sweden", 18.1, 59.3, 1),
    CityPin("Oslo", "Norway", 10.75, 59.9, 2), CityPin("Helsinki", "Finland", 24.9, 60.2, 2),
    CityPin("Copenhagen", "Denmark", 12.6, 55.7, 2), CityPin("Dublin", "Ireland", -6.3, 53.3, 2),
    CityPin("Lisbon", "Portugal", -9.1, 38.7, 2), CityPin("Athens", "Greece", 23.7, 38.0, 2),
    CityPin("Reykjavík", "Iceland", -21.9, 64.1, 2),
    // Eurasia / Middle East / Asia
    CityPin("Istanbul", "Turkey", 29.0, 41.0, 1), CityPin("Moscow", "Russia", 37.6, 55.8, 1),
    CityPin("Saint Petersburg", "Russia", 30.3, 59.9, 2), CityPin("Kyiv", "Ukraine", 30.5, 50.4, 1),
    CityPin("Minsk", "Belarus", 27.6, 53.9, 2), CityPin("Novosibirsk", "Russia", 82.9, 55.0, 2),
    CityPin("Vladivostok", "Russia", 131.9, 43.1, 2), CityPin("Yakutsk", "Russia", 129.7, 62.0, 2),
    CityPin("Riyadh", "Saudi Arabia", 46.7, 24.6, 1), CityPin("Dubai", "UAE", 55.3, 25.2, 1),
    CityPin("Tehran", "Iran", 51.4, 35.7, 1), CityPin("Baghdad", "Iraq", 44.4, 33.3, 1),
    CityPin("Kabul", "Afghanistan", 69.2, 34.5, 2), CityPin("Islamabad", "Pakistan", 73.1, 33.7, 1),
    CityPin("Karachi", "Pakistan", 67.0, 24.9, 1), CityPin("Delhi", "India", 77.1, 28.7, 1),
    CityPin("Mumbai", "India", 72.9, 19.1, 1), CityPin("Bangalore", "India", 77.6, 13.0, 1),
    CityPin("Kolkata", "India", 88.4, 22.6, 1), CityPin("Chennai", "India", 80.3, 13.1, 2),
    CityPin("Dhaka", "Bangladesh", 90.4, 23.8, 1), CityPin("Kathmandu", "Nepal", 85.3, 27.7, 2),
    CityPin("Bangkok", "Thailand", 100.5, 13.8, 1), CityPin("Hanoi", "Vietnam", 105.8, 21.0, 1),
    CityPin("Ho Chi Minh City", "Vietnam", 106.7, 10.8, 1), CityPin("Kuala Lumpur", "Malaysia", 101.7, 3.1, 1),
    CityPin("Singapore", "Singapore", 103.8, 1.35, 1), CityPin("Jakarta", "Indonesia", 106.8, -6.2, 1),
    CityPin("Manila", "Philippines", 121.0, 14.6, 1), CityPin("Hong Kong", "China", 114.2, 22.3, 1),
    CityPin("Taipei", "Taiwan", 121.6, 25.0, 1), CityPin("Beijing", "China", 116.4, 39.9, 1),
    CityPin("Shanghai", "China", 121.5, 31.2, 1), CityPin("Guangzhou", "China", 113.3, 23.1, 1),
    CityPin("Shenzhen", "China", 114.1, 22.5, 1), CityPin("Chengdu", "China", 104.1, 30.7, 2),
    CityPin("Seoul", "South Korea", 127.0, 37.6, 1), CityPin("Tokyo", "Japan", 139.7, 35.7, 1),
    CityPin("Osaka", "Japan", 135.5, 34.7, 2), CityPin("Sapporo", "Japan", 141.3, 43.1, 2),
    CityPin("Ulaanbaatar", "Mongolia", 106.9, 47.9, 2), CityPin("Almaty", "Kazakhstan", 76.9, 43.2, 2),
    CityPin("Tashkent", "Uzbekistan", 69.2, 41.3, 2),
    // Africa
    CityPin("Cairo", "Egypt", 31.2, 30.0, 1), CityPin("Lagos", "Nigeria", 3.4, 6.5, 1),
    CityPin("Kinshasa", "DR Congo", 15.3, -4.3, 1), CityPin("Johannesburg", "South Africa", 28.0, -26.2, 1),
    CityPin("Cape Town", "South Africa", 18.4, -33.9, 1), CityPin("Nairobi", "Kenya", 36.8, -1.3, 1),
    CityPin("Addis Ababa", "Ethiopia", 38.8, 9.0, 1), CityPin("Algiers", "Algeria", 3.1, 36.8, 1),
    CityPin("Casablanca", "Morocco", -7.6, 33.6, 2), CityPin("Accra", "Ghana", -0.2, 5.6, 2),
    CityPin("Dakar", "Senegal", -17.4, 14.7, 2), CityPin("Luanda", "Angola", 13.2, -8.8, 2),
    CityPin("Dar es Salaam", "Tanzania", 39.3, -6.8, 2), CityPin("Tripoli", "Libya", 13.2, 32.9, 2),
    // Oceania
    CityPin("Sydney", "Australia", 151.2, -33.9, 1), CityPin("Melbourne", "Australia", 145.0, -37.8, 1),
    CityPin("Brisbane", "Australia", 153.0, -27.5, 2), CityPin("Perth", "Australia", 115.9, -31.9, 1),
    CityPin("Adelaide", "Australia", 138.6, -34.9, 2), CityPin("Auckland", "New Zealand", 174.8, -36.8, 1),
    CityPin("Wellington", "New Zealand", 174.8, -41.3, 2), CityPin("Port Moresby", "Papua New Guinea", 147.2, -9.5, 2)
)

// Districts erased by Fusion Matter, by day (FusionFall erased districts)
val ERASED_CITIES = listOf(
    Triple("New York", 8, -74.0 to 40.7), Triple("Chicago", 12, -87.6 to 41.8),
    Triple("Los Angeles", 15, -118.2 to 34.1), Triple("London", 16, -0.1 to 51.5),
    Triple("Toronto", 18, -79.4 to 43.7), Triple("Moscow", 19, 37.6 to 55.8),
    Triple("Paris", 21, 2.35 to 48.9), Triple("Mumbai", 22, 72.9 to 19.1),
    Triple("Tokyo", 24, 139.7 to 35.7), Triple("Cape Town", 26, 18.4 to -33.9),
    Triple("Cairo", 27, 31.2 to 30.0), Triple("Rio de Janeiro", 27, -43.2 to -22.9),
    Triple("Jakarta", 28, 106.8 to -6.2), Triple("Sydney", 29, 151.2 to -33.9)
)

private data class Blob(val lon: Double, val lat: Double, val startDay: Int, val maxR: Double, val seed: Double)
private data class Scar(val pts: List<Pair<Double, Double>>, val startDay: Int)

private fun pts(vararg flat: Double): List<Pair<Double, Double>> {
    val out = ArrayList<Pair<Double, Double>>()
    var i = 0
    while (i + 1 < flat.size) { out.add(flat[i] to flat[i + 1]); i += 2 }
    return out
}

// --- The map ----------------------------------------------------------------
class FusionMapView(context: Context) : View(context) {

    var day: Int = 1
        set(value) { field = value.coerceIn(1, 31); invalidate() }

    var onPlaceTapped: ((String) -> Unit)? = null

    companion object {
        val LIME: Int = Color.rgb(61, 255, 26)
        val OCEAN: Int = Color.rgb(20, 61, 94)
    }

    private val density: Float = context.resources.displayMetrics.density

    @Volatile var countries: List<GeoFeature> = emptyList()
    @Volatile var counties: List<GeoFeature> = emptyList()
    @Volatile var stateLabels: List<Triple<String, Double, Double>> = emptyList()

    var zoom: Float = 1f
        private set
    private var centerLon = 0.0
    private var centerLat = 12.0

    private val oceanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OCEAN; style = Paint.Style.FILL }
    private val gridPaint = Paint().apply { color = Color.argb(38, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1f }
    private val countryFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val countryEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 15, 35, 15); style = Paint.Style.STROKE; strokeWidth = 1.1f
    }
    private val countyEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 10, 25, 10); style = Paint.Style.STROKE; strokeWidth = 0.8f
    }
    private val limePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LIME; style = Paint.Style.FILL }
    private val futurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 61, 255, 26); style = Paint.Style.FILL
    }
    private val futureEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LIME; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val scarGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 61, 255, 26); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val scarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LIME; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val cityDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 240, 170); style = Paint.Style.FILL
    }
    private val cityRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 20, 30, 10); style = Paint.Style.STROKE; strokeWidth = 1.2f
    }
    private val erasedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LIME; style = Paint.Style.FILL }
    private val erasedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LIME; style = Paint.Style.STROKE; strokeWidth = 2.4f
    }
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(6, 19, 10); style = Paint.Style.STROKE; strokeWidth = 2.6f; strokeCap = Paint.Cap.ROUND
    }
    private val countryLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        setShadowLayer(4f, 0f, 0f, Color.argb(220, 0, 0, 0))
    }
    private val smallLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255); textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 0f, Color.argb(220, 0, 0, 0))
    }
    private val scaleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.2f
    }
    private val scaleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val scaleLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; setShadowLayer(4f, 0f, 0f, Color.argb(220, 0, 0, 0))
    }

    private val desertCountries = setOf(
        "Algeria", "Libya", "Egypt", "Saudi Arabia", "Yemen", "Oman", "United Arab Emirates",
        "Qatar", "Kuwait", "Iraq", "Jordan", "Mauritania", "Mali", "Niger", "Chad", "Sudan",
        "Namibia", "Botswana", "Australia", "Kazakhstan", "Turkmenistan", "Uzbekistan", "Mongolia"
    )
    private val greens = intArrayOf(
        Color.rgb(101, 146, 84), Color.rgb(93, 138, 76),
        Color.rgb(110, 152, 88), Color.rgb(86, 130, 70)
    )

    private val oceanBlobs = listOf(
        Blob(-30.0, 25.0, 3, 34.0, 1.0), Blob(-150.0, 5.0, 7, 42.0, 2.0),
        Blob(150.0, -5.0, 9, 38.0, 3.0), Blob(80.0, -15.0, 11, 34.0, 4.0),
        Blob(0.0, 78.0, 18, 40.0, 5.0), Blob(-40.0, -45.0, 23, 40.0, 6.0),
        Blob(-90.0, -25.0, 25, 34.0, 7.0)
    )
    private val corruptedZones = listOf(
        Blob(-95.0, 42.0, 1, 20.0, 11.0), Blob(-110.0, 35.0, 2, 15.0, 12.0),
        Blob(-60.0, -15.0, 6, 19.0, 13.0), Blob(-45.0, -30.0, 8, 14.0, 14.0),
        Blob(5.0, 10.0, 6, 18.0, 15.0), Blob(22.0, -12.0, 9, 17.0, 16.0),
        Blob(28.0, -28.0, 12, 13.0, 17.0), Blob(15.0, 50.0, 10, 16.0, 18.0),
        Blob(45.0, 55.0, 14, 20.0, 19.0), Blob(85.0, 28.0, 14, 18.0, 20.0),
        Blob(110.0, 35.0, 17, 18.0, 21.0), Blob(135.0, 48.0, 20, 17.0, 22.0),
        Blob(134.0, -25.0, 22, 17.0, 23.0), Blob(148.0, -32.0, 24, 12.0, 24.0),
        Blob(-125.0, 52.0, 16, 14.0, 25.0), Blob(65.0, 68.0, 22, 22.0, 26.0),
        Blob(-35.0, 74.0, 24, 16.0, 27.0), Blob(0.0, -78.0, 30, 30.0, 28.0)
    )
    private val futureZones = listOf(
        Blob(-77.0, 38.0, 5, 19.0, 31.0), Blob(15.0, 50.0, 12, 17.0, 32.0),
        Blob(120.0, 32.0, 20, 18.0, 33.0)
    )
    private val scars = listOf(
        Scar(pts(-122.0, 50.0, -108.0, 44.0, -94.0, 43.0, -82.0, 39.0, -74.0, 38.0), 4),
        Scar(pts(8.0, 6.0, 16.0, -6.0, 22.0, -18.0, 28.0, -30.0), 9),
        Scar(pts(-72.0, -8.0, -64.0, -20.0, -66.0, -34.0, -68.0, -48.0), 13),
        Scar(pts(-5.0, 48.0, 18.0, 50.0, 45.0, 55.0, 80.0, 58.0, 115.0, 52.0), 17),
        Scar(pts(88.0, 24.0, 102.0, 28.0, 116.0, 33.0, 128.0, 38.0), 24),
        Scar(pts(116.0, -24.0, 128.0, -27.0, 140.0, -32.0, 150.0, -35.0), 26)
    )

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        try {
            val text = context.assets.open("countries.geo.json").bufferedReader().readText()
            countries = parseGeoJson(text, counties = false)
        } catch (e: Exception) {
            countries = emptyList()
        }
        Thread {
            try {
                val text = context.assets.open("us-counties.geo.json").bufferedReader().readText()
                val parsed = parseGeoJson(text, counties = true)
                counties = parsed
                val acc = HashMap<String, DoubleArray>()
                val names = HashMap<String, String>()
                for (c in parsed) {
                    val a = acc.getOrPut(c.stateFips) { doubleArrayOf(0.0, 0.0, 0.0) }
                    a[0] += c.cenLon * c.weight; a[1] += c.cenLat * c.weight; a[2] += c.weight
                    names[c.stateFips] = STATE_NAMES[c.stateFips] ?: c.stateFips
                }
                stateLabels = acc.map { (fips, a) ->
                    Triple(names[fips] ?: fips, a[0] / a[2], a[1] / a[2])
                }
                postInvalidate()
            } catch (e: Exception) {
                counties = emptyList()
            }
        }.start()

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomAt(detector.focusX, detector.focusY, detector.scaleFactor)
                return true
            }
        })
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (scaleDetector.isInProgress) return true
                val s = scaleDeg()
                if (s <= 0f) return true
                centerLon += distanceX / s
                centerLat -= distanceY / s
                clampCenter()
                invalidate()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoomAt(e.x, e.y, 1.7f)
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                identify(e.x, e.y)
                return true
            }
        })
    }

    // --- transforms ---------------------------------------------------------
    private fun scaleDeg(): Float {
        if (width == 0 || height == 0) return 1f
        return minOf(width / 360f, height / 180f) * zoom
    }

    private fun sx(lon: Double): Float = (width / 2.0 + (lon - centerLon) * scaleDeg()).toFloat()
    private fun sy(lat: Double): Float = (height / 2.0 - (lat - centerLat) * scaleDeg()).toFloat()
    private fun lonAt(px: Float): Double = centerLon + (px - width / 2.0) / scaleDeg()
    private fun latAt(py: Float): Double = centerLat - (py - height / 2.0) / scaleDeg()

    private fun visibleLon(): ClosedFloatingPointRange<Double> {
        val half = width / 2.0 / scaleDeg()
        return (centerLon - half)..(centerLon + half)
    }

    private fun visibleLat(): ClosedFloatingPointRange<Double> {
        val half = height / 2.0 / scaleDeg()
        return (centerLat - half)..(centerLat + half)
    }

    private fun clampCenter() {
        centerLon = centerLon.coerceIn(-180.0, 180.0)
        centerLat = centerLat.coerceIn(-82.0, 85.0)
    }

    fun zoomAt(fx: Float, fy: Float, factor: Float) {
        val anchorLon = lonAt(fx)
        val anchorLat = latAt(fy)
        zoom = (zoom * factor).coerceIn(1f, 60f)
        val s = scaleDeg()
        centerLon = anchorLon - (fx - width / 2.0) / s
        centerLat = anchorLat + (fy - height / 2.0) / s
        clampCenter()
        invalidate()
    }

    fun zoomBy(factor: Float) = zoomAt(width / 2f, height / 2f, factor)

    fun resetView() {
        zoom = 1f; centerLon = 0.0; centerLat = 12.0
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // --- geometry helpers ---------------------------------------------------
    private fun ringPath(ring: FloatArray): Path {
        val path = Path()
        var prevLon = 0f
        var i = 0
        var first = true
        while (i + 1 < ring.size) {
            val lo = ring[i]; val la = ring[i + 1]
            val px = sx(lo.toDouble()); val py = sy(la.toDouble())
            if (first) {
                path.moveTo(px, py); first = false
            } else if (abs(lo - prevLon) > 180f) {
                path.moveTo(px, py) // antimeridian jump: break instead of streaking
            } else {
                path.lineTo(px, py)
            }
            prevLon = lo
            i += 2
        }
        path.close()
        return path
    }

    private fun featurePath(f: GeoFeature): Path {
        val path = Path()
        path.fillType = Path.FillType.EVEN_ODD
        for (ring in f.rings) path.addPath(ringPath(ring))
        return path
    }

    private fun intersects(f: GeoFeature, lonR: ClosedFloatingPointRange<Double>, latR: ClosedFloatingPointRange<Double>): Boolean =
        f.maxLon >= lonR.start && f.minLon <= lonR.endInclusive &&
            f.maxLat >= latR.start && f.minLat <= latR.endInclusive

    private fun pointInRing(ring: FloatArray, lon: Double, lat: Double): Boolean {
        var inside = false
        var j = ring.size - 2
        var i = 0
        while (i + 1 < ring.size) {
            val xi = ring[i].toDouble(); val yi = ring[i + 1].toDouble()
            val xj = ring[j].toDouble(); val yj = ring[j + 1].toDouble()
            if ((yi > lat) != (yj > lat)) {
                val xCross = (xj - xi) * (lat - yi) / (yj - yi) + xi
                if (lon < xCross) inside = !inside
            }
            j = i; i += 2
        }
        return inside
    }

    private fun pointInFeature(f: GeoFeature, lon: Double, lat: Double): Boolean {
        if (lon < f.minLon || lon > f.maxLon || lat < f.minLat || lat > f.maxLat) return false
        var inside = false
        for (ring in f.rings) if (pointInRing(ring, lon, lat)) inside = !inside
        return inside
    }

    private fun growth(b: Blob): Double {
        if (day < b.startDay) return 0.0
        val span = (31 - b.startDay).coerceAtLeast(1)
        val f = ((day - b.startDay + 1).toDouble() / (span + 1)).coerceIn(0.0, 1.0)
        return 0.22 + 0.78 * f
    }

    private fun blobRadiusDeg(b: Blob): Double = b.maxR * growth(b)

    private fun blobPath(cxLon: Double, cyLat: Double, rDeg: Double, seed: Double): Path {
        val path = Path()
        val s = scaleDeg()
        val cx = sx(cxLon); val cy = sy(cyLat)
        val r = (rDeg * s).toFloat()
        val n = 14
        for (i in 0..n) {
            val a = 2.0 * Math.PI * i / n
            val wobble = 0.74 + 0.26 * sin(seed * 12.99 + i * 2.399)
            val px = cx + (r * wobble * kotlin.math.cos(a)).toFloat()
            val py = cy + (r * wobble * kotlin.math.sin(a)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        return path
    }

    private fun drawBlob(canvas: Canvas, b: Blob, paint: Paint) {
        val r = blobRadiusDeg(b)
        if (r <= 0.0) return
        canvas.drawPath(blobPath(b.lon, b.lat, r, b.seed), paint)
        canvas.drawPath(blobPath(b.lon + b.maxR * 0.55, b.lat + b.maxR * 0.3, r * 0.4, b.seed + 7.0), paint)
        canvas.drawPath(blobPath(b.lon - b.maxR * 0.5, b.lat - b.maxR * 0.35, r * 0.32, b.seed + 13.0), paint)
    }

    private fun countryColor(f: GeoFeature): Int = when {
        f.name == "Antarctica" -> Color.rgb(232, 240, 242)
        f.name == "Greenland" -> Color.rgb(214, 230, 228)
        desertCountries.contains(f.name) -> Color.rgb(194, 161, 92)
        else -> greens[abs(f.name.hashCode()) % greens.size]
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width; val h = height
        if (w == 0 || h == 0) return
        val s = scaleDeg()
        val lonR = visibleLon(); val latR = visibleLat()

        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), oceanPaint)

        // Fusion-matter oceans under the land
        for (b in oceanBlobs) drawBlob(canvas, b, limePaint)

        // graticule every 30 degrees
        var lon = -150.0
        while (lon <= 150.0) {
            val gx = sx(lon); canvas.drawLine(gx, 0f, gx, h.toFloat(), gridPaint); lon += 30.0
        }
        var lat = -60.0
        while (lat <= 60.0) {
            val gy = sy(lat); canvas.drawLine(0f, gy, w.toFloat(), gy, gridPaint); lat += 30.0
        }

        // Countries: real borders, natural colours
        for (f in countries) {
            if (!intersects(f, lonR, latR)) continue
            val p = featurePath(f)
            countryFillPaint.color = countryColor(f)
            canvas.drawPath(p, countryFillPaint)
            canvas.drawPath(p, countryEdgePaint)
        }

        // US counties appear as you zoom in
        if (s >= 10f && counties.isNotEmpty()) {
            for (f in counties) {
                if (!intersects(f, lonR, latR)) continue
                canvas.drawPath(featurePath(f), countyEdgePaint)
            }
        }

        // Future zones (lime, ringed)
        for (b in futureZones) {
            val r = blobRadiusDeg(b); if (r <= 0.0) continue
            val p = blobPath(b.lon, b.lat, r, b.seed)
            canvas.drawPath(p, futurePaint)
            canvas.drawPath(p, futureEdgePaint)
        }

        // Corrupted zones (lime)
        for (b in corruptedZones) drawBlob(canvas, b, limePaint)

        // Fusion scars: lengthen daily
        scarPaint.strokeWidth = (s * 0.10f).coerceIn(3f, 9f)
        scarGlowPaint.strokeWidth = scarPaint.strokeWidth * 2.4f
        for (scar in scars) {
            if (day < scar.startDay) continue
            val span = (31 - scar.startDay).coerceAtLeast(1)
            val frac = ((day - scar.startDay + 1).toDouble() / (span + 1)).coerceIn(0.0, 1.0)
            val segs = scar.pts.size - 1
            val upto = (frac * segs).coerceAtLeast(0.35)
            val path = Path()
            path.moveTo(sx(scar.pts[0].first), sy(scar.pts[0].second))
            var i = 1
            while (i <= upto.toInt() && i < scar.pts.size) {
                path.lineTo(sx(scar.pts[i].first), sy(scar.pts[i].second)); i++
            }
            if (i < scar.pts.size && upto - upto.toInt() > 0.0) {
                val (lo1, la1) = scar.pts[i - 1]
                val (lo2, la2) = scar.pts[i]
                val fr = upto - upto.toInt()
                path.lineTo(sx(lo1 + (lo2 - lo1) * fr), sy(la1 + (la2 - la1) * fr))
            }
            canvas.drawPath(path, scarGlowPaint)
            canvas.drawPath(path, scarPaint)
        }

        drawLabels(canvas, s, lonR, latR)
        drawCitiesAndErased(canvas, s)
        drawScaleBar(canvas, s)
    }

    private fun drawLabels(
        canvas: Canvas, s: Float,
        lonR: ClosedFloatingPointRange<Double>, latR: ClosedFloatingPointRange<Double>
    ) {
        // country names
        if (s >= 4.2f) {
            countryLabelPaint.textSize = (11.5f * density * (1f + (s / 60f))).coerceAtMost(15f * density)
            for (f in countries) {
                if (!intersects(f, lonR, latR)) continue
                val bw = (f.maxLon - f.minLon) * s
                if (bw < 40f && s < 26f) continue
                if (f.name == "Antarctica" && s < 18f) continue
                canvas.drawText(f.name.uppercase(), sx(f.cenLon), sy(f.cenLat), countryLabelPaint)
            }
        }
        // US state names (medium zoom)
        if (s >= 10f && s < 52f) {
            smallLabelPaint.textSize = 12f * density
            smallLabelPaint.isFakeBoldText = true
            for ((name, lo, la) in stateLabels) {
                if (lo !in lonR || la !in latR) continue
                canvas.drawText(name.uppercase(), sx(lo), sy(la), smallLabelPaint)
            }
            smallLabelPaint.isFakeBoldText = false
        }
        // US county names (deep zoom): largest visible counties only
        if (s >= 52f && counties.isNotEmpty()) {
            smallLabelPaint.textSize = 10f * density
            val visible = counties.filter { intersects(it, lonR, latR) && it.cenLon in lonR && it.cenLat in latR }
                .sortedByDescending { it.weight }.take(30)
            for (f in visible) {
                canvas.drawText(f.name, sx(f.cenLon), sy(f.cenLat), smallLabelPaint)
            }
        }
    }

    private fun drawCitiesAndErased(canvas: Canvas, s: Float) {
        val erasedByName = ERASED_CITIES.associateBy { it.first }
        for (city in CITIES) {
            val cx = sx(city.lon); val cy = sy(city.lat)
            if (cx < -30 || cx > width + 30 || cy < -30 || cy > height + 30) continue
            val erased = erasedByName[city.name]
            if (erased != null && day >= erased.second) continue // drawn below as erased
            val r = if (city.tier == 1) 4.2f else 3f
            canvas.drawCircle(cx, cy, r, cityDotPaint)
            canvas.drawCircle(cx, cy, r, cityRingPaint)
            val showLabel = (city.tier == 1 && s >= 5.5f) || (city.tier == 2 && s >= 15f)
            if (showLabel) {
                smallLabelPaint.textSize = 10.5f * density
                val oldAlign = smallLabelPaint.textAlign
                smallLabelPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(city.name, cx + r + 3f, cy + 3.5f, smallLabelPaint)
                smallLabelPaint.textAlign = oldAlign
            }
        }
        // erased districts: lime disc with X
        val r = 6f * density
        for ((_, eraseDay, coord) in ERASED_CITIES) {
            if (day < eraseDay) continue
            val cx = sx(coord.first); val cy = sy(coord.second)
            if (cx < -40 || cx > width + 40 || cy < -40 || cy > height + 40) continue
            canvas.drawCircle(cx, cy, r, erasedPaint)
            canvas.drawCircle(cx, cy, r + 3.5f, erasedRingPaint)
            canvas.drawLine(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.55f, cy + r * 0.55f, xPaint)
            canvas.drawLine(cx + r * 0.55f, cy - r * 0.55f, cx - r * 0.55f, cy + r * 0.55f, xPaint)
        }
    }

    private fun isConsumed(lon: Double, lat: Double): Boolean {
        fun hit(b: Blob): Boolean {
            val r = blobRadiusDeg(b)
            if (r <= 0.0) return false
            val dLon = (lon - b.lon) * kotlin.math.cos(Math.toRadians(b.lat))
            val dLat = lat - b.lat
            return dLon * dLon + dLat * dLat <= r * r * 0.8
        }
        return corruptedZones.any(::hit) || futureZones.any(::hit) || oceanBlobs.any(::hit)
    }

    private fun oceanName(lon: Double, lat: Double): String = when {
        lat > 66 -> "Arctic Ocean"
        lat < -55 -> "Southern Ocean"
        lon in -70.0..20.0 -> "Atlantic Ocean"
        lon in 20.0..146.0 && lat < 25 -> "Indian Ocean"
        else -> "Pacific Ocean"
    }

    private fun identify(px: Float, py: Float) {
        val lon = lonAt(px); val lat = latAt(py)
        if (lon !in -180.0..180.0 || lat !in -90.0..90.0) return
        // nearest city on screen
        var best: CityPin? = null; var bestD = Float.MAX_VALUE
        for (city in CITIES) {
            val dx = sx(city.lon) - px; val dy = sy(city.lat) - py
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = city }
        }
        val tapR = 26f * density
        if (best != null && bestD <= tapR * tapR) {
            val city = best!!
            val erased = ERASED_CITIES.firstOrNull { it.first == city.name }
            val status = when {
                erased != null && day >= erased.second -> "ERASED on Oct ${erased.second} — fully lime ✕"
                erased != null -> "still standing — erasure projected Oct ${erased.second}"
                isConsumed(city.lon, city.lat) -> "inside today's Fusion Matter — consumed"
                else -> "still normal — for now"
            }
            onPlaceTapped?.invoke("📍 ${city.name}, ${city.country} — $status")
            return
        }
        // county (US deep detail)
        if (counties.isNotEmpty() && lon in -180.0..-60.0 && lat in 15.0..72.0) {
            for (f in counties) {
                if (pointInFeature(f, lon, lat)) {
                    val state = STATE_NAMES[f.stateFips] ?: ""
                    val status = if (isConsumed(f.cenLon, f.cenLat)) "Fusion Matter present" else "no Fusion Matter yet"
                    onPlaceTapped?.invoke("📍 ${f.name}, $state, United States — $status")
                    return
                }
            }
        }
        // country
        for (f in countries) {
            if (pointInFeature(f, lon, lat)) {
                val status = if (isConsumed(f.cenLon, f.cenLat)) "Fusion Matter present" else "no Fusion Matter yet"
                onPlaceTapped?.invoke("📍 ${f.name} — $status")
                return
            }
        }
        val status = if (isConsumed(lon, lat)) "Fusion-matter ocean — consumed" else "normal ocean"
        onPlaceTapped?.invoke("🌊 ${oceanName(lon, lat)} — $status")
    }

    private fun drawScaleBar(canvas: Canvas, s: Float) {
        val milesPerDeg = FusionModel.EARTH_CIRCUMFERENCE_MI / 360.0 // 69.17 mi at the equator
        val candidates = doubleArrayOf(5000.0, 2000.0, 1000.0, 500.0, 200.0, 100.0, 50.0, 20.0, 10.0, 5.0)
        var total = candidates.last()
        for (c in candidates) {
            val pxW = (c / milesPerDeg * s).toFloat()
            if (pxW <= width * 0.36f && pxW >= 50f) { total = c; break }
            total = c
        }
        val segPx = (total / 2.0 / milesPerDeg * s).toFloat()
        val baseY = height - 30f * density
        val left = 14f * density
        val barH = 7f * density
        canvas.drawRect(left, baseY, left + segPx, baseY + barH, scaleFillPaint)
        canvas.drawRect(left, baseY, left + segPx * 2, baseY + barH, scaleStrokePaint)
        canvas.drawLine(left + segPx, baseY, left + segPx, baseY + barH, scaleStrokePaint)
        scaleLabelPaint.textSize = 10.5f * density
        canvas.drawText("0", left, baseY - 5f, scaleLabelPaint)
        val mid = String.format("%,.0f", total / 2)
        canvas.drawText(mid, left + segPx - scaleLabelPaint.measureText(mid) / 2f, baseY - 5f, scaleLabelPaint)
        val end = String.format("%,.0f mi", total)
        canvas.drawText(end, left + segPx * 2 - scaleLabelPaint.measureText(end) / 2f, baseY - 5f, scaleLabelPaint)
    }
}

// --- Activity ---------------------------------------------------------------
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
    private lateinit var infoText: TextView
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
            setDay(day + 1)
            handler.postDelayed(this, 650)
        }
    }

    private val introTick = object : Runnable {
        override fun run() {
            if (day < liveTarget) {
                setDay(day + 1)
                handler.postDelayed(this, 170)
            }
        }
    }

    private val liveWatch = object : Runnable {
        override fun run() {
            if (live) {
                val real = FusionModel.realTimeDay()
                liveText.text = liveLabel()
                if (real != day) {
                    liveTarget = real
                    setDay(real)
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
        mapView.onPlaceTapped = { info -> infoText.text = info }
        root.addView(
            mapView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        infoText = TextView(this).apply {
            text = "Pinch or use + / − to zoom · drag to pan · double-tap zooms · tap the map: countries → US states → all 3,221 counties → cities"
            textSize = 12.5f
            setTextColor(Color.rgb(255, 240, 170))
            setPadding(0, dp(5), 0, 0)
        }
        root.addView(infoText)

        eventText = TextView(this).apply {
            textSize = 13.5f; setTextColor(Color.rgb(230, 255, 230))
            setPadding(0, dp(3), 0, dp(2))
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
                        setDay(progress + 1)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(seek)

        fun ctrlBtn(label: String): Button = Button(this).apply {
            text = label; textSize = 14f
            setTextColor(Color.rgb(6, 19, 10))
            backgroundTintList = ColorStateList.valueOf(lime)
        }
        fun rowOf(buttons: List<Button>, weights: List<Float>) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            buttons.forEachIndexed { i, b ->
                row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[i]).apply {
                    setMargins(dp(3), dp(4), dp(3), dp(4))
                })
            }
            root.addView(row)
        }

        val zoomInBtn = ctrlBtn("＋ Zoom in")
        val zoomOutBtn = ctrlBtn("－ Zoom out")
        val resetBtn = ctrlBtn("⌂ Whole Earth")
        zoomInBtn.setOnClickListener { mapView.zoomBy(1.5f) }
        zoomOutBtn.setOnClickListener { mapView.zoomBy(1f / 1.5f) }
        resetBtn.setOnClickListener { mapView.resetView() }
        rowOf(listOf(zoomOutBtn, zoomInBtn, resetBtn), listOf(1f, 1f, 1f))

        val prevBtn = ctrlBtn("◀ Day")
        playButton = ctrlBtn("▶ Play 31 days")
        val nextBtn = ctrlBtn("Day ▶")
        liveButton = ctrlBtn("● GO LIVE")
        prevBtn.setOnClickListener { stopPlay(); live = false; setDay(day - 1) }
        nextBtn.setOnClickListener { stopPlay(); live = false; setDay(day + 1) }
        playButton.setOnClickListener {
            if (playing) { stopPlay(); return@setOnClickListener }
            live = false
            if (day >= 31) setDay(1)
            playing = true
            playButton.text = "⏸ Pause"
            handler.postDelayed(playTick, 650)
        }
        liveButton.setOnClickListener { goLive(animate = true) }
        rowOf(listOf(prevBtn, playButton, nextBtn, liveButton), listOf(1f, 1.3f, 1f, 1f))

        root.addView(legend(lime))

        setContentView(root)

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
            setDay(1)
            handler.removeCallbacks(introTick)
            handler.postDelayed(introTick, 350)
        } else {
            setDay(liveTarget)
        }
    }

    private fun stopPlay() {
        playing = false
        handler.removeCallbacks(playTick)
        if (::playButton.isInitialized) playButton.text = "▶ Play 31 days"
    }

    private fun setDay(newDay: Int) {
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
