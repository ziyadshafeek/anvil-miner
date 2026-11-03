package io.github.j3r0nim0.anvil

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<android.view.View>(R.id.back).setOnClickListener { finish() }

        val version = try {
            val p = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            val code = p.versionCode
            "Version ${p.versionName} (build $code)"
        } catch (_: Exception) {
            "Version unknown"
        }
        findViewById<TextView>(R.id.version).text = version

        findViewById<TextView>(R.id.license).text = readAsset("LICENSE_GPL3.txt")
        findViewById<TextView>(R.id.privacy).text = readAsset("privacy.md")

        link(R.id.linkXmrig, "https://github.com/xmrig/xmrig")
        link(R.id.linkMoXmrig, "https://github.com/MoneroOcean/xmrig")
        link(R.id.linkMo, "https://moneroocean.stream")
        link(R.id.linkRepo, "https://github.com/j3r0nim0/android-xmrig-monero")
        link(R.id.linkGpl, "https://www.gnu.org/licenses/gpl-3.0.txt")
    }

    private fun readAsset(name: String): String =
        runCatching { assets.open(name).bufferedReader().readText() }
            .getOrElse { "(missing $name)" }

    private fun link(id: Int, url: String) {
        findViewById<TextView>(id).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
