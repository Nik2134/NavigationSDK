package org.maplibre.navigation.android.example

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import okhttp3.*
import org.maplibre.geojson.Point
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.*
import org.maplibre.navigation.android.example.databinding.ActivityNavigationUiBinding
import org.maplibre.navigation.android.navigation.ui.v5.route.NavigationMapRoute
import org.maplibre.navigation.core.instruction.Instruction
import org.maplibre.navigation.core.location.Location
import org.maplibre.navigation.core.location.replay.ReplayRouteLocationEngine
import org.maplibre.navigation.core.milestone.*
import org.maplibre.navigation.core.models.*
import org.maplibre.navigation.core.navigation.*
import org.maplibre.navigation.core.offroute.OffRouteListener
import org.maplibre.navigation.core.routeprogress.ProgressChangeListener
import org.maplibre.navigation.core.routeprogress.RouteProgress
import org.maplibre.turf.TurfConstants
import org.maplibre.turf.TurfMeasurement
import timber.log.Timber
import java.io.IOException
import java.util.Locale
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.maplibre.navigation.core.location.engine.LocationEngineProvider
import android.os.Handler
import android.os.Looper
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import android.graphics.Color
import org.maplibre.geojson.LineString
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.Property
//Adding import liabraries for traffic
import org.maplibre.android.style.expressions.Expression
import com.google.gson.JsonParser
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import java.text.SimpleDateFormat
import java.util.Calendar


class ValhallaNavigationActivity :
    AppCompatActivity(),
    OnMapReadyCallback,
    MapLibreMap.OnMapClickListener,
    ProgressChangeListener,
    NavigationEventListener,
    MilestoneEventListener,
    OffRouteListener {
    private lateinit var mapLibreMap: MapLibreMap
    private var language = Locale.getDefault().language
    private var route: DirectionsRoute? = null
    private var navigationMapRoute: NavigationMapRoute? = null
    private var destination: Point? = null
    private var locationComponent: LocationComponent? = null
    private lateinit var navigation: MapLibreNavigation
    private lateinit var locationEngine: ReplayRouteLocationEngine // Use replay engine for simulation

    private lateinit var binding: ActivityNavigationUiBinding
    private var simulateRoute = false // Enable simulation for testing off-route
    private var isNavigationRunning = false
    private val BEGIN_ROUTE_MILESTONE = 1001
    private var isSimulatingOffRoute = false

    // Traffic layer variables
    private var trafficLayerAdded = false
    private val trafficSourceId = "here-traffic-source"
    private val trafficLayerId = "here-traffic-layer"
    private var trafficUpdateHandler: Handler? = null
    private var trafficUpdateRunnable: Runnable? = null
    private val trafficUpdateInterval: Long = 300000 // Update every 5 minutes

    private var baseEta: Double = 0.0
    private var trafficAdjustedEta: Double = 0.0
    private var lastEtaUpdateTime: Long = 0
    private val etaUpdateInterval: Long = 30000 // Update ETA every 30 seconds

    // Add these variables to your class
    private var remainingDistance: Double = 0.0
    private var totalDistance: Double = 0.0
    private var entireRoutePoints: List<Point> = emptyList()
    private var upcomingRoutePoints: List<Point> = emptyList()

    companion object {
        private const val INITIAL_ZOOM_LEVEL = 12.0
        private const val NAVIGATION_ZOOM_LEVEL = 16.0
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        try {
            Timber.i("Initializing ValhallaNavigationActivity")
            binding = ActivityNavigationUiBinding.inflate(layoutInflater)
            //Adding ETA code
            binding.etaTextView.visibility = View.GONE
            setContentView(binding.root)
            binding.mapView.apply {
                onCreate(savedInstanceState)
                getMapAsync(this@ValhallaNavigationActivity)
            }
            // Ensure button is hidden at start
            binding.recenterButton.visibility = View.GONE


            navigation = AndroidMapLibreNavigation(applicationContext)
            locationEngine = ReplayRouteLocationEngine() // Initialize replay engine

            navigation.addMilestone(
                RouteMilestone(
                    identifier = BEGIN_ROUTE_MILESTONE,
                    instruction = BeginRouteInstruction(),
                    trigger = Trigger.all(
                        Trigger.lt(TriggerProperty.STEP_INDEX, 3),
                        Trigger.gt(TriggerProperty.STEP_DISTANCE_TOTAL_METERS, 200),
                        Trigger.gte(TriggerProperty.STEP_DISTANCE_TRAVELED_METERS, 75)
                    ),
                )
            )

            setupUiListeners()

            // Initialize traffic update handler
            trafficUpdateHandler = Handler(Looper.getMainLooper())

            // Add traffic toggle listener
            binding.trafficToggle.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    addTrafficLayer()
                } else {
                    removeTrafficLayer()
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "Error during onCreate initialization")
        }
    }

    private fun setupUiListeners() {
        binding.startRouteButton.setOnClickListener {
            try {
                route?.let { route ->
                    Timber.i("Start Route button clicked - Starting navigation")
                    binding.startRouteButton.visibility = View.INVISIBLE

                    navigation.apply {
                        addNavigationEventListener(this@ValhallaNavigationActivity)
                        addProgressChangeListener(this@ValhallaNavigationActivity)
                        addMilestoneEventListener(this@ValhallaNavigationActivity)
                        addOffRouteListener(this@ValhallaNavigationActivity)
                    }

                    Timber.i("Setting location engine and starting navigation")

                    if (simulateRoute) {
                        Timber.i("Simulate route is true for  navigation")
                        locationEngine.assign(route) // Only replay engine supports assign
                        navigation.locationEngine = locationEngine
                    } else {
                        // For real navigation, use the component's engine
                        Timber.i("Real navigation route is true")
                        val navEngine = LocationEngineProvider.getBestLocationEngine(applicationContext)
                        navigation.locationEngine = navEngine

                    }

                    navigation.startNavigation(route)

                    if (::mapLibreMap.isInitialized) {
                        moveCameraToLastKnownLocationOrWait(NAVIGATION_ZOOM_LEVEL)
                        Timber.i("Camera animation started")
                        mapLibreMap.removeOnMapClickListener(this)
                    }
                } ?: Timber.w("No route found when trying to start navigation")
            } catch (e: Exception) {
                Timber.e(e, "Error starting navigation")
            }
        }

        binding.clearPoints.setOnClickListener {
            try {
                Timber.i("Clear Points clicked")
                if (::mapLibreMap.isInitialized) {
                    mapLibreMap.markers.forEach {
                        mapLibreMap.removeMarker(it)
                    }
                }
                destination = null
                it.visibility = View.GONE
                binding.startRouteLayout.visibility = View.GONE
                navigationMapRoute?.removeRoute()

                if (isNavigationRunning) {
                    navigation.stopNavigation()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error clearing points")
            }
        }
    }

    override fun onMapReady(mapLibreMap: MapLibreMap) {
        try {
            Timber.i("Map is ready")
            this.mapLibreMap = mapLibreMap
            mapLibreMap.setStyle(
                Style.Builder().fromUri(getString(R.string.map_style_light))
            ) { style ->

                // Overall route (grey background line)
                if (style.getSource("overall-route-source") == null) {
                    style.addSource(GeoJsonSource("overall-route-source"))
                }
                style.addLayerBelow(
                    LineLayer("overall-route-layer", "overall-route-source").withProperties(
                        lineColor(Color.LTGRAY),
                        lineWidth(6f)
                    ),
                    "road-label"
                )

                // Add sources for traveled & upcoming
                if (style.getSource("traveled-source") ==null) {
                    val geoJsonSource = GeoJsonSource("traveled-source")
                    style.addSource(geoJsonSource)
                }

                if (style.getSource("upcoming-source")==null) {
                    val geoJsonSource = GeoJsonSource("upcoming-source")
                    style.addSource(geoJsonSource)
                }

                // Traveled layer (gray)
                style.addLayerAbove(
                    LineLayer("traveled-layer", "traveled-source").withProperties(
                        lineColor(Color.GRAY),
                        lineWidth(6f)
                    ) ,
                    "overall-route-layer"
                )

                // Upcoming layer (blue)
                style.addLayerAbove(
                    LineLayer("upcoming-layer", "upcoming-source").withProperties(
                        lineColor(Color.BLUE),
                        lineWidth(6f)
                    ),
                    "traveled-layer"

                )

                // Traffic layer should be above upcoming but below markers
                style.addLayerAbove(
                    LineLayer(trafficLayerId, trafficSourceId).withProperties(
                        lineWidth(6f),
                        lineOpacity(0.8f)
                    ),
                    "upcoming-layer"
                )


                // ✅ Marker source
                if (style.getSource("marker-source") == null) {
                    style.addSource(GeoJsonSource("marker-source"))
                }

                // ✅ Marker icon (make sure ic_marker.png exists in res/drawable)
                style.addImage(
                    "marker-icon",
                    android.graphics.BitmapFactory.decodeResource(resources, R.drawable.map_marker_light)
                )

                style.addLayer(
                    SymbolLayer("marker-layer", "marker-source")
                        .withProperties(
                            iconImage("marker-icon"),
                            iconAnchor(Property.ICON_ANCHOR_CENTER), // ⬅️ keep it above
                            iconAllowOverlap(true)
                        )
                )


                // Prepare traffic layer (but don't add it yet)
                prepareTrafficLayer(style)

                enableLocationComponent(style)
                // Set initial zoom level
                moveCameraToLastKnownLocationOrWait(INITIAL_ZOOM_LEVEL)


                mapLibreMap.setPadding(0, 1000, 0, 50) // bottom padding pushes the arrow up

                mapLibreMap.uiSettings.focalPoint = android.graphics.PointF(
                    binding.mapView.width / 2f,     // keep it horizontally centered
                    binding.mapView.height * 0.50f  // push it lower on screen → puck moves upward
                )



                navigationMapRoute = NavigationMapRoute(binding.mapView, mapLibreMap)
                mapLibreMap.addOnMapClickListener(this)
                Snackbar.make(findViewById(R.id.container), "Tap map to place destination", Snackbar.LENGTH_LONG).show()
            }



        } catch (e: Exception) {
            Timber.e(e, "Error in onMapReady")
        }
    }


    @SuppressLint("MissingPermission")
    private fun enableLocationComponent(style: Style) {
        locationComponent = mapLibreMap.locationComponent

        locationComponent?.let {
            it.activateLocationComponent(
                LocationComponentActivationOptions.builder(this, style).build()
            )
            it.isLocationComponentEnabled = true
            it.cameraMode = CameraMode.NONE
            it.renderMode = RenderMode.GPS


            // ✅  recenter button logic
            binding.recenterButton.setOnClickListener {
                // Use appropriate zoom level based on navigation state
                val zoomLevel = if (isNavigationRunning) NAVIGATION_ZOOM_LEVEL else INITIAL_ZOOM_LEVEL
                mapLibreMap.setPadding(0, 1000, 0, 50)
                locationComponent?.cameraMode = CameraMode.TRACKING_GPS
                moveCameraToLastKnownLocationOrWait(zoomLevel)
                hideRecenterButton()
            }


            // ✅ Use Navigation SDK engine, not the Maps engine
            val navEngine = org.maplibre.navigation.core.location.engine.LocationEngineProvider
                .getBestLocationEngine(applicationContext)
            navigation.locationEngine = navEngine

            it.addOnLocationClickListener {
                val loc = locationComponent?.lastKnownLocation
                Timber.d("Last known location at camera move: $loc")
                println("Last known location at camera move: $loc")

                if (loc != null) {
                    val zoomLevel = if (isNavigationRunning) NAVIGATION_ZOOM_LEVEL else INITIAL_ZOOM_LEVEL
                    val cameraPosition = CameraPosition.Builder()
                        .target(LatLng(loc.latitude, loc.longitude))
                        .zoom(zoomLevel)
                        .build()
                    mapLibreMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
                    Timber.i(
                        "Camera moved to current location: %s, %s with zoom %f",
                        loc.latitude, loc.longitude, zoomLevel
                    )

                } else {
                    Timber.w("LocationComponent: lastKnownLocation is still null")
                }
            }
        }
    }


    override fun onMapClick(point: LatLng): Boolean {
        return try {
            Timber.i("Map clicked at: %s", point)
            destination = Point.fromLngLat(point.longitude, point.latitude)

            // ✅ Update marker source instead of addMarker()
            mapLibreMap.style?.getSourceAs<GeoJsonSource>("marker-source")
                ?.setGeoJson(Point.fromLngLat(point.longitude, point.latitude))

            binding.clearPoints.visibility = View.VISIBLE
            calculateRoute()
            true
        } catch (e: Exception) {
            Timber.e(e, "Error handling map click")
            false
        }
    }

    private fun calculateRoute() {
        try {
            binding.startRouteLayout.visibility = View.GONE
            val userLocation = mapLibreMap.locationComponent.lastKnownLocation
            val dest = destination

            if (userLocation == null || dest == null) {
                Timber.w("Cannot calculate route - missing user location or destination")
                return
            }

            val origin = Point.fromLngLat(userLocation.longitude, userLocation.latitude)
            if (TurfMeasurement.distance(origin, dest, TurfConstants.UNIT_METERS) < 50) {
                Timber.i("Destination is within 50 meters - ignoring route calculation")
                binding.startRouteButton.visibility = View.GONE
                return
            }

            val requestBody = mapOf(
                "format" to "osrm",
                "costing" to "auto",
                "banner_instructions" to true,
                "voice_instructions" to true,
                "language" to language,
                "directions_options" to mapOf("units" to "kilometers"),
                "costing_options" to mapOf("auto" to mapOf("top_speed" to 130)),
                "locations" to listOf(
                    mapOf("lon" to origin.longitude(), "lat" to origin.latitude(), "type" to "break"),
                    mapOf("lon" to dest.longitude(), "lat" to dest.latitude(), "type" to "break")
                )
            )

            val requestBodyJson = Gson().toJson(requestBody)
            val client = OkHttpClient()
            val request = Request.Builder()
                .header("User-Agent", "MapLibre Android Navigation SDK Demo App")
                .url(getString(R.string.valhalla_url))
                .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            Timber.d("Enqueuing Valhalla route request: %s", requestBodyJson)

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Timber.e(e, "Failed to get route from Valhalla")
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (it.isSuccessful) {
                                val responseBodyJson = it.body!!.string()
                                Timber.d("Valhalla response: %s", responseBodyJson)

                                val maplibreResponse = DirectionsResponse.fromJson(responseBodyJson)
                                val assignedRoute = maplibreResponse.routes.first().copy(
                                    routeOptions = RouteOptions(
                                        baseUrl = "https://mapapi.genesysmap.com/api/v1/directions/route",
                                        profile = "auto",
                                        user = "user1",
                                        accessToken = "94a5ee1ecf7c4f10a8ea7a3f3a54b3de",
                                        voiceInstructions = true,
                                        bannerInstructions = true,
                                        language = language,
                                        coordinates = listOf(origin, dest),
                                        requestUuid = "0000-0000-0000-0000"
                                    )
                                )

                                // Store base ETA (without traffic)
                                baseEta = assignedRoute.duration // This might vary based on your DirectionsRoute implementation
//                                trafficAdjustedEta = baseEta
                                lastEtaUpdateTime = System.currentTimeMillis()
                                totalDistance = assignedRoute.distance // Store total distance
                                remainingDistance = totalDistance

                                this@ValhallaNavigationActivity.route = assignedRoute

                                val encodedGeometry = assignedRoute.geometry!!
                                refreshTrafficData()
                                // Update ETA display
                                updateEtaDisplay()
                                val fullLine = LineString.fromPolyline(encodedGeometry, 6)
                                entireRoutePoints = fullLine.coordinates() // Store the entire route points


                                runOnUiThread {
                                    mapLibreMap.style?.getSourceAs<GeoJsonSource>("overall-route-source")
                                        ?.setGeoJson(fullLine)

                                    navigationMapRoute?.addRoutes(maplibreResponse.routes)
                                    binding.startRouteLayout.visibility = View.VISIBLE

                                }
                            } else {
                                Timber.e("Valhalla request failed: %d - %s", it.code, it.body?.string())
                            }
                        }
                    } catch (ex: Exception) {
                        Timber.e(ex, "Error processing Valhalla route response")
                    }
                }
            })
        } catch (e: Exception) {
            Timber.e(e, "Error in calculateRoute")
        }
    }


    private fun moveCameraToLastKnownLocationOrWait(zoomLevel: Double = INITIAL_ZOOM_LEVEL, maxRetries: Int = 8, delayMs: Long = 700)  {
        var attempts = 0
        fun tryMove() {
            try {
                val loc = mapLibreMap.locationComponent.lastKnownLocation
                Timber.d("moveCamera try #$attempts lastKnownLocation: $loc")
                println("moveCamera try #$attempts lastKnownLocation: $loc")

                if (loc != null) {
                    val cameraPosition = CameraPosition.Builder()
                        .target(LatLng(loc.latitude, loc.longitude))
                        .zoom(zoomLevel)
                        .bearing(loc.bearing.toDouble())      // orient in direction of travel
                        .tilt(45.0)
                        .build()

                    mapLibreMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
                    mapLibreMap.locationComponent.cameraMode = CameraMode.TRACKING_GPS
                    mapLibreMap.locationComponent.renderMode = RenderMode.GPS

                    mapLibreMap.setPadding(0, 1000, 0, 0) // 400px from top, tweak value


                    Timber.i("Camera moved to ${loc.latitude}, ${loc.longitude} with zoom $zoomLevel")
                    println("Camera moved to ${loc.latitude}, ${loc.longitude} with zoom $zoomLevel")


                    // ✅ Enable continuous tracking after first move
//                    val locationComponent = mapLibreMap.locationComponent
//                    locationComponent.isLocationComponentEnabled = true
//                    locationComponent.cameraMode = CameraMode.TRACKING
//                    locationComponent.renderMode = RenderMode.GPS


                } else {
                    attempts++
                    if (attempts <= maxRetries) {
                        // try again after delay
                        binding.mapView.postDelayed({ tryMove() }, delayMs)
                    } else {
                        Timber.w("moveCamera: gave up after $attempts attempts; lastKnownLocation still null")
                        println("moveCamera: gave up after $attempts attempts; lastKnownLocation still null")
                    }
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Exception while trying to move camera")
                println("Exception while trying to move camera: ${ex.localizedMessage}")
            }
        }
        tryMove()
    }

    // ProgressChangeListener implementation
    override fun onProgressChange(location: Location, routeProgress: RouteProgress) {

        if (simulateRoute && !isSimulatingOffRoute && isNavigationRunning) {
            isSimulatingOffRoute = true
            Handler(Looper.getMainLooper()).postDelayed({

                val offRoutePoint = Point.fromLngLat(
                    location.longitude+ 0.01,
                    location.latitude + 0.01
                )

                val offRouteLocation = Location(
                    latitude = offRoutePoint.latitude(),
                    longitude = offRoutePoint.longitude(),
                    provider = "test" // provider goes here
                )

                (navigation.locationEngine as? ReplayRouteLocationEngine)
                    ?.moveTo(offRoutePoint)
            }, 5000)
        }

        updateRouteLine(location)
        updateNavigationUI()

        // Keep the camera updated with the current location during navigation
        if (isNavigationRunning ) {
            runOnUiThread {
                val position = CameraPosition.Builder()
                    .target(LatLng(location.latitude, location.longitude))
                    .zoom(NAVIGATION_ZOOM_LEVEL)
                    .bearing(location.bearing?.toDouble() ?: 0.0)
                    .tilt(45.0)
                    .build()
                mapLibreMap.easeCamera(CameraUpdateFactory.newCameraPosition(position), 1000)
            }
        }


        // Update ETA periodically or when significant changes occur
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEtaUpdateTime > etaUpdateInterval) {
            // Calculate remaining time based on progress
            val remainingDistance = routeProgress.distanceRemaining

            val lastLocation: Location? = null
            val lastLocationTime: Long = 0

            val currentSpeed = if (lastLocation != null && lastLocationTime > 0) {
                val timeDelta = (System.currentTimeMillis() - lastLocationTime) / 1000.0 // seconds
                val distance = TurfMeasurement.distance(
                    Point.fromLngLat(lastLocation!!.longitude, lastLocation!!.latitude),
                    Point.fromLngLat(location.longitude, location.latitude),
                    TurfConstants.UNIT_METERS
                )
                if (timeDelta > 0) distance / timeDelta else 0.0
            } else {
                0.0
            }


            if (currentSpeed > 1) { // Only update if moving
                val remainingTimeSeconds = remainingDistance / currentSpeed
                trafficAdjustedEta = remainingTimeSeconds
                updateEtaDisplay()
                lastEtaUpdateTime = currentTime
            }
        }

    }

    // NavigationEventListener implementation
    override fun onRunning(running: Boolean) {
        isNavigationRunning = running
        Timber.i("Navigation running: $running")

        // Reset simulation flag when navigation stops
        if (!running) {
            isSimulatingOffRoute = false
        }


        // Update UI based on navigation state
        runOnUiThread {
            if (running) {
                binding.startRouteButton.visibility = View.INVISIBLE
                binding.startRouteLayout.visibility = View.GONE
                binding.simulateRouteSwitch.visibility = View.GONE

                mapLibreMap.locationComponent.cameraMode = CameraMode.TRACKING_GPS
                locationComponent?.renderMode = RenderMode.GPS

                mapLibreMap.setPadding(0, 1000, 0, 50)

                // Use navigation zoom level
                moveCameraToLastKnownLocationOrWait(NAVIGATION_ZOOM_LEVEL)

                // // Keep camera tracking north
                // val navigationCamera = CameraPosition.Builder()
                //     .target(mapLibreMap.locationComponent.lastKnownLocation?.let {
                //         LatLng(it.latitude, it.longitude)
                //     })
                //     .zoom(17.5)      // closer zoom like Google Maps driving
                //     .tilt(45.0)      // 3D angle
                //     .bearing(0.0)    // keep north-up (set to it.bearing if you want route-up)
                //     .build()

                // mapLibreMap.animateCamera(CameraUpdateFactory.newCameraPosition(navigationCamera))


//                mapLibreMap.setPadding(0, 1000, 0, 0) // 400px from top, tweak value


                // Detect when user moves map manually
                mapLibreMap.addOnCameraMoveStartedListener { reason ->
                    when (reason) {
                        MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE -> {
                            Timber.d("User moved map manually")
                            mapLibreMap.locationComponent.cameraMode = CameraMode.NONE

                            showRecenterButton()

                        }
                        MapLibreMap.OnCameraMoveStartedListener.REASON_DEVELOPER_ANIMATION,
                        MapLibreMap.OnCameraMoveStartedListener.REASON_API_ANIMATION -> {
                            Timber.d("Map is moving programmatically")
                        }
                    }
                }


                // You might want to show a stop button instead
            } else {
                binding.startRouteButton.visibility = View.VISIBLE
                binding.startRouteLayout.visibility = View.VISIBLE
                binding.simulateRouteSwitch.visibility = View.VISIBLE

                mapLibreMap.setPadding(0, 0, 0, 0)

                // Reset to initial zoom when navigation stops
                moveCameraToLastKnownLocationOrWait(INITIAL_ZOOM_LEVEL)

            }
        }
    }

    // MilestoneEventListener implementation
    override fun onMilestoneEvent(routeProgress: RouteProgress, instruction: String?, milestone: Milestone) {
//        Timber.i("Milestone event: $instruction")
        // Show instructions to the user
        instruction?.let {
            displayInstruction(it)
        }
    }

    // OffRouteListener implementation
    override fun userOffRoute(location: Location) {

        if (!isNavigationRunning) {
            Timber.w("Off-route detected but navigation is not running. Ignoring.")
            return
        }

        Timber.i("User is off route!")
        // Handle off-route scenario
        handleOffRoute(location)
    }

    private fun updateNavigationUI() {
        // Update distance to next turn, ETA, etc.
        runOnUiThread {

        }
    }

    private fun displayInstruction(instruction: String) {
        // Display instruction to user
        runOnUiThread {
            // Example: Update instruction text if you have a TextView for it
            // binding.instructionText.text = instruction
            // You might want to use TTS to speak the instruction
            // speakInstruction(instruction)

            // Show a snackbar with the instruction
            Snackbar.make(binding.root, instruction, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun handleOffRoute(location: Location) {
        // Recalculate route from current location
        recalculateRoute(location)
    }


    private fun recalculateRoute(location: Location) {

        if (!isNavigationRunning) {
            Timber.w("Recalculate route called but navigation is not running. Ignoring.")
            return
        }

        Timber.i("Recalculating route from current location: ${location.latitude}, ${location.longitude}")

        // Get destination from original route
        val destination = destination
        destination?.let {
            // Create a Point from the current off-route location
            val currentPoint = Point.fromLngLat(location.longitude, location.latitude)

            // Call your routing service (Valhalla) with the current location as start
            calculateRouteFromLocation(currentPoint, destination)

        }
    }

    private fun calculateRouteFromLocation(currentLocation: Point, destination: Point) {
        val origin = Point.fromLngLat(currentLocation.longitude, currentLocation.latitude)

        val requestBody = mapOf(
            "format" to "osrm",
            "costing" to "auto",
            "banner_instructions" to true,
            "voice_instructions" to true,
            "language" to language,
            "directions_options" to mapOf("units" to "kilometers"),
            "costing_options" to mapOf("auto" to mapOf("top_speed" to 130)),
            "locations" to listOf(
                mapOf("lon" to origin.longitude(), "lat" to origin.latitude(), "type" to "break"),
                mapOf("lon" to destination.longitude(), "lat" to destination.latitude(), "type" to "break")
            )
        )

        val requestBodyJson = Gson().toJson(requestBody)
        val client = OkHttpClient()
        val request = Request.Builder()
            .header("User-Agent", "MapLibre Android Navigation SDK Demo App")
            .url(getString(R.string.valhalla_url))
            .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        Timber.d("Enqueuing Valhalla route recalculation request: %s", requestBodyJson)

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Timber.e(e, "Failed to get recalculated route from Valhalla")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (it.isSuccessful) {
                            val responseBodyJson = it.body!!.string()
                            Timber.d("Valhalla recalculated route response: %s", responseBodyJson)

                            val maplibreResponse = DirectionsResponse.fromJson(responseBodyJson)
                            val assignedRoute = maplibreResponse.routes.first().copy(
                                routeOptions = RouteOptions(
                                    baseUrl = "https://mapapi.genesysmap.com/api/v1/directions/route",
                                    profile = "auto",
                                    user = "user1",
                                    accessToken = "94a5ee1ecf7c4f10a8ea7a3f3a54b3de",
                                    voiceInstructions = true,
                                    bannerInstructions = true,
                                    language = language,
                                    coordinates = listOf(origin, destination),
                                    requestUuid = "0000-0000-0000-0000"
                                )
                            )

//                            Adding ETA calculation
                            // Store base ETA (without traffic)
                            baseEta = assignedRoute.duration // This might vary based on your DirectionsRoute implementation
                            trafficAdjustedEta = baseEta
                            lastEtaUpdateTime = System.currentTimeMillis()
                            totalDistance = assignedRoute.distance
                            remainingDistance = totalDistance

                            this@ValhallaNavigationActivity.route = assignedRoute
                            // Store the entire route points for the new route
                            val encodedGeometry = assignedRoute.geometry
                            if (!encodedGeometry.isNullOrEmpty()) {
                                val fullLine = LineString.fromPolyline(encodedGeometry, 6)
                                entireRoutePoints = fullLine.coordinates()
                            }

                            // Immediately fetch traffic for this new route
                            refreshTrafficData()

                            runOnUiThread {
                                navigationMapRoute?.removeRoute()
                                navigationMapRoute?.addRoutes(maplibreResponse.routes)

                                // If navigation is running, restart it with the new route
                                if (isNavigationRunning) {
                                    locationEngine.assign(assignedRoute)
                                    navigation.startNavigation(assignedRoute)
                                    Timber.i("Navigation restarted with recalculated route")
                                }
                            }
                        } else {
                            Timber.e("Valhalla recalculated route request failed: %d - %s", it.code, it.body?.string())
                        }
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Error processing Valhalla recalculated route response")
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTrafficUpdates()
        trafficUpdateHandler?.removeCallbacksAndMessages(null)
        trafficUpdateHandler = null


        // Remove all navigation listeners to prevent memory leaks
        navigation.apply {
            removeNavigationEventListener(this@ValhallaNavigationActivity)
            removeProgressChangeListener(this@ValhallaNavigationActivity)
            removeMilestoneEventListener(this@ValhallaNavigationActivity)
            removeOffRouteListener(this@ValhallaNavigationActivity)
        }

        if (::mapLibreMap.isInitialized) {
            mapLibreMap.removeOnMapClickListener(this)
        }
        binding.mapView.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Restart traffic updates if they were active
        if (trafficLayerAdded) {
            startTrafficUpdates()
        }
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        // Stop traffic updates to save resources
        stopTrafficUpdates()
        binding.mapView.onPause()

    }

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onStop() {
        super.onStop()
        binding.mapView.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }

    private class BeginRouteInstruction : Instruction {
        override fun buildInstruction(routeProgress: RouteProgress): String = "Have a safe trip!"
    }


    private fun showRecenterButton() {
        if (binding.recenterButton.visibility != View.VISIBLE) {
            binding.recenterButton.visibility = View.VISIBLE
            binding.recenterButton.alpha = 0f
            binding.recenterButton.animate()
                .alpha(1f)
                .setDuration(300)
                .start()
        }
    }

    private fun hideRecenterButton() {
        binding.recenterButton.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { binding.recenterButton.visibility = View.GONE }
            .start()
    }

    private fun updateRouteLine(location: Location) {
        if (route == null || !::mapLibreMap.isInitialized) return

        val style = mapLibreMap.style ?: return
        // Check if geometry is available
        val encodedGeometry = route!!.geometry

        // Decode the polyline into a LineString
        val lineString = LineString.fromPolyline(encodedGeometry, 6)
        val allPoints = lineString.coordinates()

        // Current GPS point
        val currentPoint = Point.fromLngLat(location.longitude, location.latitude)

        // Find closest point index on route
        var splitIndex = 0
        var minDistance = Double.MAX_VALUE
        for (i in allPoints.indices) {
            val d = TurfMeasurement.distance(currentPoint, allPoints[i], TurfConstants.UNIT_METERS)
            if (d < minDistance) {
                minDistance = d
                splitIndex = i
            }
        }

        // Split route
        val traveledPoints = allPoints.subList(0, splitIndex+1)
        upcomingRoutePoints = allPoints.subList(splitIndex, allPoints.size) // Store upcoming points


        // Update sources
        val traveledSource = style.getSourceAs<GeoJsonSource>("traveled-source")
        traveledSource?.setGeoJson(LineString.fromLngLats(traveledPoints))

        val upcomingSource = style.getSourceAs<GeoJsonSource>("upcoming-source")
        upcomingSource?.setGeoJson(LineString.fromLngLats(upcomingRoutePoints))
        // Update remaining distance for ETA calculation
        remainingDistance = calculateRemainingDistance(upcomingRoutePoints)

        // Update traffic layer with filtered data (only upcoming route)
        if (trafficLayerAdded) {
            refreshTrafficData()
        }

    }

    private fun prepareTrafficLayer(style: Style) {
        try {
            // Check if source already exists
            if (style.getSource(trafficSourceId) == null) {
                val trafficSource = GeoJsonSource(trafficSourceId)
                style.addSource(trafficSource)
            }


            // Check if layer already exists
            if (style.getLayer(trafficLayerId) == null) {
                val trafficLayer = LineLayer(trafficLayerId, trafficSourceId).withProperties(
                    lineWidth(6f),
                    lineOpacity(0.8f)
                )
                style.addLayerAbove(trafficLayer, "upcoming-layer")
            }


            // Initially set to invisible (will be toggled by switch)
            style.getLayerAs<LineLayer>(trafficLayerId)?.setProperties(
                visibility(Property.NONE)
            )


        } catch (e: Exception) {
            Timber.e(e, "Error preparing HERE traffic layer: ${e.message}")
        }
    }


    private fun addTrafficLayer() {
        Timber.i("addTrafficLayer called, trafficLayerAdded: $trafficLayerAdded")
        if (trafficLayerAdded) return

        mapLibreMap.style?.let { style ->
            try {
                // Make traffic layer visible
                val trafficLayer = style.getLayerAs<LineLayer>(trafficLayerId)
                if (trafficLayer != null) {
                    trafficLayer.setProperties(visibility(Property.VISIBLE))
                    trafficLayerAdded = true

                    // Fetch initial traffic data
                    refreshTrafficData()
                    startTrafficUpdates()

                    Timber.i("HERE traffic layer added and visible")
                } else {
                    Timber.e("Traffic layer not found in style")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error adding HERE traffic layer")
            }
        }
    }

    private fun removeTrafficLayer() {
        Timber.i("removeTrafficLayer called, trafficLayerAdded: $trafficLayerAdded")
        if (!trafficLayerAdded) return

        mapLibreMap.style?.let { style ->
            try {
                // Hide traffic layer
                val trafficLayer = style.getLayerAs<LineLayer>(trafficLayerId)
                trafficLayer?.setProperties(visibility(Property.NONE))
                trafficLayerAdded = false
                Timber.i("HERE traffic layer removed")
            } catch (e: Exception) {
                Timber.e(e, "Error removing HERE traffic layer")
            }
        }
    }

    private fun startTrafficUpdates() {
        stopTrafficUpdates() // Ensure no previous runnables are running

        trafficUpdateRunnable = Runnable {
            refreshTrafficData()
            trafficUpdateHandler?.postDelayed(trafficUpdateRunnable!!, trafficUpdateInterval)
        }

        trafficUpdateHandler?.postDelayed(trafficUpdateRunnable!!, trafficUpdateInterval)
    }

    private fun stopTrafficUpdates() {
        trafficUpdateHandler?.removeCallbacksAndMessages(null)
        trafficUpdateRunnable = null
    }

    private fun fetchTrafficDataFromGenesys() {
        // Use the current route's geometry instead of hardcoded shape
        val routeGeometry = route?.geometry
        if (routeGeometry.isNullOrEmpty()) {
            Timber.i("No route geometry available for traffic data")
            return
        }
        val url = "https://api.genesysmap.com/api/v1/flutter-services/routes/traffic" +
                "?access_key=2b566f8e-af80-41fa-b59f-d8bbb39d532c" +
                "&api_key=22faa6f6b34d4cfe892ebd930ee2b4cc"

        // Build JSON safely with Gson to avoid malformed JSON from special chars
        val payload = mapOf(
            "Shape" to routeGeometry,
            "Radius" to 50
        )
        val requestBodyJson = Gson().toJson(payload)


        val client = OkHttpClient()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = requestBodyJson.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Timber.i( "Failed to fetch traffic data from Genesys API")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (it.isSuccessful) {
                            val responseBody = it.body?.string()
                            Timber.d("Genesys Traffic API response: $responseBody")

                            responseBody?.let { body ->
                                // TODO: parse JSON (jamFactorSegments, tripTime, etc.)
                                parseGenesysTrafficData(body)
                            }
                        } else {
                            Timber.e("Genesys Traffic API request failed: ${it.code} - ${it.message}")
                            // Log the response body for more details
                            Timber.e("Response body: ${it.body?.string()}")

                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error processing Genesys traffic response")
                }
            }
        })
    }

    private fun parseGenesysTrafficData(jsonData: String) {
        try {
            Timber.i("Parsing traffic data: $jsonData")

            val jsonObject = JsonParser.parseString(jsonData).asJsonObject

            if (!jsonObject.has("jamFactorSegments")) {
                Timber.i("Invalid traffic data format: no jamFactorSegments found")
                return
            }

            // Extract trip time (in seconds)
            val tripTime = jsonObject.get("tripTime")?.asDouble ?: 0.0
            trafficAdjustedEta = tripTime
            lastEtaUpdateTime = System.currentTimeMillis()

            // Update ETA display
            updateEtaDisplay()

            val jamFactorSegments = jsonObject
                .getAsJsonObject("jamFactorSegments")
                .getAsJsonArray("features")

            val features = mutableListOf<Feature>()

            for (featureElement in jamFactorSegments) {
                val featureObj = featureElement.asJsonObject

                // Check if geometry exists
                if (!featureObj.has("geometry")) {
                    Timber.w("Feature missing geometry, skipping")
                    continue
                }


                val geometry = featureObj.getAsJsonObject("geometry")
                val coordinatesArray = geometry.getAsJsonArray("coordinates")

                val coordinates = mutableListOf<Point>()
                for (coord in coordinatesArray) {
                    val lng = coord.asJsonArray[0].asDouble
                    val lat = coord.asJsonArray[1].asDouble
                    coordinates.add(Point.fromLngLat(lng, lat))
                }

                if (coordinates.size >= 2) {
                    val properties = featureObj.getAsJsonObject("properties")
                    val jamFactor = properties.get("jamFactor")?.asDouble ?: 0.0

                    // Determine color based on jam factor scale (0–10, like HERE API)
                    val color = when {
                        jamFactor >= 7 -> Color.RED       // heavy congestion
                        jamFactor >= 4 -> Color.YELLOW    // moderate
                        else -> Color.BLUE              // free flow
                    }

                    val lineString = LineString.fromLngLats(coordinates)
                    val feature = Feature.fromGeometry(lineString)

                    feature.addNumberProperty("jamFactor", jamFactor)
                    feature.addNumberProperty("color", color)

                    features.add(feature)
                }
            }

            // Update map layer on UI thread
            runOnUiThread {
                if (features.isNotEmpty()) {
                    updateTrafficLayer(FeatureCollection.fromFeatures(features))
                    Timber.i("Traffic layer updated with ${features.size} features")
                } else {
                    Timber.w("No traffic features found in response")
                }
            }

            // Optional: you can also extract trip stats
            val tripLength = jsonObject.get("tripLength")?.asDouble ?: 0.0
            Timber.d("Trip stats: time=${tripTime}s, length=${tripLength}km")

        } catch (e: Exception) {
            Timber.e(e, "Error parsing Genesys traffic data")
        }
    }


    private fun updateTrafficLayer(featureCollection: FeatureCollection) {
        mapLibreMap.style?.let { style ->

            // Filter traffic features to only those that intersect with upcoming route
            val pointsToUse = if (upcomingRoutePoints.isNotEmpty()) upcomingRoutePoints else entireRoutePoints

            // If we have no points to filter with, show all traffic
            if (pointsToUse.isEmpty()) {
                val trafficSource = style.getSourceAs<GeoJsonSource>(trafficSourceId)
                trafficSource?.setGeoJson(featureCollection)
                return
            }


            // Filter traffic features to only those that intersect with upcoming route
            val filteredFeatures = featureCollection.features()?.filter { feature ->
                val geometry = feature.geometry()
                if (geometry is LineString) {
                    geometry.coordinates().any { trafficPoint ->
                        upcomingRoutePoints.any { routePoint ->
                            TurfMeasurement.distance(
                                trafficPoint,
                                routePoint,
                                TurfConstants.UNIT_METERS
                            ) < 50 // Adjust tolerance as needed
                        }
                    }
                } else {
                    false
                }
            }

            val filteredCollection = FeatureCollection.fromFeatures(filteredFeatures ?: emptyList())


            val trafficSource = style.getSourceAs<GeoJsonSource>(trafficSourceId)
            trafficSource?.setGeoJson(filteredCollection)

            val trafficLayer = style.getLayerAs<LineLayer>(trafficLayerId)
            trafficLayer?.setProperties(
                lineColor(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.get("jamFactor"),
                        Expression.stop(0.0, Expression.color(Color.BLUE)),   // Free flow
                        Expression.stop(2.0, Expression.color(Color.BLUE)),
                        Expression.stop(6.0, Expression.color(Color.YELLOW)), // Medium congestion
                        Expression.stop(10.0, Expression.color(Color.RED)),    // Heavy congestion
//                        Expression.stop(10.0, Expression.color(Color.BLACK))  // Complete jam
                    )
                )
            )
        }
    }

    private fun refreshTrafficData() {
        if (trafficLayerAdded && route != null) {
            fetchTrafficDataFromGenesys()
        }
    }

    // Add this method to format time
    private fun formatEta(seconds: Double): String {
        val hours = (seconds / 3600).toInt()
        val minutes = ((seconds % 3600) / 60).toInt()

        return if (hours > 0) {
            String.format("%dh %dm", hours, minutes)
        } else {
            String.format("%dm", minutes)
        }
    }

    // Add this method to update ETA display
    private fun updateEtaDisplay() {
        runOnUiThread {

            if (trafficAdjustedEta > 0) {
                // Calculate arrival time
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.SECOND, trafficAdjustedEta.toInt())
                val arrivalTime = SimpleDateFormat("h:mm a", Locale.getDefault()).format(calendar.time)

                // Format distance
                val formattedDistance = if (remainingDistance < 1000) {
                    String.format(Locale.getDefault(), "%.0f m", remainingDistance)
                } else {
                    String.format(Locale.getDefault(), "%.1f km", remainingDistance / 1000)
                }

                // Format time remaining
                val hours = (trafficAdjustedEta / 3600).toInt()
                val minutes = ((trafficAdjustedEta % 3600) / 60).toInt()

                val timeRemaining = if (hours > 0) {
                    String.format(Locale.getDefault(), "%dh %dm", hours, minutes)
                } else {
                    String.format(Locale.getDefault(), "%dm", minutes)
                }


//            if (trafficAdjustedEta > 0) {
                binding.etaTextView.text = String.format(
                    Locale.getDefault(),
                    "ETA: %s (%s away)\n%s remaining",
                    arrivalTime,
                    formattedDistance,
                    timeRemaining
                )

                binding.etaTextView.visibility = View.VISIBLE
            } else if (baseEta > 0) {
                // Calculate arrival time
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.SECOND, baseEta.toInt())
                val arrivalTime = SimpleDateFormat("h:mm a", Locale.getDefault()).format(calendar.time)

                // Format distance
                val formattedDistance = if (remainingDistance < 1000) {
                    String.format(Locale.getDefault(), "%.0f m", remainingDistance)
                } else {
                    String.format(Locale.getDefault(), "%.1f km", remainingDistance / 1000)
                }

                // Format time remaining
                val hours = (baseEta / 3600).toInt()
                val minutes = ((baseEta % 3600) / 60).toInt()

                val timeRemaining = if (hours > 0) {
                    String.format(Locale.getDefault(), "%dh %dm", hours, minutes)
                } else {
                    String.format(Locale.getDefault(), "%dm", minutes)
                }

                // Update ETA text view
                binding.etaTextView.text = String.format(
                    Locale.getDefault(),
                    "ETA: %s (%s away)\n%s remaining",
                    arrivalTime,
                    formattedDistance,
                    timeRemaining
                )
                binding.etaTextView.visibility = View.VISIBLE
            } else
            {
                binding.etaTextView.visibility = View.GONE
            }
        }
    }

    private fun calculateRemainingDistance(points: List<Point>): Double {
        var distance = 0.0
        for (i in 0 until points.size - 1) {
            distance += TurfMeasurement.distance(
                points[i],
                points[i + 1],
                TurfConstants.UNIT_METERS
            )
        }
        return distance
    }

}