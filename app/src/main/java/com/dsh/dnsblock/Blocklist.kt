package com.dsh.dnsblock

/** 广告 SDK 域名黑名单。命中就回 0.0.0.0，素材拉不到 → SDK 走"加载失败"分支。 */
object Blocklist {
    val domains = listOf(
        // 穿山甲 Pangle
        "pangolin-sdk-toutiao.com",
        "pglstatp-toutiao.com",
        "ad.oceanengine.com",
        "ad.toutiao.com",
        "pangolin.snssdk.com",
        "gromore.snssdk.com",
        "log-api.pangolin-sdk-toutiao.com",
        "sf3-be-pack.pglstatp-toutiao.com",
        // 优量汇 GDT
        "gdt.qq.com",
        "e.qq.com",
        "pgdt.gtimg.cn",
        "img.gdt.qq.com",
        "adx.gdt.qq.com",
        // 快手
        "kwaicdn.com",
        "kwad.kuaishou.com",
        "ad.kwad.com",
        "open.e.kuaishou.com",
        // 百度 mobads
        "mobads.baidu.com",
        "mobads-logs.baidu.com",
        "cpro.baidu.com",
        "union.baidu.com",
        // 倍孜
        "beizi.biz",
        "beiziad.com",
        "api.beizi.biz",
        "static.beizi.biz",
    )

    /** 后缀匹配：域名等于条目，或以 ".条目" 结尾 */
    fun hit(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        return domains.any { d -> h == d || h.endsWith(".$d") }
    }
}
