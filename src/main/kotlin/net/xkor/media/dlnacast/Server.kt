package net.xkor.media.dlnacast

import com.typesafe.config.ConfigFactory
import io.ktor.application.*
import io.ktor.auth.Authentication
import io.ktor.auth.UserIdPrincipal
import io.ktor.auth.authenticate
import io.ktor.auth.basic
import io.ktor.config.tryGetString
import io.ktor.features.AutoHeadResponse
import io.ktor.html.respondHtml
import io.ktor.http.ContentType
import io.ktor.http.content.LocalFileContent
import io.ktor.http.defaultForFile
import io.ktor.response.respond
import io.ktor.response.respondRedirect
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.ShutDownUrl
import io.ktor.server.netty.EngineMain
import io.ktor.util.combineSafe
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

    private const val pathParameterName = "static-content-path-parameter"

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
    private var host: String = "0.0.0.0"
    private var port: String = "8686"
    private var localPath: String = "static"
    private var debug = false

    fun execute(args: Array<String>) {
        loadConfig()
        val argsWithDefs = args + arrayOf(
            "-config=application.conf"
        )
        EngineMain.main(argsWithDefs)
    }

    fun module(application: Application) = application.apply {
        debug = application.environment.config.propertyOrNull("ktor.deployment.debug")?.getString()?.toBoolean() ?:
                false
        install(Authentication) {
            basic {
                realm = "DLNA-Cast"
                val login = application.environment.config.propertyOrNull("ktor.auth.login")?.getString()
                val password = application.environment.config.propertyOrNull("ktor.auth.password")?.getString()
                validate { credentials ->
                    if ((login == null || credentials.name == login) &&
                        (password == null || credentials.password == password)
                    ) {
                        UserIdPrincipal(credentials.name)
                    } else {
                        null
                    }
                }
            }
        }
        routing {
            get("/") { call.respondRedirect("/status") }
            authenticate {
                get("/status") { handleStatus() }
                get("/scan") {
                    scanDevices()
                    delay(TimeUnit.SECONDS.toMillis(2))
                    call.respondRedirect("/status")
                }
                get("/play") { handlePlay() }
                get("/start-tracking") { handleStartTracking() }
                get("/stop-tracking") { handleStopTracking() }
                get("/play-list") { handlePlayList() }
                get("/play-list-edit") { handlePlayListEdit() }
                get("/play-list-configure") { handlePlayListConfigure() }
                get("/actions") { handleActions() }
                get("/exec-action") { handleExecAction() }
            }
            get("/static/{$pathParameterName...}") { handleStatic() }
        }
        install(ShutDownUrl.ApplicationCallFeature) { shutDownUrl = "/stop" }
        install(AutoHeadResponse)
        environment.monitor.subscribe(ApplicationStopPreparing) { upnpService.shutdown() }
        environment.monitor.subscribe(ApplicationStarted) { scanDevices() }
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.handleStatic() {
        val relativePath = call.parameters.getAll(pathParameterName)?.joinToString(File.separator) ?: return
        val dir = File(localPath)
        val file = dir.combineSafe(relativePath)
        if (file.isFile) {
            val contentType = when (file.extension.toLowerCase()) {
                "mp4" -> ContentType.Video.MP4
                else -> io.ktor.http.ContentType.defaultForFile(file)
            }
            call.respond(LocalFileContent(file, contentType))
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

    private suspend fun PipelineContext<Unit, ApplicationCall>.handlePlayListConfigure() {
        host = call.parameters["host"] ?: throw IllegalArgumentException("host can not be null")
        val newPath = call.parameters["path"] ?: throw IllegalArgumentException("path can not be null")
        if (!File(newPath).isDirectory) {
            throw IllegalArgumentException("path '$newPath' is not a directory")
        }
        localPath = newPath

        call.respondRedirect("/play-list")
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.handlePlayList() = call.respondHtml {
        saveConfig()
        head { title("Play List") }
        body {
            form("/play-list-configure") {
                a("/status") { +"Status" }
                +" | "
                +"Server host name: "
                input(InputType.text, name = "host") {
                    value = host
                    size = "20"
                }
                +"  Path to local files: "
                input(InputType.text, name = "path") {
                    value = localPath
                    size = "50"
                }
                +"  "
                button(type = ButtonType.submit) { +"Update" }
                +"  "
                button(type = ButtonType.reset) { +"Reset" }
            }
            ol { playList.forEachIndexed { i, playItem -> li { fillPlayListItem(i, playItem) } } }
            fillPlayListItem(-1, null)
            val dir = File(localPath)
            if (dir.isDirectory) {
                val notAdded = dir.walk().asSequence().filter { file ->
                    !playList.any { it.url == file.relativeTo(dir).toString() } && !file.isHidden && file.isFile
                }.take(100).map { PlayItem(it.relativeTo(dir).toString()) }.toList()
                if (notAdded.isNotEmpty()) {
                    +"Founded in local path:"
                    notAdded.forEach { fillPlayListItem(-1, it) }
                }
            }
        }
    }

    private fun FlowContent.fillPlayListItem(index: Int, playItem: PlayItem?) = form("/play-list-edit") {
        if (playItem == null) {
            b { +"New " }
        }
        input(InputType.hidden, name = "index") { value = index.toString() }
        label {
            +"Url: "
            input(InputType.text, name = "url") {
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
        if (playItem != null && index != -1) {
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
            a("/status") { +"Refresh" }
            +" | "
            a("/scan") { +"Rescan" }
            +" | "
            a("/play-list") { +"Edit Play List" }
            +" | "
            a("/stop") { +"Stop server" }
            ul { tvs.values.sortedBy { it.udn }.forEach { fillDeviceItem(it) } }
        }
    }

    private fun UL.fillDeviceItem(control: DeviceControl) = li {
        +"${control.name ?: control.device?.details?.friendlyName ?: "?"} "
        +"(${control.device?.details?.modelDetails?.modelName ?: "?"}, ${control.udn}) "
        if (control.device != null) {
            +" State: ${control.state?.name ?: "unknown".takeIf { control.tracked } ?: "auto play disabled"}"
            if (control.state == TransportState.PLAYING && control.currentPlayItemIndex in 0 until playList.size) {
                +", ${playList[control.currentPlayItemIndex].url} (${control.currentDuration})"
            }
        } else {
            +" State: offline"
        }
        br
        +"Actions: "
        if (control.tracked) {
            a("/stop-tracking?udn=" + control.udn) { +"Turn off auto play" }
        } else {
            a("/start-tracking?udn=" + control.udn) { +"Turn on auto play" }
        }
        if (control.service != null && debug) {
            +" | "
            a("/actions?udn=" + control.udn) { +"See all AVTransport actions" }
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
            delay(TimeUnit.SECONDS.toMillis(1)) // wait to unsubscribe complete
        }
        call.respondRedirect("/status")
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.handleExecAction() {
        val udn = call.parameters["udn"] ?: throw IllegalArgumentException("udn can not be null")
        val name = call.parameters["name"] ?: throw IllegalArgumentException("name can not be null")
        var exception: ActionInvocationException? = null
        val invocation = tvs[udn]?.service?.let { service ->
            val action = service.getAction(name) ?: throw IllegalStateException("no action with name $name")
            val params = action.inputArguments.orEmpty().map { it.name to call.parameters[it.name] }.toTypedArray()
            try {
                upnpService.execute(service, name, *params as Array<Pair<String, Any>>)
            } catch (error: ActionInvocationException) {
                exception = error
                error.invocation
            }
        }
        call.respondHtml {
            head { title("Execute action $name") }
            body {
                +"Execute action $name:"
                exception?.also {
                    +" Fail: ${it.message}"
                } ?: also {
                    +" Success"
                }
                invocation?.outputMap?.forEach { name, value ->
                    br
                    +"$name: $value"
                }
            }
        }
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.handleActions() {
        val udn = call.parameters["udn"] ?: throw IllegalArgumentException("udn can not be null")
        call.respondHtml {
            head { title("Actions") }
            body {
                ul {
                    tvs[udn]?.service?.actions?.sortedBy { it.name }?.forEach { action ->
                        li {
                            form("exec-action") {
                                target = "_blank"
                                input(InputType.hidden, name = "udn") { value = udn }
                                input(InputType.hidden, name = "name") { value = action.name }
                                +"${action.name} "
                                button { +"Execute" }
                                if (!action.outputArguments.isEmpty()) {
                                    +" (Returns: ${action.outputArguments?.joinToString { it.name }})"
                                }
                                br
                                action.inputArguments?.forEach { argument ->
                                    label {
                                        +"\t"
                                        input(InputType.text, name = argument.name) {
                                            placeholder = argument.datatype.displayString
                                        }
                                        +" (${argument.name})"
                                    }
                                    br
                                }
                            }
                            br
                        }
                    }
                }
            }
        }
    }

    private fun onDeviceEvent(deviceControl: DeviceControl) {
        when (deviceControl.state) {
            TransportState.NO_MEDIA_PRESENT,
            TransportState.PAUSED_PLAYBACK,
            TransportState.STOPPED -> if (deviceControl.autoPlay && !deviceControl.playPreparing) {
                deviceControl.hasAutoStop = false
                GlobalScope.launch {
                    deviceControl.currentPlayItemIndex = (deviceControl.currentPlayItemIndex + 1).rem(playList.size)
                    val index = deviceControl.currentPlayItemIndex
                    val nextUrl = playList[index].absoluteUrl
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
                if (duration == 0f && !deviceControl.hasAutoStop) {
//                    val currentUrl = deviceControl.currentUri?.toString()
//                    playList.find { it.absoluteUrl == currentUrl && it.duration != null }?.also {
                    if (deviceControl.currentPlayItemIndex == -1) {
                        GlobalScope.launch {
                            upnpService.execute(deviceControl.service, "Stop", "InstanceID" to 0)
                        }
                    } else playList.getOrNull(deviceControl.currentPlayItemIndex)?.also {
                        deviceControl.hasAutoStop = true
                        GlobalScope.launch {
                            delay(TimeUnit.SECONDS.toMillis(it.duration ?: 10))
                            if (it.duration != null ||
                                deviceControl.currentDuration?.replace(":", "")?.toFloat() == 0f
                            ) {
                                try {
                                    upnpService.execute(deviceControl.service, "Stop", "InstanceID" to 0)
                                } finally {
                                    deviceControl.hasAutoStop = false
                                }
                            }
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
        properties["host"] = host
        properties["path"] = localPath

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
            properties["path"]?.toString()?.also { localPath = it }

            val config = ConfigFactory.parseFile(File("application.conf"))
            host = properties["host"]?.toString() ?: (config.tryGetString("ktor.deployment.host") ?: "0.0.0.0")
                .replace("0.0.0.0", "127.0.0.1")
            port = config.tryGetString("ktor.deployment.port") ?: "8686"
        }
    }

    private val httpSchemeMather = "^https?://.*".toRegex()
    private val PlayItem.absoluteUrl get() = if (httpSchemeMather.matches(url)) url else "http://$host:$port/static/$url"

    data class PlayItem(val url: String, val duration: Long? = null)

}

fun Application.module() = Server.module(this)
