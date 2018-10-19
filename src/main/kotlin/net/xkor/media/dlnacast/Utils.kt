package net.xkor.media.dlnacast

import org.fourthline.cling.UpnpService
import org.fourthline.cling.controlpoint.ActionCallback
import org.fourthline.cling.model.action.ActionInvocation
import org.fourthline.cling.model.gena.GENASubscription
import org.fourthline.cling.model.message.UpnpResponse
import org.fourthline.cling.model.meta.Service
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
