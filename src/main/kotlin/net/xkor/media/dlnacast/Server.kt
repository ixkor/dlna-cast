package net.xkor.media.dlnacast

import io.ktor.application.*
import io.ktor.features.AutoHeadResponse
import io.ktor.html.respondHtml
import io.ktor.http.ContentType
import io.ktor.http.content.static
import io.ktor.response.respondRedirect
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.ShutDownUrl
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.html.*
import org.fourthline.cling.UpnpServiceImpl
import org.fourthline.cling.model.meta.RemoteDevice
import org.fourthline.cling.model.types.UDAServiceId
import org.fourthline.cling.registry.DefaultRegistryListener
import org.fourthline.cling.registry.Registry
import org.fourthline.cling.support.model.TransportState
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

object Server {

    private val tvs = mutableMapOf<String, DeviceControl>()
    private val upnpService by lazy {
        UpnpServiceImpl(object : DefaultRegistryListener() {
            override fun remoteDeviceAdded(registry: Registry, device: RemoteDevice) = addOrUpdateDevice(device)
            override fun remoteDeviceUpdated(registry: Registry, device: RemoteDevice) = addOrUpdateDevice(device)
            override fun remoteDeviceRemoved(registry: Registry, device: RemoteDevice) {
                tvs[device.identity.udn.identifierString]?.release()
            }
        })
    }
    private var host: String = "0.0.0.0"
    private var port: Int = 8686
    private val server: NettyApplicationEngine by lazy { initHttpServer() }
    private val properties = Properties()
    private val playList = mutableListOf(
        PlayItem("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"),
        PlayItem("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"),
        PlayItem("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"),
        PlayItem("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4"),
        PlayItem("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4"),
        PlayItem("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyrides.mp4"),
        PlayItem("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerMeltdowns.mp4"),
        PlayItem("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4"),
        PlayItem("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/SubaruOutbackOnStreetAndDirt.mp4"),
        PlayItem("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4"),
        PlayItem("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/VolkswagenGTIReview.mp4"),
        PlayItem("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WeAreGoingOnBullrun.mp4"),
        PlayItem("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/WhatCarCanYouGetForAGrand.mp4")
    )

    fun execute(host: String = this.host, port: Int = this.port) {
        this.host = host
        this.port = port
        loadConfig()
        server.start(wait = true)
    }

    private fun initHttpServer(): NettyApplicationEngine {
        return embeddedServer(Netty, port, host) {
            routing {
                get("/") { call.respondRedirect("/status") }
                get("/status") { handleStatus() }
                get("/scan") {
                    scanDevices()
                    delay(2, TimeUnit.SECONDS)
                    call.respondRedirect("/status")
                }
                get("/play") { handlePlay() }
                get("/start-tracking") { handleStartTracking() }
                get("/stop-tracking") { handleStopTracking() }
                get("/play-list") { handlePlayList() }
                get("/play-list-edit") { handlePlayListEdit() }
                static("static") {
                    filesWithCustomContentType("static") {
                        when (it.extension.toLowerCase()) {
                            "mp4" -> ContentType.Video.MP4
                            else -> null
                        }
                    }
                }
            }
            install(ShutDownUrl.ApplicationCallFeature) {
                shutDownUrl = "/stop"
            }
            install(AutoHeadResponse)
            environment.monitor.subscribe(ApplicationStopPreparing) { upnpService.shutdown() }
            environment.monitor.subscribe(ApplicationStarted) { scanDevices() }
        }
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.handlePlayListEdit() {
        val action = call.parameters["action"]
        val index = call.parameters["index"]?.toIntOrNull() ?: throw IllegalArgumentException("index can not be null")
        val url = call.parameters["url"] ?: throw IllegalArgumentException("url can not be null")
        val duration = call.parameters["duration"]?.toLongOrNull()

        when (action) {
            "update" -> playList[index] = PlayItem(url, duration)
            "add" -> playList.add(PlayItem(url, duration))
            "remove" -> playList.removeAt(index)
            "up" -> {
                if (index == 0) {
                    playList.add(playList.removeAt(0))
                } else if (index < playList.size) {
                    playList.add(index - 1, playList.removeAt(index))
                }
            }
            "down" -> {
                if (index == playList.size - 1) {
                    playList.add(0, playList.removeAt(playList.size - 1))
                } else if (index < playList.size - 1) {
                    playList.add(index + 1, playList.removeAt(index))
                }
            }
        }

        call.respondRedirect("/play-list")
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.handlePlayList() = call.respondHtml {
        saveConfig()
        head { title("Play List") }
        body {
            ol { playList.forEachIndexed { i, playItem -> li { fillPlayListItem(i, playItem) } } }
            fillPlayListItem(-1, null)
        }
    }

    private fun FlowContent.fillPlayListItem(index: Int, playItem: PlayItem?) = form("/play-list-edit") {
        if (playItem == null) {
            b { +"New " }
        }
        input(InputType.hidden, name = "index") { value = index.toString() }
        label {
            +"Url: "
            input(InputType.url, name = "url") {
                value = playItem?.url.orEmpty()
                size = "100"
            }
        }
        label {
            +"  Duration (for pictures): "
            input(InputType.number, name = "duration") {
                value = playItem?.duration?.toString().orEmpty()
                style = "width: 4em"
            }
            +" seconds  "
        }
        if (playItem != null) {
            button(name = "action") { value = "update"; +"Update" }
            +"  "
            button(type = ButtonType.reset) { +"Reset" }
            +"  "
            button(name = "action") { value = "up"; +"Up" }
            +"  "
            button(name = "action") { value = "down"; +"Down" }
            +"  "
            button(name = "action") { value = "remove"; +"Delete" }
        } else {
            button(name = "action") { value = "add"; +"Add" }
        }
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.handleStatus() = call.respondHtml {
        saveConfig()
        head { title("Status") }
        body {
            a("/status") { +"Update" }
            +" | "
            a("/scan") { +"Rescan" }
            +" | "
            a("/play-list") { +"Edit Play List" }
            +" | "
            a("/stop") { +"Stop server" }
            ul { tvs.values.sortedBy { it.udn }.forEach { fillDeviceItem(it) } }
        }
    }

    private fun UL.fillDeviceItem(data: DeviceControl) = li {
        +"${data.name ?: data.device?.details?.friendlyName ?: "?"} "
        +"(${data.device?.details?.modelDetails?.modelName ?: "?"}, ${data.udn}) "
        if (data.device != null) {
            +" State: ${data.state?.name ?: "unknown".takeIf { data.tracked } ?: "not tracked"}"
            if (data.state == TransportState.PLAYING) {
                +", duration: ${data.currentDuration}"
            }
        } else {
            +" State: offline"
        }
        br
        +"Actions: "
        if (data.tracked) {
            a("/stop-tracking?udn=" + data.udn) { +"Stop tracking" }
//            data.availableActions?.forEach { action ->
//                +" | "
//                when (action) {
//                    TransportAction.Play -> a("/play?udn=" + data.udn) { +"Play" }
//                    else -> +action.name
//                }
//            }
//            if (data.availableActions.isNullOrEmpty() && data.state == TransportState.NO_MEDIA_PRESENT) {
//                +" | "
//                a("/play?udn=" + data.udn) { +"Play" }
//            }
        } else {
            a("/start-tracking?udn=" + data.udn) { +"Start tracking" }
        }
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.handleStartTracking() {
        val udn = call.parameters["udn"]
        tvs[udn]?.apply {
            tracked = true
            subscribe(upnpService, ::onDeviceEvent)
        }
        call.respondRedirect("/status")
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.handleStopTracking() {
        val udn = call.parameters["udn"]
        tvs[udn]?.apply {
            tracked = false
            unsubscribe()
            delay(1, TimeUnit.SECONDS) // wait to unsubscribe complete
        }
        call.respondRedirect("/status")
    }

    private fun onDeviceEvent(deviceControl: DeviceControl) {
        when (deviceControl.state) {
            TransportState.NO_MEDIA_PRESENT,
            TransportState.PAUSED_PLAYBACK,
            TransportState.STOPPED -> if (deviceControl.autoPlay && !deviceControl.playPreparing) {
                GlobalScope.launch {
                    val currentUrl = deviceControl.currentUri?.toString()
                    val index = (playList.indexOfFirst { it.url == currentUrl } + 1).rem(playList.size)
                    val nextUrl = playList[index].url
                    deviceControl.playPreparing = true
                    try {
                        upnpService.execute(
                            deviceControl.service,
                            "SetAVTransportURI",
                            "InstanceID" to 0,
                            "CurrentURI" to nextUrl
                        )
                        upnpService.execute(deviceControl.service, "Play", "InstanceID" to 0, "Speed" to "1")
//                    } catch (e: ActionInvocationException) {
//                        e.printStackTrace()
//                        onDeviceEvent(deviceControl) // try next
                    } finally {
                        deviceControl.playPreparing = false
                    }
                }
            }
            TransportState.PLAYING -> {
                val duration = deviceControl.currentDuration?.replace(":", "")?.toFloat()
                if (duration == 0f) {
                    val currentUrl = deviceControl.currentUri?.toString()
                    playList.find { it.url == currentUrl && it.duration != null }?.also {
                        GlobalScope.launch {
                            delay(it.duration!!, TimeUnit.SECONDS)
                            upnpService.execute(deviceControl.service, "Stop", "InstanceID" to 0)
                        }
                    }
                }
            }
        }
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.handlePlay() {
        val udn = call.parameters["udn"]
        val service = tvs[udn]?.service
        if (service != null) {
            upnpService.execute(
                service,
                "SetAVTransportURI",
                "InstanceID" to 0,
                "CurrentURI" to "http://clips.vorwaerts-gmbh.de/big_buck_bunny.mp4"
            )
            upnpService.execute(service, "Play", "InstanceID" to 0, "Speed" to "1")
        }
        call.respondRedirect("/status")
    }

    private fun scanDevices() {
        upnpService.controlPoint.search()
    }

    private fun addOrUpdateDevice(device: RemoteDevice) {
        if (device.type.type == "MediaRenderer") {
            val udn = device.identity.udn.identifierString
            tvs.getOrPut(udn) { DeviceControl(udn, properties) }.also {
                it.device = device
                it.service = device.findService(UDAServiceId("AVTransport"))
                saveConfig()
                if (it.tracked) GlobalScope.launch {
                    it.subscribe(upnpService, ::onDeviceEvent)
                }
            }
        }
    }

    @Synchronized
    private fun saveConfig() {
        properties["devices"] = tvs.keys.joinToString(",")
        properties["playList"] = playList.joinToString(";") { it.url + "," + (it.duration ?: "") }
        File("config.properties").outputStream().use {
            properties.store(it.writer(), null)
        }
    }

    private fun loadConfig() {
        File("config.properties").takeIf { it.exists() }?.apply {
            inputStream().use { properties.load(it.reader()) }
            properties["devices"]?.toString()?.split(',')?.forEach { udn ->
                tvs.getOrPut(udn) { DeviceControl(udn, properties) }
            }
            properties["playList"]?.toString()?.also {
                playList.clear()
                it.split(';').forEach {
                    val (url, duration) = it.split(',')
                    playList.add(PlayItem(url, duration.toLongOrNull()))
                }
            }
        }
    }

    data class PlayItem(val url: String, val duration: Long? = null)

}
