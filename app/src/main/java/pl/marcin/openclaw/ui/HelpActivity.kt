package pl.marcin.openclaw.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class HelpActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    if (uri.scheme == "http" || uri.scheme == "https") { startActivity(Intent(Intent.ACTION_VIEW, uri)); return true }
                    return false
                }
            }
        }
        setContentView(view)
        val language = resources.configuration.locales[0]?.language ?: Locale.getDefault().language
        val file = if (language == "pl") "help_pl.html" else "help_en.html"
        val topic = intent.getStringExtra("topic") ?: "top"
        view.loadUrl("file:///android_asset/$file#$topic")
    }
}
