package net.xkor.media.dlnacast

import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.http.content.LocalFileContent
import io.ktor.http.content.staticRootFolder
import io.ktor.http.defaultForFile
import io.ktor.response.respond
import io.ktor.routing.Route
import io.ktor.routing.get
import io.ktor.util.combineSafe
import org.fourthline.cling.UpnpService
import org.fourthline.cling.controlpoint.ActionCallback
import org.fourthline.cling.model.action.ActionInvocation
import org.fourthline.cling.model.gena.GENASubscription
import org.fourthline.cling.model.message.UpnpResponse
import org.fourthline.cling.model.meta.Service
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class ActionInvocationException(
    val invocation: ActionInvocation<*>,
    val operation: UpnpResponse?,
    defaultMsg: String?
) : RuntimeException(
    (listOfNotNull(defaultMsg, invocation.action?.name?.let { "Action: $it" }) +
            invocation.input.map { "\t" + it.argument.name + ": " + it.value }).joinToString("\n")
)

class SubscriptionException(
    val subscription: GENASubscription<*>,
    val responseStatus: UpnpResponse?,
    exception: Exception?,
    defaultMsg: String?
) : RuntimeException(defaultMsg, exception)

suspend fun UpnpService.execute(action: ActionInvocation<*>): ActionInvocation<*> = suspendCoroutine {
    controlPoint.execute(object : ActionCallback(action) {
        override fun failure(invocation: ActionInvocation<*>, operation: UpnpResponse?, defaultMsg: String?) {
            it.resumeWithException(ActionInvocationException(invocation, operation, defaultMsg))
        }

        override fun success(invocation: ActionInvocation<out Service<*, *>>) = it.resume(invocation)
    })
}

suspend fun UpnpService.execute(
    service: Service<*, *>?,
    actionName: String,
    vararg arguments: Pair<String, Any>
) = service?.getAction(actionName)?.let { action ->
    val actionInvocation = ActionInvocation(action)
    arguments.forEach {
        actionInvocation.setInput(it.first, it.second.toString())
    }
    execute(actionInvocation)
}

private const val pathParameterName = "static-content-path-parameter"

private fun File?.combine(file: File) = when {
    this == null -> file
    else -> resolve(file)
}

fun Route.filesWithCustomContentType(
    folder: String,
    contentTypeResolver: (File) -> ContentType? = { null }
) = filesWithCustomContentType(File(folder), contentTypeResolver)

fun Route.filesWithCustomContentType(
    folder: File,
    contentTypeResolver: (File) -> ContentType? = { null }
) {
    val dir = staticRootFolder.combine(folder)
    get("{$pathParameterName...}") {
        val relativePath = call.parameters.getAll(pathParameterName)?.joinToString(File.separator) ?: return@get
        val file = dir.combineSafe(relativePath)
        if (file.isFile) {
            call.respond(LocalFileContent(file, contentTypeResolver(file) ?: ContentType.defaultForFile(file)))
        }
    }
}
