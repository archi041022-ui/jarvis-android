package ru.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast

/** Hosts the messenger's own signed-in assistant and its live voice session. */
class MessengerActivity : Activity() {
    private lateinit var web: WebView
    private var microphoneRequest: PermissionRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#211D3A"))
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }
        val back = Button(this).apply {
            text = "Назад в Руну"
            isAllCaps = false
            setOnClickListener { finish() }
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(Color.parseColor("#211D3A"))
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url
                    if (trusted(target)) return false
                    if (request.isForMainFrame && request.hasGesture() && target.scheme == "https") {
                        try { startActivity(Intent(Intent.ACTION_VIEW, target)) }
                        catch (_: ActivityNotFoundException) {
                            Toast.makeText(this@MessengerActivity, "Ссылка не открылась", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    if (!trusted(request.origin) || !request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        request.deny()
                    } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else if (microphoneRequest == null) {
                        microphoneRequest = request
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
                    } else request.deny()
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest) {
                    if (microphoneRequest === request) microphoneRequest = null
                }
            }
        }
        root.addView(back, LinearLayout.LayoutParams(-1, -2))
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()
        web.loadUrl(MESSENGER)
    }

    override fun onResume() {
        super.onResume()
        JarvisService.send(this, JarvisService.ACTION_PAUSE)
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) web.evaluateJavascript(
            "document.querySelector('.runa-ai .rai-x')?.click()", null
        )
        if (!isFinishing) JarvisService.send(this, JarvisService.ACTION_RESUME)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MIC_REQUEST) return
        val request = microphoneRequest ?: return
        microphoneRequest = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else request.deny()
    }

    override fun onDestroy() {
        microphoneRequest?.deny()
        (web.parent as? LinearLayout)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    private fun trusted(uri: Uri) =
        uri.scheme == "https" && uri.host == "runachat.ru" && (uri.port == -1 || uri.port == 443)

    private companion object {
        const val MESSENGER = "https://runachat.ru/"
        const val MIC_REQUEST = 41
    }
}
