package com.echoes.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.ImageButton // 导入 ImageButton
import androidx.cardview.widget.CardView // 导入 CardView
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bottomBar: View // 定义底栏变量
    private var isBottomBarVisible = true // 记录当前底栏是否显示
    private var uploadMessage: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (uploadMessage == null) return@registerForActivityResult

        var results: Array<Uri>? = null

        if (result.resultCode == RESULT_OK && result.data != null) {
            val dataString = result.data?.dataString
            val clipData = result.data?.clipData

            if (clipData != null) {
                // 情况 A：用户选中了多张图片
                results = Array(clipData.itemCount) { i ->
                    clipData.getItemAt(i).uri
                }
            } else if (dataString != null) {
                // 情况 B：用户只选了一张图片
                results = arrayOf(dataString.toUri())
            }
        }

        // 把图片列表传回给 WebView
        uploadMessage?.onReceiveValue(results)
        uploadMessage = null
    }
    // ↑↑↑↑↑

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 1. 隐藏状态栏 (Status Bar)
        // 获取窗口控制器
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        // 设置手势操作：从边缘滑动时，状态栏会半透明显示一会儿，然后自动消失
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // 执行隐藏操作
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())

        // 2. 适配刘海屏/挖孔屏 (非常重要！)
        // 如果不加这段，隐藏状态栏后，顶部可能会留下一条黑边
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // 1. 绑定控件
        webView = findViewById(R.id.myWebView)
        bottomBar = findViewById<CardView>(R.id.bottomBar) // 绑定底栏
        val btnRefresh = findViewById<ImageButton>(R.id.btnRefresh) // 绑定刷新按钮

        // 2. 配置 WebView 设置
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true // 启用 JS，现代 Web App 必须
        webSettings.domStorageEnabled = true // 启用本地存储 (localStorage)，很多 App 需要
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT // 开启缓存
        val defaultUA = webSettings.userAgentString
        webSettings.userAgentString = "$defaultUA EchoesApp/1.0"

        // 缩放设置（可选，视你的 Web App 是否适配移动端而定）
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.builtInZoomControls = false // 隐藏缩放按钮
        webSettings.displayZoomControls = false

        // 3. 设置 WebViewClient
        // 这一步非常重要！如果不设置，点击网页内的链接会自动跳转到系统浏览器。
        webView.webViewClient = object : WebViewClient() {
            // 使用这个新的重写方法，替换掉旧的
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // 获取 URL
                val url = request?.url.toString()

                // 处理逻辑
                return if (url.startsWith("http://") || url.startsWith("https://")) {
                    false // 返回 false，让 WebView 自己加载网页
                } else {
                    try {
                        // 处理 tel:, mailto: 等外部协议
                        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                        startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    true // 返回 true，表示我们已经手动处理了该链接
                }
            }
        }

        // 2. 刷新按钮点击逻辑
        btnRefresh.setOnClickListener {
            webView.reload()
        }

        // 3. 核心：监听 WebView 滚动，实现底栏自动伸缩
        webView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->

            // 计算滚动的距离差
            val dy = scrollY - oldScrollY

            // 如果向下滑动 (dy > 0) 且 底栏当前是显示的 -> 隐藏底栏
            if (dy > 10 && isBottomBarVisible) {
                hideBottomBar()
            }
            // 如果向上滑动 (dy < 0) 且 底栏当前是隐藏的 -> 显示底栏
            else if (dy < -10 && !isBottomBarVisible) {
                showBottomBar()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            // 这个方法会在网页点击 <input type="file"> 时触发
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {

                // 如果之前有未处理的回调，先取消掉（避免 WebView 卡死）
                if (uploadMessage != null) {
                    uploadMessage?.onReceiveValue(null)
                    uploadMessage = null
                }

                // 1. 保存回调，等会儿选完图要用
                uploadMessage = filePathCallback

                // 2. 创建打开系统文件管理器的意图 (Intent)
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "image/*"
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

                // 3. 启动文件选择器
                try {
                    fileChooserLauncher.launch(Intent.createChooser(intent, "选择图片"))
                } catch (e: Exception) {
                    uploadMessage?.onReceiveValue(null)
                    uploadMessage = null
                    return false
                }

                return true // 返回 true 表示我们要处理这个事件
            }
        }
        // 4. 加载你的 Web App 地址
        webView.loadUrl("https://echoes.zeabur.app/")

        // 5. 处理物理返回键
        // 如果网页可以后退，则后退网页；否则退出 App
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // 如果不能后退，则执行默认的“退出/后台”操作
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }
    private fun hideBottomBar() {
        // 向下平移底栏的高度，使其移出屏幕
        bottomBar.animate()
            .translationY(bottomBar.height.toFloat())
            .setDuration(300) // 动画时长 300毫秒
            .start()
        isBottomBarVisible = false
    }

    private fun showBottomBar() {
        // 恢复平移位置到 0，回到屏幕内
        bottomBar.animate()
            .translationY(0f)
            .setDuration(300)
            .start()
        isBottomBarVisible = true
    }
}

