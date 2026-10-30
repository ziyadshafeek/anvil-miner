package io.github.j3r0nim0.anvil

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * ForegroundService that owns the XMRig process.
 * Wake lock, thermal / battery / charging / Wi-Fi / quiet-hours / no-internet
 * pause, persistent notification with Pause / Resume / Stop.
 */
class MiningService : Service() {

    companion object {
        private const val TAG = "MiningService"

        const val ACTION_START = "io.github.j3r0nim0.anvil.START"
        const val ACTION_STOP = "io.github.j3r0nim0.anvil.STOP"
        const val ACTION_PAUSE = "io.github.j3r0nim0.anvil.PAUSE"
        const val ACTION_RESUME = "io.github.j3r0nim0.anvil.RESUME"
        const val ACTION_RECONFIGURE = "io.github.j3r0nim0.anvil.RECONFIGURE"
        const val ACTION_RECALIBRATE = "io.github.j3r0nim0.anvil.RECALIBRATE"

        const val EXTRA_WALLET = "wallet"
        const val EXTRA_THREADS_PERCENT = "threads_percent"
        const val EXTRA_THERMAL_THRESHOLD = "thermal_threshold"
        const val EXTRA_CHARGING_ONLY = "charging_only"
        const val EXTRA_WIFI_ONLY = "wifi_only"
        const val EXTRA_QUIET_ON = "quiet_on"
        const val EXTRA_QUIET_START = "quiet_start"
        const val EXTRA_QUIET_END = "quiet_end"

        const val DEFAULT_POOL_HOST = "gulf.moneroocean.stream"
        const val DEFAULT_POOL_PORT = 20032
        const val DEFAULT_POOL_TLS = true

        /** MoneroOcean VarDiff start (`wallet+N`). 0 = pool auto. */
        const val START_DIFFICULTY = 30000

        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "xmrig_mining"

        private const val BATTERY_PAUSE_TENTHS = 400   // 40°C
        private const val BATTERY_RESUME_TENTHS = 370  // 37°C

        private const val NO_INTERNET_GRACE_SECS = 60
        private const val WIFI_HARD_STOP_MS = 5 * 60 * 1000L
        private const val QUIET_POLL_MS = 30_000L
        private const val NOTIF_STATS_MS = 5_000L

        @Volatile
        var isRunning = false
            private set

        /** True while start/stop is in flight — UI should ignore extra taps. */
        @Volatile
        var isBusy = false
            private set

        private const val WORKER_PREFS = "xmrig_worker"
        private const val WORKER_KEY = "worker_name"

        fun poolWorkerName(context: Context): String {
            val prefs = context.getSharedPreferences(WORKER_PREFS, Context.MODE_PRIVATE)
            prefs.getString(WORKER_KEY, null)?.let { return it }
            val name = "AX-" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)
            prefs.edit().putString(WORKER_KEY, name).apply()
            return name
        }

        fun pending(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, MiningService::class.java).setAction(action)
            return PendingIntent.getService(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var xmrig: XMRigManager? = null
    private var thermal: ThermalMonitor? = null

    private var currentWallet = ""
    private var currentPoolUrl = "$DEFAULT_POOL_HOST:$DEFAULT_POOL_PORT"
    private var currentPoolTls = DEFAULT_POOL_TLS
    private var currentThreadsPercent = 50
    private var currentThermalThreshold = 65

    private var thermalPaused = false
    private var batteryThermalPaused = false
    private var chargingPaused = false
    private var userPaused = false
    private var wifiPaused = false
    private var quietPaused = false
    private var netPaused = false
    private var chargingOnly = true
    private var wifiOnly = false
    private var quietOn = false
    private var quietStart = 22 * 60
    private var quietEnd = 7 * 60
    private var lastBatteryTempC = 0

    private var ioThread: HandlerThread? = null
    private var io: Handler? = null
    private val main = Handler(Looper.getMainLooper())

    private var noInternetLeft = -1
    private val noInternetTick = object : Runnable {
        override fun run() {
            if (!isRunning || netPaused) return
            if (hasInternet()) {
                noInternetLeft = -1
                MiningState.noInternetSecondsLeft = -1
                return
            }
            if (noInternetLeft < 0) noInternetLeft = NO_INTERNET_GRACE_SECS
            noInternetLeft -= 1
            MiningState.noInternetSecondsLeft = noInternetLeft.coerceAtLeast(0)
            if (noInternetLeft <= 0) {
                netPaused = true
                noInternetLeft = -1
                MiningState.noInternetSecondsLeft = -1
                pauseHashing("Paused — no internet")
                publishPauseState()
            } else {
                main.postDelayed(this, 1000)
            }
        }
    }

    private val quietPoll = object : Runnable {
        override fun run() {
            if (!isRunning) return
            applyQuietHours()
            main.postDelayed(this, QUIET_POLL_MS)
        }
    }

    private val notifTick = object : Runnable {
        override fun run() {
            if (!isRunning) return
            updateNotification(currentPauseLabel())
            main.postDelayed(this, NOTIF_STATS_MS)
        }
    }

    private var wifiHardStopPosted = false
    private val wifiHardStop = Runnable {
        if (isRunning && wifiOnly && wifiPaused) {
            Log.i(TAG, "Wi-Fi-only: hard-stop after grace")
            isBusy = true
            io?.post {
                tearDown()
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        promoteForeground("Starting…")
        val thread = HandlerThread("mining-io").also { it.start() }
        ioThread = thread
        io = Handler(thread.looper)
        MiningState.cpuThermalReadable = ThermalMonitor.isCpuThermalReadable()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        when (intent.action) {
            ACTION_START -> {
                val wallet = intent.getStringExtra(EXTRA_WALLET) ?: run {
                    Log.e(TAG, "START missing wallet")
                    return START_NOT_STICKY
                }
                applyExtras(intent)
                val threads = intent.getIntExtra(EXTRA_THREADS_PERCENT, 50)
                val threshold = intent.getIntExtra(EXTRA_THERMAL_THRESHOLD, 65)
                startMining(wallet, threads, threshold)
            }
            ACTION_PAUSE -> pauseByUser()
            ACTION_RESUME -> resumeByUser()
            ACTION_STOP -> {
                isBusy = true
                io?.post {
                    tearDown()
                    stopSelf()
                } ?: run {
                    tearDown()
                    stopSelf()
                }
            }
            ACTION_RECONFIGURE -> reconfigure(intent)
            ACTION_RECALIBRATE -> recalibrate()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        io?.removeCallbacksAndMessages(null)
        main.removeCallbacksAndMessages(null)
        tearDown()
        ioThread?.quitSafely()
        ioThread = null
        io = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun applyExtras(intent: Intent) {
        chargingOnly = intent.getBooleanExtra(EXTRA_CHARGING_ONLY, true)
        wifiOnly = intent.getBooleanExtra(EXTRA_WIFI_ONLY, false)
        quietOn = intent.getBooleanExtra(EXTRA_QUIET_ON, false)
        quietStart = intent.getIntExtra(EXTRA_QUIET_START, 22 * 60)
        quietEnd = intent.getIntExtra(EXTRA_QUIET_END, 7 * 60)
    }

    private fun startMining(
        wallet: String,
        threadsPercent: Int,
        thermalThreshold: Int,
    ) {
        if (isRunning || isBusy) {
            Log.i(TAG, "startMining ignored (running=$isRunning busy=$isBusy)")
            return
        }
        isBusy = true
        isRunning = true

        currentWallet = wallet
        currentThreadsPercent = threadsPercent
        currentThermalThreshold = thermalThreshold
        currentPoolUrl = "$DEFAULT_POOL_HOST:$DEFAULT_POOL_PORT"
        currentPoolTls = DEFAULT_POOL_TLS
        MiningState.reset()
        MiningState.cpuThermalReadable = ThermalMonitor.isCpuThermalReadable()
        registerChargingReceiver()
        registerNetworkCallback()
        promoteForeground("Starting…")

        thermal = ThermalMonitor(thermalThreshold).apply {
            onTemperature = { tempC ->
                when {
                    tempC > 0 -> {
                        MiningState.tempCelsius = tempC
                        MiningState.tempSource = "cpu"
                    }
                    lastBatteryTempC > 0 -> {
                        MiningState.tempCelsius = lastBatteryTempC
                        MiningState.tempSource = "battery"
                    }
                    else -> {
                        MiningState.tempCelsius = 0
                        MiningState.tempSource = ""
                    }
                }
                xmrig?.emitStats()
            }
            onOverheat = {
                thermalPaused = true
                pauseHashing("Paused — CPU too hot")
                publishPauseState()
            }
            onCooledDown = {
                thermalPaused = false
                tryResumeHashing()
                publishPauseState()
            }
            start()
        }

        val startWallet = wallet
        val startThreads = threadsPercent
        io?.post {
            if (!isRunning) {
                isBusy = false
                Log.i(TAG, "startMining deferred — stop was requested; aborting")
                return@post
            }
            try {
                xmrig?.stop()
                xmrig = newXmrig().apply { start(startWallet, startThreads) }
                main.post {
                    evaluateNetworkNow()
                    applyQuietHours()
                    applyChargingNow()
                    if (anyConstraintPause()) {
                        pauseHashing(currentPauseLabel())
                        publishPauseState()
                        Log.i(TAG, "Mining started but immediately paused")
                    } else {
                        acquireWakeLock()
                        updateNotification("Mining")
                        Log.i(TAG, "Mining started")
                    }
                    main.post(quietPoll)
                    main.post(notifTick)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "XMRig start failed: ${e.message}", e)
                tearDown()
                stopSelf()
            } finally {
                isBusy = false
            }
        } ?: run {
            isBusy = false
        }
    }

    private fun reconfigure(intent: Intent) {
        if (!isRunning) return
        val wallet = intent.getStringExtra(EXTRA_WALLET) ?: currentWallet
        val threads = intent.getIntExtra(EXTRA_THREADS_PERCENT, currentThreadsPercent)
        val threshold = intent.getIntExtra(EXTRA_THERMAL_THRESHOLD, currentThermalThreshold)
        val prevCharging = chargingOnly
        val prevWifi = wifiOnly
        applyExtras(intent)
        currentThermalThreshold = threshold
        thermal?.updateThreshold(threshold)

        if (!prevCharging && chargingOnly) applyChargingNow()
        if (prevCharging && !chargingOnly && chargingPaused) {
            chargingPaused = false
            tryResumeHashing()
            publishPauseState()
        }

        if (prevWifi != wifiOnly) {
            if (!wifiOnly && wifiPaused) {
                wifiPaused = false
                cancelWifiHardStop()
                tryResumeHashing()
                publishPauseState()
            } else {
                evaluateNetworkNow()
            }
        }

        applyQuietHours()

        if (wallet != currentWallet || threads != currentThreadsPercent) {
            currentWallet = wallet
            currentThreadsPercent = threads
            io?.post {
                xmrig?.reconfigure(wallet, threads)
                if (anyConstraintPause()) xmrig?.pause()
            }
        }
        publishPauseState()
        updateNotification(currentPauseLabel())
    }

    private fun recalibrate() {
        if (!isRunning || isBusy) return
        isBusy = true
        io?.post {
            try {
                xmrig?.stop()
                XMRigManager.clearAlgoCache(this)
                xmrig = newXmrig().apply { start(currentWallet, currentThreadsPercent) }
                if (anyConstraintPause()) xmrig?.pause()
            } finally {
                isBusy = false
            }
        }
    }

    private fun promoteForeground(statusText: String) {
        try {
            val notif = buildNotification(statusText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
        }
    }

    private fun newXmrig() = XMRigManager(
        this, currentPoolUrl, currentPoolTls, poolWorkerName(this), START_DIFFICULTY,
    )

    @Synchronized
    private fun tearDown() {
        main.removeCallbacks(noInternetTick)
        main.removeCallbacks(quietPoll)
        main.removeCallbacks(notifTick)
        cancelWifiHardStop()
        xmrig?.stop()
        xmrig = null
        thermal?.stop()
        thermal = null
        releaseWakeLock()
        safeUnregisterReceiver()
        unregisterNetworkCallback()
        isRunning = false
        isBusy = false
        thermalPaused = false
        batteryThermalPaused = false
        chargingPaused = false
        userPaused = false
        wifiPaused = false
        quietPaused = false
        netPaused = false
        lastBatteryTempC = 0
        noInternetLeft = -1
        MiningState.reset()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
        Log.i(TAG, "Mining stopped")
    }

    private var chargingReceiverRegistered = false

    private val chargingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val batteryTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            if (batteryTemp > 0) {
                lastBatteryTempC = batteryTemp / 10
                MiningState.batteryTempC = lastBatteryTempC
                if (MiningState.tempSource != "cpu" || MiningState.tempCelsius <= 0) {
                    MiningState.tempCelsius = lastBatteryTempC
                    MiningState.tempSource = "battery"
                }
            }

            if (batteryTemp >= BATTERY_PAUSE_TENTHS && !batteryThermalPaused) {
                batteryThermalPaused = true
                pauseHashing("Paused — battery too hot")
                Log.w(TAG, "Battery too hot: ${batteryTemp / 10}°C — pausing")
                publishPauseState()
            } else if (batteryTemp <= BATTERY_RESUME_TENTHS && batteryThermalPaused) {
                batteryThermalPaused = false
                tryResumeHashing()
                Log.i(TAG, "Battery cooled: ${batteryTemp / 10}°C")
                publishPauseState()
            }

            if (!chargingOnly) return
            if (!charging && !chargingPaused) {
                chargingPaused = true
                pauseHashing("Paused — not charging")
                publishPauseState()
                Log.i(TAG, "Charging-only: unplugged — paused")
            } else if (charging && chargingPaused) {
                chargingPaused = false
                tryResumeHashing()
                publishPauseState()
                Log.i(TAG, "Charging-only: plugged in")
            }
        }
    }

    private fun registerChargingReceiver() {
        if (!chargingReceiverRegistered) {
            registerReceiver(chargingReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            chargingReceiverRegistered = true
        }
    }

    private fun safeUnregisterReceiver() {
        if (chargingReceiverRegistered) {
            try {
                unregisterReceiver(chargingReceiver)
            } catch (_: Exception) {
            }
            chargingReceiverRegistered = false
        }
    }

    private fun applyChargingNow() {
        if (!chargingOnly) return
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        if (!plugged && !chargingPaused) {
            chargingPaused = true
            pauseHashing("Paused — not charging")
            publishPauseState()
        }
    }

    // ── Network ──────────────────────────────────────────────────────────────

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun connectivity(): ConnectivityManager =
        getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun hasInternet(): Boolean {
        val cm = connectivity()
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun onWifi(): Boolean {
        val cm = connectivity()
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post { evaluateNetworkNow() }
            }
            override fun onLost(network: Network) {
                main.post { evaluateNetworkNow() }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                main.post { evaluateNetworkNow() }
            }
        }
        networkCallback = cb
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivity().registerNetworkCallback(req, cb) }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching { connectivity().unregisterNetworkCallback(cb) }
    }

    private fun evaluateNetworkNow() {
        if (!isRunning) return
        val internet = hasInternet()
        val wifi = onWifi()

        if (wifiOnly && !wifi) {
            if (!wifiPaused) {
                wifiPaused = true
                pauseHashing("Paused — no Wi-Fi")
                publishPauseState()
                scheduleWifiHardStop()
            }
        } else if (wifiPaused) {
            wifiPaused = false
            cancelWifiHardStop()
            tryResumeHashing()
            publishPauseState()
        }

        if (!internet) {
            if (!netPaused && noInternetLeft < 0) {
                noInternetLeft = NO_INTERNET_GRACE_SECS
                MiningState.noInternetSecondsLeft = noInternetLeft
                main.removeCallbacks(noInternetTick)
                main.post(noInternetTick)
            }
        } else {
            main.removeCallbacks(noInternetTick)
            noInternetLeft = -1
            MiningState.noInternetSecondsLeft = -1
            if (netPaused) {
                netPaused = false
                tryResumeHashing()
                publishPauseState()
            }
        }
    }

    private fun scheduleWifiHardStop() {
        if (wifiHardStopPosted) return
        wifiHardStopPosted = true
        main.postDelayed(wifiHardStop, WIFI_HARD_STOP_MS)
    }

    private fun cancelWifiHardStop() {
        wifiHardStopPosted = false
        main.removeCallbacks(wifiHardStop)
    }

    private fun applyQuietHours() {
        if (!isRunning) return
        val inQuiet = quietOn && QuietHours.inWindow(quietStart, quietEnd)
        if (inQuiet && !quietPaused) {
            quietPaused = true
            pauseHashing("Paused — quiet hours")
            publishPauseState()
        } else if (!inQuiet && quietPaused) {
            quietPaused = false
            tryResumeHashing()
            publishPauseState()
        }
    }

    private fun pauseByUser() {
        if (!isRunning || isBusy || userPaused) return
        userPaused = true
        pauseHashing("Paused")
        publishPauseState()
        Log.i(TAG, "User pause")
    }

    private fun resumeByUser() {
        if (!isRunning || isBusy || !userPaused) return
        userPaused = false
        io?.post { tryResumeHashing() } ?: tryResumeHashing()
        publishPauseState()
        Log.i(TAG, "User resume")
    }

    private fun anyConstraintPause(): Boolean =
        userPaused || thermalPaused || batteryThermalPaused || chargingPaused ||
            wifiPaused || quietPaused || netPaused

    private fun pauseHashing(label: String) {
        io?.post {
            xmrig?.pause()
            releaseWakeLock()
        } ?: run {
            xmrig?.pause()
            releaseWakeLock()
        }
        updateNotification(label)
    }

    /** Resume hashing only when no pause flag is still set. */
    private fun tryResumeHashing() {
        if (anyConstraintPause()) {
            updateNotification(currentPauseLabel())
            return
        }
        acquireWakeLock()
        xmrig?.resumeHashing()
        updateNotification("Mining")
    }

    private fun currentPauseLabel(): String {
        val hs = MiningState.formatHs(MiningState.hashrate10s)
        val status = when {
            thermalPaused -> "Paused — CPU too hot"
            batteryThermalPaused -> "Paused — battery too hot"
            chargingPaused -> "Paused — not charging"
            wifiPaused -> "Paused — no Wi-Fi"
            quietPaused -> "Paused — quiet hours"
            netPaused -> "Paused — no internet"
            userPaused -> "Paused"
            else -> "Mining $hs"
        }
        return status
    }

    private fun publishPauseState() {
        MiningState.userPaused = userPaused
        MiningState.pauseReason = when {
            thermalPaused -> "Device too hot"
            batteryThermalPaused -> "Battery too hot"
            chargingPaused -> "Not charging"
            wifiPaused -> "No Wi-Fi"
            quietPaused -> "Quiet hours"
            netPaused -> "No internet"
            userPaused -> "Paused"
            else -> ""
        }
        xmrig?.emitStats()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "android-xmrig-monero:Mining")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_stat_mining)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)

        if (userPaused) {
            builder.addAction(0, "Resume", pending(this, ACTION_RESUME, 2))
        } else {
            builder.addAction(0, "Pause", pending(this, ACTION_PAUSE, 1))
        }
        builder.addAction(0, "Stop", pending(this, ACTION_STOP, 3))
        return builder.build()
    }

    private fun updateNotification(statusText: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(statusText))
    }
}
