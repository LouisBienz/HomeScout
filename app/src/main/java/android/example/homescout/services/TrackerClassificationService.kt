package android.example.homescout.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.example.homescout.R
import android.example.homescout.database.BLEDevice
import android.example.homescout.repositories.MainRepository
import android.example.homescout.repositories.TrackingPreferencesRepository
import android.example.homescout.ui.main.MainActivity
import android.example.homescout.utils.Constants.ACTION_SHOW_SETTINGS_FRAGMENT
import android.example.homescout.utils.Constants.ACTION_START_TRACKER_CLASSIFICATION_SERVICE
import android.example.homescout.utils.Constants.ACTION_STOP_TRACKER_CLASSIFICATION_SERVICE
import android.example.homescout.utils.Constants.CHANNEL_ID_TRACKER_CLASSIFICATION
import android.example.homescout.utils.Constants.INTERVAL_TRACKER_CLASSIFICATION
import android.example.homescout.utils.Constants.NOTIFICATION_CHANNEL_TRACKER_CLASSIFICATION
import android.example.homescout.utils.Constants.NOTIFICATION_ID_TRACKER_CLASSIFICATION
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class TrackerClassificationService : LifecycleService() {


    private val handler = Handler(Looper.getMainLooper())

    private var isServiceRunning = false

    private var distance : Float? = null
    private var timeinMin : Float? = null
    private var occurrences : Float? = null

    @Inject
    lateinit var trackingPreferencesRepository: TrackingPreferencesRepository

    @Inject
    lateinit var mainRepository: MainRepository

    private var hashMapBleDevicesSortedByTime: HashMap<String, MutableList<BLEDevice>?> = HashMap()

    private var timeStamp = 50000000000000

    // LIFECYCLE FUNCTIONS
    override fun onCreate() {
        super.onCreate()

        clearBleDeviceTable()
        observeTrackingPreferences()
        createBleDeviceHashMapWithMacAsKeyOrderedDescByTime()


    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        intent?.let {

            when (it.action) {

                ACTION_START_TRACKER_CLASSIFICATION_SERVICE -> {
                    if (!isServiceRunning) {
                        Timber.i("Start Service")
                        isServiceRunning = true
                        startForegroundService()
                    }
                }

                ACTION_STOP_TRACKER_CLASSIFICATION_SERVICE -> {
                    Timber.i("Stop Service")
                    isServiceRunning = false
                    stopSelf()
                }
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }


    // FUNCTIONS USED IN LIFECYCLE (for code readability)
    private fun observeTrackingPreferences() {
        trackingPreferencesRepository.distance.asLiveData().observe(this) { distance = it }
        trackingPreferencesRepository.timeInMin.asLiveData().observe(this) { timeinMin = it }
        trackingPreferencesRepository.occurrences.asLiveData().observe(this) {
            occurrences = it
        }
    }

    private fun createBleDeviceHashMapWithMacAsKeyOrderedDescByTime() {

        mainRepository.getAllBLEDevicesSortedByTimestamp().observe(this) { bleDevices ->

            deleteBLEDevicesOlderThanTwoHours()

            hashMapBleDevicesSortedByTime.clear()

            for (bleDevice in bleDevices) {
                if (hashMapBleDevicesSortedByTime.containsKey(bleDevice.macAddress)) {

                    hashMapBleDevicesSortedByTime[bleDevice.macAddress]!!.add(bleDevice)

                } else {
                    hashMapBleDevicesSortedByTime[bleDevice.macAddress!!]=
                        mutableListOf(bleDevice)
                }
            }
        }
    }


    private fun startForegroundService() {

        startTrackerClassification()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        createNotificationChannel(notificationManager)

        val notificationBuilder = NotificationCompat.Builder(this,
            CHANNEL_ID_TRACKER_CLASSIFICATION
        )
            .setAutoCancel(false)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_protect_48px)
            .setContentTitle("Home Scout")
            .setContentText("Tracker classification is running.")
            .setContentIntent(getMainActivityPendingIntent())

        startForeground(NOTIFICATION_ID_TRACKER_CLASSIFICATION, notificationBuilder.build())
    }

    private fun createNotificationChannel(notificationManager: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_ID_TRACKER_CLASSIFICATION,
            NOTIFICATION_CHANNEL_TRACKER_CLASSIFICATION,
            IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun getMainActivityPendingIntent() = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).also {
            it.action = ACTION_SHOW_SETTINGS_FRAGMENT
        },
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun startTrackerClassification() {

        if (isServiceRunning) {

            hashMapBleDevicesSortedByTime.let{ hashMapBleDevicesSortedByTime ->

                for (key in hashMapBleDevicesSortedByTime.keys) {

                    // get all scans of the same mac address
                    val scansOfThisDevice = hashMapBleDevicesSortedByTime[key]!!

                    // check if more than one scan exists or it has less scans than defined by user
                    if ( scansOfThisDevice.size == 1 || scansOfThisDevice.size < occurrences!!) {continue}


                    // check if the tracker follows according to time defined by user
                    val youngestScanTime = scansOfThisDevice.first().timestampInMilliSeconds
                    val oldestScanTime = scansOfThisDevice.last().timestampInMilliSeconds
                    val diffBetweenFirstAndLastScan = youngestScanTime - oldestScanTime
                    val timeThresholdInMillis = timeinMin!! * 60000
//                    if (diffBetweenFirstAndLastScan < timeThresholdInMillis) { continue }


                    // check if the tracker follows according to distance defined by user
                    var distanceFollowed = 0.0
                    val secondLastIndex = scansOfThisDevice.size - 2
                    for (i in 0..secondLastIndex) {

                        val currentLocation = Location("currentLocation").apply {
                            latitude = scansOfThisDevice[i].lat
                            longitude = scansOfThisDevice[i].lng
                        }

                        val nextLocation = Location("nextLocation").apply {
                            latitude = scansOfThisDevice[i + 1].lat
                            longitude = scansOfThisDevice[i + 1].lng
                        }

                        val distanceBetweenTwoLocations = currentLocation.distanceTo(nextLocation)
                        distanceFollowed += distanceBetweenTwoLocations
                    }

                    if (distanceFollowed < distance!!) { continue }

                    // FOUND A MALICIOUS TRACKER ACCORDING TO USER DEFINED PARAMETERS

                    val macAddress = key
                    val type = scansOfThisDevice.first().type
                    Toast.makeText(
                        applicationContext,
                        "Type: $type, Mac: $macAddress",
                        Toast.LENGTH_SHORT
                    ).show()

                }

            }



//            val testBLEDevice = BLEDevice("testLocation", timeStamp, 47.39214386976374 ,8.525952486036404, "Test")
//            timeStamp++
//            val testBLEDevice2 = BLEDevice("testLocation", timeStamp, 47.39207757469091, 8.526133440055352, "Test")
//            timeStamp++
//
//            insertBLEDevice(testBLEDevice)
//            insertBLEDevice(testBLEDevice2)



            handler.postDelayed({
                startTrackerClassification()
            }, INTERVAL_TRACKER_CLASSIFICATION)
        }
    }

    private fun deleteBLEDevicesOlderThanTwoHours() {
        lifecycleScope.launch{
            mainRepository.deleteBLEDevicesOlderThanTwoHours()
        }
    }

    private fun  insertBLEDevice(bleDevice: BLEDevice) {
        lifecycleScope.launch {
            mainRepository.insertBLEDevice(bleDevice)
        }
    }

    private fun clearBleDeviceTable() {
        lifecycleScope.launch{
            mainRepository.clearBleDeviceTable()
        }
    }

}