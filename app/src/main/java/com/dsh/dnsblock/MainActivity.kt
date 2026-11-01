package com.dsh.dnsblock

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var toggle: Button
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    companion object {
        const val REQ_VPN = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "DSH DNS Block"
            textSize = 24f
            gravity = Gravity.CENTER_HORIZONTAL
        })

        status = TextView(this).apply {
            text = "状态：未开启"
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 32, 0, 8)
        }
        root.addView(status)

        stats = TextView(this).apply {
            text = "拦截 0 / 总计 0"
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 32)
        }
        root.addView(stats)

        toggle = Button(this).apply {
            text = "开启拦截"
            setOnClickListener { if (running) stopVpn() else startVpn() }
        }
        root.addView(toggle)

        root.addView(TextView(this).apply {
            text = "只拦 DNS，不改应用、不注入进程。\n黑名单：穿山甲 / 优量汇 / 快手 / 百度 / 倍孜。"
            textSize = 12f
            setPadding(0, 40, 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.postDelayed(object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun refresh() {
        stats.text = "拦截 ${DnsVpnService.blockedCount.get()} / 总计 ${DnsVpnService.totalCount.get()}"
    }

    private fun startVpn() {
        val prep = VpnService.prepare(this)
        if (prep != null) {
            startActivityForResult(prep, REQ_VPN)
            return
        }
        startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START))
        running = true
        status.text = "状态：运行中"
        toggle.text = "关闭拦截"
    }

    private fun stopVpn() {
        startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
        running = false
        status.text = "状态：已停止"
        toggle.text = "开启拦截"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) startVpn()
    }
}
