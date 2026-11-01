# DSH DNS Block

自己的去广告工具。不注入进程、不改目标应用、不需要 root——所以加固壳检测不到。

## 为什么不用 LSPosed 模块

目标应用 `com.clggjv.xcjfmd.ffo`（Lanerc）带自研加固壳 `libjgccxf`。实测：

```text
LSPosed 注入 → 壳加载完立即 kill 进程 → 应用打不开
且注入时机在壳解密 dex 之前，广告 SDK 类全部 ClassNotFound，hook 一个都没挂上
```

## 原理

```text
VpnService 建一个本地 tun，把系统 DNS 指向它
读到 UDP/53 请求：
  ├─ 命中黑名单 → 直接构造 A 记录 0.0.0.0
  └─ 其它       → 转发 223.5.5.5，应答写回
广告素材域名解析失败 → SDK 走"加载失败" → 应用跳过广告
```

只处理 IPv4 + UDP 53，够用且省电。

## 黑名单

`Blocklist.kt` 里内置：

```text
穿山甲   pangolin-sdk-toutiao.com · pglstatp-toutiao.com · ad.oceanengine.com
优量汇   gdt.qq.com · e.qq.com · pgdt.gtimg.cn
快手     kwaicdn.com · kwad.kuaishou.com · ad.kwad.com
百度     mobads.baidu.com · mobads-logs.baidu.com · cpro.baidu.com
倍孜     beizi.biz · beiziad.com
```

后缀匹配，改完重新编译即可。

## 用法

```text
1. 安装 APK
2. 打开 → 点「开启拦截」→ 允许 VPN 权限
3. 状态栏出现常驻通知，显示"拦截 N / 总计 M"
4. 打开目标应用
```

关掉：App 里点「关闭拦截」，或下拉通知栏停服务。

## 构建

```text
GitHub Actions：push 后自动出 app-debug.apk
本地：Android Studio 打开 → Build APK
```

## 局限

```text
· 只拦 DNS。如果某广告 SDK 把域名硬编码成 IP、或走 DoH/DoT，DNS 拦不到
· 应用若强校验"必须看完广告"，会提示加载失败而不是跳过
· 常驻 VPN 会有通知，且和别的 VPN 应用互斥
```
