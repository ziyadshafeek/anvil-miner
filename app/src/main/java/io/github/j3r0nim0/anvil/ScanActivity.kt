package io.github.j3r0nim0.anvil

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

class ScanActivity : AppCompatActivity() {

    private lateinit var barcode: DecoratedBarcodeView
    private var handled = false

    private val callback = BarcodeCallback { result: BarcodeResult ->
        if (handled) return@BarcodeCallback
        val raw = result.text ?: return@BarcodeCallback
        val addr = Wallet.parse(raw)
        if (Wallet.isValid(addr)) {
            handled = true
            setResult(RESULT_OK, Intent().putExtra(EXTRA_WALLET, addr))
            finish()
        } else {
            runOnUiThread {
                Toast.makeText(this, "Not a Monero address — keep scanning", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        barcode = findViewById(R.id.barcode)
        barcode.barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        barcode.decodeContinuous(callback)
        barcode.setStatusText("Point at a Monero wallet QR")
        findViewById<android.view.View>(R.id.close).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        barcode.resume()
    }

    override fun onPause() {
        barcode.pause()
        super.onPause()
    }

    companion object {
        const val EXTRA_WALLET = "wallet"
    }
}
