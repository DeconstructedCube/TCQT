package com.owo233.tcqt.features.advanced

import com.owo233.tcqt.annotations.RegisterAction
import com.owo233.tcqt.api.Feature
import com.owo233.tcqt.core.action.ActionProcess
import com.owo233.tcqt.core.env.isFlagEnabled
import com.owo233.tcqt.core.hook.hookMethodAfter
import com.owo233.tcqt.host.service.NTServiceFetcher
import com.owo233.tcqt.host.service.milky.MilkyEventDispatcher
import com.owo233.tcqt.host.service.milky.MilkyServer
import com.tencent.qqnt.kernel.api.IKernelService
import com.tencent.qqnt.kernel.api.impl.KernelServiceImpl
import mqq.app.MobileQQ

@RegisterAction(processes = [ActionProcess.MAIN])
object MilkyBot : Feature(
    key = "milky_bot",
    name = "Milky 机器人协议端",
    desc = "在宿主内启动 Milky v1.3 协议服务（支持 HTTP /api、WebSocket /event、SSE /event 及 WebHook 推送）。",
    uiOrder = 15,
) {

    private val listenHost by stringOption(
        settingKey = "host",
        name = "监听地址",
        defaultValue = "127.0.0.1",
        desc = "默认 127.0.0.1（仅本机可连），局域网连接可填 0.0.0.0",
        placeholder = "127.0.0.1",
    )

    private val listenPort by stringOption(
        settingKey = "port",
        name = "服务端口",
        defaultValue = "3000",
        desc = "Milky HTTP (/api) 与事件流 (/event) 共用端口",
        placeholder = "3000",
    )

    private val accessToken by stringOption(
        settingKey = "access_token",
        name = "Access Token",
        defaultValue = "",
        desc = "Bearer 鉴权密钥（留空则不启用鉴权）",
        placeholder = "留空则不鉴权",
    )

    private val webhookUrls by stringOption(
        settingKey = "webhook_urls",
        name = "WebHook 推送地址",
        defaultValue = "",
        desc = "主动推送事件的 HTTP 地址，多个地址用换行或逗号分隔",
        placeholder = "http://127.0.0.1:8080/webhook",
    )

    private val options by multiIntOption(
        settingKey = "options",
        name = "运行选项",
        defaultValue = 0,
        options = listOf(
            "上报自身发送的消息",
            "强制仅监听本机回环 (127.0.0.1)",
        ),
    )

    override fun install() {
        val opts = options
        val reportSelf = opts.isFlagEnabled(0)
        val forceLoopback = opts.isFlagEnabled(1)

        val host = if (forceLoopback) "127.0.0.1" else listenHost.trim().ifEmpty { "127.0.0.1" }
        val port = listenPort.trim().toIntOrNull()?.coerceIn(1, 65535) ?: 3000
        val token = accessToken.trim()
        val webhooks = webhookUrls
            .split('\n', ',')
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }

        MilkyEventDispatcher.enabled = true

        MilkyServer.start(
            MilkyServer.Config(
                host = host,
                port = port,
                accessToken = token,
                webhookUrls = webhooks,
                reportSelfMessage = reportSelf,
            )
        )

        KernelServiceImpl::class.java.hookMethodAfter("initService") {
            val service = it.thisObject as IKernelService
            NTServiceFetcher.onFetch(service)
            MilkyEventDispatcher.onKernelServiceFetch(service)
        }

        runCatching {
            val runtime = MobileQQ.getMobileQQ().peekAppRuntime()
            if (runtime != null && runtime.isLogin) {
                val service = runtime.getRuntimeService(IKernelService::class.java, "all")
                NTServiceFetcher.onFetch(service)
                MilkyEventDispatcher.onKernelServiceFetch(service)
            }
        }
    }
}
