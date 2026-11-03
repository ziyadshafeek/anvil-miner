package io.github.j3r0nim0.anvil

import android.Manifest
import android.app.TimePickerDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs.get(this) }
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var wallet: TextInputEditText
    private lateinit var walletError: TextView
    private lateinit var workerName: TextView
    private lateinit var walletSaved: View
    private lateinit var walletEdit: View
    private lateinit var walletDisplay: TextView
    private lateinit var walletEditTitle: TextView
    private lateinit var saveWallet: MaterialButton
    private lateinit var cancelWallet: MaterialButton
    private lateinit var changeWallet: MaterialButton
    private var editingWallet = false
    private lateinit var threads: SeekBar
    private lateinit var threadsLabel: TextView
    private lateinit var threadTicks: Array<TextView>
    private lateinit var thermal: SeekBar
    private lateinit var thermalLabel: TextView
    private lateinit var thermalBlock: LinearLayout
    private lateinit var batteryMeterBlock: LinearLayout
    private lateinit var batteryMeterLabel: TextView
    private lateinit var batteryMeter: ProgressBar
    private lateinit var chargingOnly: MaterialSwitch
    private lateinit var wifiOnly: MaterialSwitch
    private lateinit var bigOnly: MaterialSwitch
    private lateinit var asymmetric: MaterialSwitch
    private lateinit var l3Isolated: MaterialSwitch
    private lateinit var mask4: MaterialSwitch
    private lateinit var mask8: MaterialSwitch
    private lateinit var poolPresetBtn: MaterialButton
    private lateinit var poolPresetLabel: TextView
    private lateinit var swarmSwitch: MaterialSwitch
    private lateinit var swarmLabel: TextView
    private lateinit var thpDisplay: TextView
    private lateinit var gpuBench: MaterialButton
    private lateinit var blackholeBench: MaterialButton
    private lateinit var updateCheck: MaterialSwitch
    private lateinit var gcspcSwitch: MaterialSwitch
    private lateinit var walletStepTitle: TextView
    private lateinit var walletStepBody: TextView
    private lateinit var walletAboutMoney: TextView
    private var walletStep = 0
    private lateinit var quietHours: MaterialSwitch
    private lateinit var quietPickers: LinearLayout
    private lateinit var quietStartBtn: MaterialButton
    private lateinit var quietEndBtn: MaterialButton
    private lateinit var toggle: MaterialButton
    private lateinit var hashrate: TextView
    private lateinit var temp: TextView
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: android.widget.ScrollView
    private lateinit var pauseBanner: TextView
    private lateinit var updateBanner: TextView
    private lateinit var tabMine: View
    private lateinit var tabWallet: View
    private lateinit var tabSettings: View
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var statsRow: View
    private lateinit var accepted: TextView
    private lateinit var rejected: TextView
    private lateinit var noWalletHint: TextView
    private var clickLockUntil = 0L
    private var cpuThermalOk = true
    private var lastThreadIdx = 1
    private var applyingThreads = false

    private val threadSteps = intArrayOf(25, 50, 75, 100)

    // 6-step wallet flow, plain language. No step asks for, stores, or
    // shows a seed phrase, private key, or password — only the public address.
    // 12-step wallet flow: the original 6 Monero steps, kept in full, plus
    // 6 new-preset steps in the same plain style. Nothing here asks for,
    // stores, or displays a seed phrase, private key, or password.
    private val walletSteps = listOf(
        "Step 1 of 12 — Make a separate mining wallet" to
            "Do this in a trusted Monero wallet app, not in this miner. This miner does not make a wallet for you. Make a NEW wallet, only for mining. Do not use your main savings wallet. Write your 25 seed words on paper, keep the paper safe and offline. Never take a photo of them. Never send them to anyone. Why separate? If something goes wrong with mining, your main money is not in that wallet.",
        "Step 2 of 12 — Copy only the public address" to
            "In your wallet app, tap Receive. Copy the long address shown there. It starts with 4 and is about 95 letters and numbers long. This is the ONLY thing you will paste into this miner. Do NOT copy your seed words, private key, or password. A second address starting with 8 is a subaddress — it also works, but for this simple setup use the address starting with 4.",
        "Step 3 of 12 — Paste your address here" to
            "Use Paste or Scan QR code below, then tap Continue. The app will check the address in the next step. It will not send money yet. It will not start mining yet. Paste, do not type it by hand.",
        "Step 4 of 12 — Check this address" to
            "After you save, this tab shows only the first 6 and last 4 characters, for example 4•••••• … ••••. Check that start and end match the address you copied. Your full address is saved on this phone only, and sent to the mining pool so the pool knows where to pay you. It is not your seed phrase. It cannot spend your money.",
        "Step 5 of 12 — Start a short test" to
            "Go to the Mine tab and tap Start mining. Keep your phone plugged in. Mining makes your phone warm — that is normal, up to a limit. In the first 10 minutes look for: (1) speed more than 0, (2) accepted shares going up, even slowly, (3) temperature in the safe range. If all 3 happen, your wallet connection works. If accepted shares stay at 0 after 30 minutes, see Step 6. Do not change your address yet. Charging only is ON: mining pauses if you unplug.",
        "Step 6 of 12 — Check on MoneroOcean yourself" to
            "Do not trust only this app's numbers. Tap Verify on MoneroOcean below. It opens MoneroOcean in your browser. Check: your worker is there, speed is more than 0, and accepted shares are going up. If you see nothing, wait 10 minutes and refresh — the pool page can be slow to show a new worker.",
        "Step 7 of 12 — Make a Feelcoin wallet yourself, if you pick a Feelcoin pool" to
            "This step is only for the Feelcoin presets in Settings. Do it yourself, in the Feelcoin wallet, not in this miner. Go to wallet.feelcoin.org, choose Create New Wallet, give it a name, and set a password you will remember. The wallet will show you a recovery seed. Write that seed on paper, keep the paper safe and offline. Never take a photo of it. Never send it to anyone. Never paste it into this miner, and never paste it to any agent or chat. This miner will never ask you for it, and anyone who asks you for it is not this app.",
        "Step 8 of 12 — Copy ONLY the public Receive address for that coin" to
            "In your Feelcoin wallet, tap Receive, and copy only the public address shown there. That address is the only thing you will paste into this miner. Do NOT copy your seed words, your private key, or your wallet password. For other coins on the preset list, the same rule holds: Receive address only, never a seed, never a private key, never a password. If an address does not look right, stop and check it in your wallet app again before you paste it.",
        "Step 9 of 12 — In this app, pick the preset, then paste only that address" to
            "Go to Settings, tap Choose pool preset, and pick the pool you want — for example Feelcoin Official Pool, a VeinPool Feelcoin pool, VeinPool Lajkto SOLO, VeinPool WAM, or the MoneroOcean default. The app keeps the MoneroOcean default until you pick something else on purpose. Then go to the Wallet tab, paste only the public Receive address for that coin, and save it. For Monero pools the app checks the address shape and checksum. For other coins it cannot check the address the same way, so check the first and last characters yourself, and never paste seed words — if you pasted seed words by mistake, do not start mining, delete that text, and make a new wallet.",
        "Step 10 of 12 — Start mining, then check accepted shares and the pool page yourself" to
            "Keep your phone plugged in, case off if it traps heat, and tap Start on the Mine tab. In the first 10 minutes look for: (1) speed more than 0, (2) accepted shares going up, even slowly, (3) temperature in the safe range, CPU pause 65 degrees and battery pause 40 degrees still on. Then check the pool's own page yourself, for the worker and address you used. Do not trust only this app's numbers. If accepted shares stay at 0 after 30 minutes, stop, check the pool and address again, and do not change to another pool and wallet at the same time — change one thing, then test again.",
        "Step 11 of 12 — Pick a thread mask and watch the 60-second speed, not the burst" to
            "In Settings you can try Big cores only, Asymmetric, L3-isolated, 4-Thread (big cores plus the last two little cores), or 8-Thread. The app discovers the fast cores on this phone by itself; the cpu6, cpu7 and cpu4, cpu5 names are just the expected layout for this phone. Judge a mask by its 60-second speed over 30 minutes at a safe temperature, not by a 10-second burst. The Mine tab also shows AnonHugePages for the app and for the XMRig child process — those are process totals, not proof that the mining dataset itself is on huge pages, so do not read them as a dataset figure.",
        "Step 12 of 12 — LAN proxy is off unless you turn it on yourself" to
            "The LAN swarm proxy on port 3333 is OFF by default and never starts just because mining starts. Turn it on in Settings only if you want a laptop on the same Wi-Fi to mine through this phone to the same pool and worker. Your laptop connects its XMRig to your phone's Wi-Fi address, port 3333. The proxy only accepts connections from your own local network, and it never logs or stores a seed, private key, or password. Turn it off when you are done, and check the pool page yourself to see whether the laptop's shares were accepted.",
    )

    private fun showWalletStep() {
        if (!::walletStepTitle.isInitialized) return
        val (title, body) = walletSteps[walletStep.coerceIn(0, walletSteps.lastIndex)]
        walletStepTitle.text = title
        walletStepBody.text = body
    }

    private fun threadPercent(): Int = threadSteps[threads.progress.coerceIn(0, threadSteps.lastIndex)]

    private fun indexOfThreadPercent(percent: Int): Int {
        val nearest = threadSteps.minBy { kotlin.math.abs(it - percent) }
        return threadSteps.indexOf(nearest).coerceAtLeast(0)
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) openScanner()
        else Toast.makeText(this, "Camera permission needed to scan", Toast.LENGTH_SHORT).show()
    }

    private val scanWallet = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val addr = result.data?.getStringExtra(ScanActivity.EXTRA_WALLET) ?: return@registerForActivityResult
        wallet.setText(addr)
        walletError.visibility = View.GONE
    }

    private val tick = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabMine = findViewById(R.id.tabMine)
        tabWallet = findViewById(R.id.tabWallet)
        tabSettings = findViewById(R.id.tabSettings)
        bottomNav = findViewById(R.id.bottomNav)
        wallet = findViewById(R.id.wallet)
        walletError = findViewById(R.id.walletError)
        workerName = findViewById(R.id.workerName)
        walletSaved = findViewById(R.id.walletSaved)
        walletEdit = findViewById(R.id.walletEdit)
        walletDisplay = findViewById(R.id.walletDisplay)
        walletEditTitle = findViewById(R.id.walletEditTitle)
        saveWallet = findViewById(R.id.saveWallet)
        cancelWallet = findViewById(R.id.cancelWallet)
        changeWallet = findViewById(R.id.changeWallet)
        threads = findViewById(R.id.threads)
        threadsLabel = findViewById(R.id.threadsLabel)
        statsRow = findViewById(R.id.statsRow)
        accepted = findViewById(R.id.accepted)
        rejected = findViewById(R.id.rejected)
        noWalletHint = findViewById(R.id.noWalletHint)
        threadTicks = arrayOf(
            findViewById(R.id.tick25),
            findViewById(R.id.tick50),
            findViewById(R.id.tick75),
            findViewById(R.id.tick100),
        )
        threads.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> alignThreadTicks() }
        thermal = findViewById(R.id.thermal)
        thermalLabel = findViewById(R.id.thermalLabel)
        thermalBlock = findViewById(R.id.thermalBlock)
        batteryMeterBlock = findViewById(R.id.batteryMeterBlock)
        batteryMeterLabel = findViewById(R.id.batteryMeterLabel)
        batteryMeter = findViewById(R.id.batteryMeter)
        chargingOnly = findViewById(R.id.chargingOnly)
        wifiOnly = findViewById(R.id.wifiOnly)
        bigOnly = findViewById(R.id.bigOnly)
        asymmetric = findViewById(R.id.asymmetric)
        l3Isolated = findViewById(R.id.l3Isolated)
        mask4 = findViewById(R.id.mask4)
        mask8 = findViewById(R.id.mask8)
        poolPresetBtn = findViewById(R.id.poolPresetBtn)
        poolPresetLabel = findViewById(R.id.poolPresetLabel)
        swarmSwitch = findViewById(R.id.swarmSwitch)
        thpDisplay = findViewById(R.id.thpDisplay)
        gpuBench = findViewById(R.id.gpuBench)
        blackholeBench = findViewById(R.id.blackholeBench)
        updateCheck = findViewById(R.id.updateCheck)
        gcspcSwitch = findViewById(R.id.gcspcSwitch)
        walletStepTitle = findViewById(R.id.walletStepTitle)
        walletStepBody = findViewById(R.id.walletStepBody)
        walletAboutMoney = findViewById(R.id.walletAboutMoney)
        showWalletStep()
        quietHours = findViewById(R.id.quietHours)
        quietPickers = findViewById(R.id.quietPickers)
        quietStartBtn = findViewById(R.id.quietStart)
        quietEndBtn = findViewById(R.id.quietEnd)
        toggle = findViewById(R.id.toggle)
        hashrate = findViewById(R.id.hashrate)
        temp = findViewById(R.id.temp)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        pauseBanner = findViewById(R.id.pauseBanner)
        updateBanner = findViewById(R.id.updateBanner)

        showTab(R.id.nav_mine)
        bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        wallet.setText(savedWallet())
        val savedThreads = prefs.getInt(Prefs.THREADS, 50)
        lastThreadIdx = indexOfThreadPercent(savedThreads)
        threads.max = threadSteps.lastIndex
        threads.progress = lastThreadIdx
        thermal.progress = prefs.getInt(Prefs.THERMAL, 65).coerceIn(50, 85)
        chargingOnly.isChecked = prefs.getBoolean(Prefs.CHARGING_ONLY, true)
        wifiOnly.isChecked = prefs.getBoolean(Prefs.WIFI_ONLY, false)
        bigOnly.isChecked = prefs.getInt(Prefs.THREAD_MODE, 0) == Prefs.THREAD_MODE_BIG_ONLY
        asymmetric.isChecked = prefs.getInt(Prefs.THREAD_MODE, 0) == Prefs.THREAD_MODE_ASYMMETRIC
        l3Isolated.isChecked = prefs.getInt(Prefs.THREAD_MODE, 0) == Prefs.THREAD_MODE_L3_ISOLATED
        mask4.isChecked = prefs.getInt(Prefs.THREAD_MODE, 0) == Prefs.THREAD_MODE_MASK4
        mask8.isChecked = prefs.getInt(Prefs.THREAD_MODE, 0) == Prefs.THREAD_MODE_MASK8
        swarmSwitch.isChecked = prefs.getBoolean(Prefs.SWARM_PROXY_ON, false)
        updateCheck.isChecked = prefs.getBoolean(Prefs.UPDATE_CHECK, true)
        gcspcSwitch.isChecked = prefs.getBoolean(Prefs.GCSPC_ON, true)
        quietHours.isChecked = prefs.getBoolean(Prefs.QUIET_ON, false)
        updateLabels()
        updateQuietButtons()
        quietPickers.visibility = if (quietHours.isChecked) View.VISIBLE else View.GONE

        cpuThermalOk = ThermalMonitor.isCpuThermalReadable()
        MiningState.cpuThermalReadable = cpuThermalOk
        applyThermalUi()
        showWalletUi()

        workerName.text = "This device is worker ${MiningService.poolWorkerName(this)}"

        threads.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (applyingThreads) return
                val idx = threads.progress.coerceIn(0, threadSteps.lastIndex)
                val value = threadSteps[idx]
                if (idx == lastThreadIdx) return
                if (value == 100 && prefs.getInt(Prefs.CPU100, 0) < 2) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("100% CPU threads")
                        .setMessage("All cores, more heat, more battery. Continue at 100%?")
                        .setNegativeButton("Cancel") { _, _ -> snapThreadsBack() }
                        .setPositiveButton("Continue") { _, _ ->
                            prefs.edit().putInt(Prefs.CPU100, prefs.getInt(Prefs.CPU100, 0) + 1).apply()
                            commitThreads(idx)
                        }
                        .setOnCancelListener { snapThreadsBack() }
                        .show()
                    return
                }
                commitThreads(idx)
            }
        })
        thermal.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                save()
                if (MiningService.isRunning) sendReconfigure()
            }
        })

        chargingOnly.setOnCheckedChangeListener { _, _ ->
            save()
            if (MiningService.isRunning) sendReconfigure()
        }
        wifiOnly.setOnCheckedChangeListener { _, _ ->
            save()
            if (MiningService.isRunning) sendReconfigure()
        }
        bigOnly.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                asymmetric.isChecked = false
                l3Isolated.isChecked = false
                mask4.isChecked = false
                mask8.isChecked = false
                prefs.edit().putInt(Prefs.THREAD_MODE, Prefs.THREAD_MODE_BIG_ONLY).apply()
            } else if (prefs.getInt(Prefs.THREAD_MODE, 0) == Prefs.THREAD_MODE_BIG_ONLY) {
                prefs.edit().putInt(Prefs.THREAD_MODE, 0).apply()
            }
            updateLabels()
            if (MiningService.isRunning) sendReconfigure()
        }
        asymmetric.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                bigOnly.isChecked = false
                l3Isolated.isChecked = false
                mask4.isChecked = false
                mask8.isChecked = false
                prefs.edit().putInt(Prefs.THREAD_MODE, Prefs.THREAD_MODE_ASYMMETRIC).apply()
            } else if (prefs.getInt(Prefs.THREAD_MODE, 0) == Prefs.THREAD_MODE_ASYMMETRIC) {
                prefs.edit().putInt(Prefs.THREAD_MODE, 0).apply()
            }
            updateLabels()
            if (MiningService.isRunning) sendReconfigure()
        }
        l3Isolated.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                bigOnly.isChecked = false
                asymmetric.isChecked = false
                mask4.isChecked = false
                mask8.isChecked = false
                prefs.edit().putInt(Prefs.THREAD_MODE, Prefs.THREAD_MODE_L3_ISOLATED).apply()
            } else if (prefs.getInt(Prefs.THREAD_MODE, 0) == Prefs.THREAD_MODE_L3_ISOLATED) {
                prefs.edit().putInt(Prefs.THREAD_MODE, 0).apply()
            }
            updateLabels()
            if (MiningService.isRunning) sendReconfigure()
        }
        mask4.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                bigOnly.isChecked = false
                asymmetric.isChecked = false
                l3Isolated.isChecked = false
                mask8.isChecked = false
                prefs.edit().putInt(Prefs.THREAD_MODE, Prefs.THREAD_MODE_MASK4).apply()
            } else if (prefs.getInt(Prefs.THREAD_MODE, 0) == Prefs.THREAD_MODE_MASK4) {
                prefs.edit().putInt(Prefs.THREAD_MODE, 0).apply()
            }
            updateLabels()
            if (MiningService.isRunning) sendReconfigure()
        }
        mask8.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                bigOnly.isChecked = false
                asymmetric.isChecked = false
                l3Isolated.isChecked = false
                mask4.isChecked = false
                prefs.edit().putInt(Prefs.THREAD_MODE, Prefs.THREAD_MODE_MASK8).apply()
            } else if (prefs.getInt(Prefs.THREAD_MODE, 0) == Prefs.THREAD_MODE_MASK8) {
                prefs.edit().putInt(Prefs.THREAD_MODE, 0).apply()
            }
            updateLabels()
            if (MiningService.isRunning) sendReconfigure()
        }
        poolPresetBtn.setOnClickListener { showPoolPresetDialog() }
        swarmSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.SWARM_PROXY_ON, checked).apply()
            updateSwarmLabel()
            val action = if (checked) MiningService.ACTION_SWARM_START else MiningService.ACTION_SWARM_STOP
            startService(Intent(this, MiningService::class.java).setAction(action))
        }
        gpuBench.setOnClickListener {
            gpuBench.isEnabled = false
            Thread({
                val result = NativeBench.runGpuBenchOnce(1024)
                runOnUiThread {
                    gpuBench.isEnabled = true
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                }
            }, "gpu-bench").start()
        }
        blackholeBench.setOnClickListener {
            blackholeBench.isEnabled = false
            Thread({
                val result = NativeBench.blackholeSelfTest()
                runOnUiThread {
                    blackholeBench.isEnabled = true
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                }
            }, "blackhole-bench").start()
        }
        updateCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.UPDATE_CHECK, checked).apply()
            if (!checked) updateBanner.visibility = View.GONE
        }
        gcspcSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.GCSPC_ON, checked).apply()
        }
        quietHours.setOnCheckedChangeListener { _, checked ->
            quietPickers.visibility = if (checked) View.VISIBLE else View.GONE
            save()
            if (MiningService.isRunning) sendReconfigure()
        }
        quietStartBtn.setOnClickListener { pickTime(true) }
        quietEndBtn.setOnClickListener { pickTime(false) }

        findViewById<View>(R.id.openLog).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<ImageButton>(R.id.pasteWallet).setOnClickListener { pasteWallet() }
        // No full-address copy button: the saved view shows a masked address only.
        findViewById<ImageButton>(R.id.scanWallet).setOnClickListener { requestScan() }
        findViewById<MaterialButton>(R.id.walletStepPrev).setOnClickListener {
            walletStep = (walletStep - 1).coerceAtLeast(0)
            showWalletStep()
        }
        findViewById<MaterialButton>(R.id.walletStepNext).setOnClickListener {
            walletStep = (walletStep + 1).coerceAtMost(walletSteps.lastIndex)
            showWalletStep()
        }
        findViewById<MaterialButton>(R.id.walletAboutMoneyBtn).setOnClickListener {
            walletAboutMoney.visibility =
                if (walletAboutMoney.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<MaterialButton>(R.id.verifyMo).setOnClickListener { openMoneroOcean() }
        saveWallet.setOnClickListener { commitWallet() }
        changeWallet.setOnClickListener { enterWalletEdit(changing = true) }
        cancelWallet.setOnClickListener { editingWallet = false; showWalletUi() }
        findViewById<MaterialButton>(R.id.rebench).setOnClickListener { confirmRebench() }
        findViewById<MaterialButton>(R.id.openAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        linkifyFooter()
        toggle.setOnClickListener { onToggle() }
        toggle.setOnLongClickListener {
            if (!armed()) return@setOnLongClickListener false
            if (!MiningService.isRunning || MiningService.isBusy) return@setOnLongClickListener false
            startService(Intent(this, MiningService::class.java).setAction(MiningService.ACTION_STOP))
            render()
            true
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        maybeHandleMoneroIntent(intent)
        checkUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeHandleMoneroIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        cpuThermalOk = ThermalMonitor.isCpuThermalReadable()
        MiningState.cpuThermalReadable = cpuThermalOk
        applyThermalUi()
        render()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        save()
    }

    private fun showTab(id: Int) {
        tabMine.visibility = if (id == R.id.nav_mine) View.VISIBLE else View.GONE
        tabWallet.visibility = if (id == R.id.nav_wallet) View.VISIBLE else View.GONE
        tabSettings.visibility = if (id == R.id.nav_settings) View.VISIBLE else View.GONE
        if (id == R.id.nav_mine) threads.post { alignThreadTicks() }
    }

    private fun applyThermalUi() {
        if (cpuThermalOk) {
            thermalBlock.visibility = View.VISIBLE
            batteryMeterBlock.visibility = View.GONE
        } else {
            thermalBlock.visibility = View.GONE
            batteryMeterBlock.visibility = View.VISIBLE
        }
    }

    private fun armed(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now < clickLockUntil || MiningService.isBusy) return false
        clickLockUntil = now + 800
        return true
    }

    private fun onToggle() {
        if (!armed()) return
        if (MiningService.isRunning) {
            val action = if (MiningState.userPaused) {
                MiningService.ACTION_RESUME
            } else {
                MiningService.ACTION_PAUSE
            }
            startService(Intent(this, MiningService::class.java).setAction(action))
            render()
            return
        }

        val addr = savedWallet()
        if (!walletOkForSelectedPreset(addr)) {
            noWalletHint.visibility = View.VISIBLE
            bottomNav.selectedItemId = R.id.nav_wallet
            return
        }
        ensureHarmConsent { startMining(addr) }
    }

    private fun startMining(addr: String) {
        val intent = miningIntent(MiningService.ACTION_START).apply {
            putExtra(MiningService.EXTRA_WALLET, addr)
        }
        ContextCompat.startForegroundService(this, intent)
        render()
    }

    private fun miningIntent(action: String): Intent = Intent(this, MiningService::class.java).apply {
        this.action = action
        putExtra(MiningService.EXTRA_WALLET, savedWallet())
        putExtra(MiningService.EXTRA_THREADS_PERCENT, threadPercent())
        putExtra(MiningService.EXTRA_THERMAL_THRESHOLD, thermal.progress)
        putExtra(MiningService.EXTRA_CHARGING_ONLY, chargingOnly.isChecked)
        putExtra(MiningService.EXTRA_WIFI_ONLY, wifiOnly.isChecked)
        putExtra(MiningService.EXTRA_QUIET_ON, quietHours.isChecked)
        putExtra(MiningService.EXTRA_QUIET_START, prefs.getInt(Prefs.QUIET_START, 22 * 60))
        putExtra(MiningService.EXTRA_QUIET_END, prefs.getInt(Prefs.QUIET_END, 7 * 60))
    }

    private fun sendReconfigure() {
        startService(miningIntent(MiningService.ACTION_RECONFIGURE))
    }

    private fun commitThreads(idx: Int) {
        if (MiningService.isRunning && idx != lastThreadIdx) {
            AlertDialog.Builder(this)
                .setTitle("Change threads?")
                .setMessage("XMRig restarts and re-benchmarks (~3–5 min) when thread % changes.")
                .setNegativeButton("Cancel") { _, _ -> snapThreadsBack() }
                .setPositiveButton("Change") { _, _ ->
                    lastThreadIdx = idx
                    save()
                    sendReconfigure()
                }
                .setOnCancelListener { snapThreadsBack() }
                .show()
        } else {
            lastThreadIdx = idx
            save()
        }
    }

    private fun snapThreadsBack() {
        applyingThreads = true
        threads.progress = lastThreadIdx
        applyingThreads = false
        updateLabels()
    }

    private fun ensureHarmConsent(onYes: () -> Unit) {
        if (prefs.getBoolean(Prefs.DEVICE_HARM, false)) {
            onYes()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Before you start mining")
            .setMessage(
                "Mining runs the CPU hard. That means heat and extra battery use.\n\n" +
                    "Anvil will:\n" +
                    "• Pause if the CPU or battery gets too hot\n" +
                    "• Optionally mine only while charging\n" +
                    "• Optionally mine only on Wi-Fi\n" +
                    "• Optionally pause during quiet hours\n\n" +
                    "No warranty. Use at your own risk.",
            )
            .setNegativeButton("Not now", null)
            .setPositiveButton("I understand") { _, _ ->
                prefs.edit().putBoolean(Prefs.DEVICE_HARM, true).apply()
                onYes()
            }
            .show()
    }

    private fun confirmRebench() {
        val running = MiningService.isRunning
        AlertDialog.Builder(this)
            .setTitle("Re-benchmark now?")
            .setMessage(
                if (running) {
                    "Restarts mining and re-runs the ~3–5 min CPU calibration."
                } else {
                    "Clears the cached algorithm. The next Start will re-calibrate (~3–5 min)."
                },
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Re-benchmark") { _, _ ->
                if (running) {
                    startService(Intent(this, MiningService::class.java).setAction(MiningService.ACTION_RECALIBRATE))
                    Toast.makeText(this, "Re-benchmarking…", Toast.LENGTH_SHORT).show()
                } else {
                    XMRigManager.clearAlgoCache(this)
                    Toast.makeText(this, "Cache cleared. Start to re-benchmark.", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun pasteWallet() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val addr = Wallet.parse(text)
        if (addr.isBlank()) {
            Toast.makeText(
                this,
                "Nothing was copied. Go back to your wallet app, tap Receive, then Copy, then come back and tap Paste here.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        wallet.setText(addr)
        walletError.visibility = View.GONE
    }

    private fun requestScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            openScanner()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openScanner() {
        scanWallet.launch(Intent(this, ScanActivity::class.java))
    }

    private fun openMoneroOcean() {
        val addr = savedWallet()
        if (!Wallet.isValid(addr)) {
            Toast.makeText(this, "Set a valid wallet first", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse("https://moneroocean.stream/#/wallet/$addr/overview")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun maybeHandleMoneroIntent(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        val addr = Wallet.parse(data)
        // Fill edit mode only. Saving requires an explicit Save/Continue tap.
        // An invalid address is never saved, and a scanned seed phrase must
        // fail validation in commitWallet() and never be saved.
        if (Wallet.isValid(addr)) {
            editingWallet = true
            wallet.setText(addr)
            walletError.visibility = View.GONE
            showWalletUi()
            bottomNav.selectedItemId = R.id.nav_wallet
        }
    }

    private fun savedWallet(): String =
        Wallet.parse(prefs.getString(Prefs.WALLET, "") ?: "")

    private fun enterWalletEdit(changing: Boolean) {
        editingWallet = true
        wallet.setText(if (changing) savedWallet() else "")
        walletError.visibility = View.GONE
        showWalletUi()
    }

    private fun commitWallet() {
        val addr = Wallet.parse(wallet.text?.toString().orEmpty())
        wallet.setText(addr)
        if (!walletOkForSelectedPreset(addr)) {
            walletError.visibility = View.VISIBLE
            walletError.text = if (selectedPreset().requiresMoneroAddress) {
                "This address does not look right. A Monero address starts with 4, and is 95 or 106 characters long. You pasted ${addr.length} characters. Please copy it again from your wallet app. Do not type it by hand. Do not paste seed words here. It may have a missing letter, an extra space, or it may be a Bitcoin or other-coin address. Monero addresses do not use the letters 0, O, I, or l."
            } else {
                "This does not look like a single public Receive address for ${selectedPreset().name}. You pasted ${addr.length} characters. Please copy only the public Receive address again from your wallet app. Do not type it by hand. Do not paste seed words, a private key, or a password here."
            }
            return
        }
        walletError.visibility = View.GONE
        prefs.edit().putString(Prefs.WALLET, addr).apply()
        editingWallet = false
        showWalletUi()
        if (MiningService.isRunning) sendReconfigure()
        Toast.makeText(this, "Address saved", Toast.LENGTH_SHORT).show()
    }

    private fun showWalletUi() {
        val addr = savedWallet()
        val valid = walletOkForSelectedPreset(addr)
        val edit = editingWallet || !valid
        walletSaved.visibility = if (edit) View.GONE else View.VISIBLE
        walletEdit.visibility = if (edit) View.VISIBLE else View.GONE
        if (!edit) {
            walletDisplay.text = if (selectedPreset().requiresMoneroAddress) {
                Wallet.mask(addr)
            } else {
                val clean = Wallet.parse(addr)
                if (clean.length >= 10) clean.take(6) + " … " + clean.takeLast(4) else "Not set"
            }
        } else {
            walletEditTitle.text = if (valid) {
                "Change wallet"
            } else {
                if (selectedPreset().requiresMoneroAddress) {
                    "Enter your Monero (XMR) wallet address"
                } else {
                    "Enter your public Receive address for ${selectedPreset().name} — never a seed, private key, or password"
                }
            }
            saveWallet.text = if (valid) "Save" else "Continue"
            cancelWallet.visibility = if (valid) View.VISIBLE else View.GONE
        }
        noWalletHint.visibility =
            if (!MiningService.isRunning && !valid) View.VISIBLE else View.GONE
    }

    private fun pickTime(start: Boolean) {
        val key = if (start) Prefs.QUIET_START else Prefs.QUIET_END
        val def = if (start) 22 * 60 else 7 * 60
        val current = prefs.getInt(key, def)
        TimePickerDialog(
            this,
            { _, hour, minute ->
                prefs.edit().putInt(key, hour * 60 + minute).apply()
                updateQuietButtons()
                if (MiningService.isRunning) sendReconfigure()
            },
            current / 60,
            current % 60,
            true,
        ).show()
    }

    private fun updateQuietButtons() {
        val s = prefs.getInt(Prefs.QUIET_START, 22 * 60)
        val e = prefs.getInt(Prefs.QUIET_END, 7 * 60)
        quietStartBtn.text = "Start ${QuietHours.format(s)}"
        quietEndBtn.text = "End ${QuietHours.format(e)}"
    }

    private fun checkUpdate() {
        // Opt-out: Settings > Check for updates. When off, no GitHub call at all.
        if (!prefs.getBoolean(Prefs.UPDATE_CHECK, true)) return
        val current = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0"
        } catch (_: Exception) {
            "0.1.0"
        }
        UpdateChecker.fetch { info ->
            if (info == null || !UpdateChecker.isNewer(info.versionName, current)) return@fetch
            updateBanner.visibility = View.VISIBLE
            updateBanner.text = "Anvil ${info.versionName} is out — tap to open GitHub Releases"
            updateBanner.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)))
            }
        }
    }

    private fun linkifyFooter() {
        val footer = findViewById<TextView>(R.id.footer)
        val text = footer.text.toString()
        val link = "moneroocean.stream"
        val start = text.indexOf(link)
        if (start < 0) return
        val spannable = SpannableString(text)
        spannable.setSpan(
            URLSpan("https://moneroocean.stream"),
            start, start + link.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        footer.text = spannable
        footer.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun render() {
        val running = MiningService.isRunning
        val busy = MiningService.isBusy
        toggle.isEnabled = !busy
        toggle.text = when {
            busy && !running -> "Starting…"
            busy && running -> "Stopping…"
            !running -> "Start"
            MiningState.userPaused -> "Resume"
            else -> "Pause"
        }
        val walletOk = walletOkForSelectedPreset(savedWallet())
        noWalletHint.visibility = if (!running && !walletOk) View.VISIBLE else View.GONE
        thpDisplay.text = if (running) {
            "THP app: ${ThpProbe.ownSmapsAnonHugePages()} | ${ThpProbe.xmrigSmapsAnonHugePages()} (process totals, NOT dataset-only proof)"
        } else {
            "THP — start mining to read app + XMRig child AnonHugePages"
        }
        val canEditWallet = !running && !busy
        wallet.isEnabled = canEditWallet
        saveWallet.isEnabled = canEditWallet
        changeWallet.isEnabled = canEditWallet
        findViewById<ImageButton>(R.id.pasteWallet).isEnabled = canEditWallet
        findViewById<ImageButton>(R.id.scanWallet).isEnabled = canEditWallet
        threads.isEnabled = !busy
        thermal.isEnabled = !busy

        val pause = MiningState.pauseReason
        if (running && pause.isNotEmpty()) {
            pauseBanner.visibility = View.VISIBLE
            val extra = if (MiningState.noInternetSecondsLeft >= 0) {
                "  (pausing in ${MiningState.noInternetSecondsLeft}s)"
            } else {
                ""
            }
            pauseBanner.text = "Mining paused · $pause$extra"
        } else if (running && MiningState.noInternetSecondsLeft >= 0) {
            pauseBanner.visibility = View.VISIBLE
            pauseBanner.text = "No internet · pausing in ${MiningState.noInternetSecondsLeft}s"
        } else {
            pauseBanner.visibility = View.GONE
        }

        if (!cpuThermalOk) {
            val bt = MiningState.batteryTempC
            if (bt > 0) {
                batteryMeterLabel.text = "Battery temperature  ${bt}°C"
                batteryMeter.progress = bt.coerceIn(25, 42)
            }
        }

        if (!running) {
            hashrate.text = "— H/s"
            temp.text = "— °C"
            status.text = "Idle"
            if (log.text.isNotEmpty()) log.text = ""
            statsRow.visibility = View.GONE
            return
        }

        statsRow.visibility = View.VISIBLE
        accepted.text = MiningState.accepted.toString()
        rejected.text = MiningState.rejected.toString()

        hashrate.text = if (MiningState.hashrate10s > 0) {
            MiningState.formatHs(MiningState.hashrate10s)
        } else {
            "0 H/s"
        }
        temp.text = if (MiningState.tempCelsius > 0) {
            val src = if (MiningState.tempSource == "battery") "bat" else "cpu"
            "${MiningState.tempCelsius}°C $src"
        } else {
            "— °C"
        }
        status.text = when {
            pause.isNotEmpty() -> pause
            MiningState.poolError.isNotEmpty() && MiningState.pool.isEmpty() ->
                MiningState.poolError
            MiningState.pool.isNotEmpty() ->
                "${MiningState.pool}  ·  ${MiningState.accepted} accepted  ·  ${MiningState.rejected} rejected  ·  ${formatUptime(MiningState.uptimeSecs)}"
            else -> "Starting… first run may take a few minutes"
        }
        val live = MiningState.logText.ifBlank { MiningState.lastLogLine }
        if (log.text.toString() != live) {
            log.text = live
            logScroll.post { logScroll.fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
        }
    }

    private fun selectedPreset(): PoolPresets.Preset =
        PoolPresets.byId(prefs.getString(Prefs.POOL_PRESET_ID, null) ?: PoolPresets.moneroOceanDefault.id)

    private fun updatePoolLabel() {
        if (!::poolPresetLabel.isInitialized) return
        val p = selectedPreset()
        poolPresetLabel.text = "Pool  ${p.name} — ${p.host}:${p.port} ${if (p.tls) "TLS" else "plaintext"} (${p.algo})" +
            if (p.verified) "" else " — UNVERIFIED, see guide"
        poolPresetBtn.text = "Choose pool preset: ${p.name}"
    }

    private fun updateSwarmLabel() {
        if (!::swarmLabel.isInitialized) return
        swarmLabel.text = if (MiningState.swarmOn) {
            "LAN proxy ON — ${MiningState.swarmUpstream}"
        } else if (MiningState.swarmUpstream.isNotEmpty()) {
            "LAN proxy OFF — ${MiningState.swarmUpstream}"
        } else {
            "LAN proxy OFF — turn on only if a laptop on the same Wi-Fi should mine through this phone"
        }
    }

    private fun showPoolPresetDialog() {
        val names = PoolPresets.presets.map { p ->
            "${p.name} — ${p.host}:${p.port} ${if (p.tls) "TLS" else "plaintext"}"
        }.toTypedArray()
        val current = PoolPresets.presets.indexOfFirst { it.id == selectedPreset().id }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Choose pool preset")
            .setSingleChoiceItems(names, current) { dialog, which ->
                val p = PoolPresets.presets[which]
                if (!p.verified && p.id != PoolPresets.moneroOceanDefault.id) {
                    AlertDialog.Builder(this)
                        .setTitle("Unverified preset")
                        .setMessage("${p.name} is NOT verified against the pool's own page in this build. ${p.note} Do you still want to pick it? You must still enter your own public wallet address for that coin, and check the pool page yourself after you start.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Pick it anyway") { _, _ ->
                            prefs.edit().putString(Prefs.POOL_PRESET_ID, p.id).apply()
                            updatePoolLabel()
                            if (MiningService.isRunning) sendReconfigure()
                        }
                        .show()
                } else {
                    prefs.edit().putString(Prefs.POOL_PRESET_ID, p.id).apply()
                    updatePoolLabel()
                    if (MiningService.isRunning) sendReconfigure()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Wallet is usable for the selected preset. Monero presets use checksum validation. */
    private fun walletOkForSelectedPreset(addr: String): Boolean {
        val p = selectedPreset()
        return if (p.requiresMoneroAddress) {
            Wallet.isValid(addr)
        } else {
            // Other coins: the app cannot checksum-verify them. Accept a
            // single non-empty token (no spaces, not a seed phrase) and
            // rely on the guide's first/last-character check by the user.
            val clean = Wallet.parse(addr)
            clean.length >= 20 && !clean.contains(" ") && clean.split(Regex("\\s+")).size < 8
        }
    }

    private fun updateLabels() {
        threadsLabel.text = when (prefs.getInt(Prefs.THREAD_MODE, 0)) {
            Prefs.THREAD_MODE_BIG_ONLY -> "Threads  Big cores only (2, pinned)"
            Prefs.THREAD_MODE_ASYMMETRIC -> "Threads  Asymmetric (2 big + 2 little, pinned)"
            Prefs.THREAD_MODE_L3_ISOLATED -> "Threads  L3-isolated per-algo (rx=big, light=little)"
            Prefs.THREAD_MODE_MASK4 -> "Threads  4-Thread mask (big + last 2 little, pinned)"
            Prefs.THREAD_MODE_MASK8 -> "Threads  8-Thread mask (all cores, pinned)"
            else -> "Threads  ${threadPercent()}%"
        }
        thermalLabel.text = "CPU pause  ${thermal.progress}°C"
        updatePoolLabel()
        updateSwarmLabel()
        alignThreadTicks()
    }

    /** Sit each 25/50/75/100 label under the SeekBar thumb, not in equal columns. */
    private fun alignThreadTicks() {
        if (!::threads.isInitialized || !::threadTicks.isInitialized) return
        if (threads.width == 0) return
        val pad = threads.paddingStart
        val track = (threads.width - threads.paddingStart - threads.paddingEnd).toFloat()
        val last = (threadSteps.size - 1).coerceAtLeast(1)
        threadTicks.forEachIndexed { i, label ->
            label.post {
                val center = pad + track * i / last
                val x = center - label.width / 2f
                val max = (threads.width - label.width).toFloat().coerceAtLeast(0f)
                label.translationX = x.coerceIn(0f, max)
            }
        }
    }

    private fun save() {
        prefs.edit()
            .putInt(Prefs.THREADS, threadPercent())
            .putInt(Prefs.THERMAL, thermal.progress)
            .putBoolean(Prefs.CHARGING_ONLY, chargingOnly.isChecked)
            .putBoolean(Prefs.WIFI_ONLY, wifiOnly.isChecked)
            .putBoolean(Prefs.QUIET_ON, quietHours.isChecked)
            .apply()
    }

    private fun formatUptime(secs: Int): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
