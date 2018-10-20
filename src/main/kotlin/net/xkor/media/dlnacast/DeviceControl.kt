package net.xkor.media.dlnacast

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.fourthline.cling.UpnpService
import org.fourthline.cling.controlpoint.SubscriptionCallback
import org.fourthline.cling.model.gena.CancelReason
import org.fourthline.cling.model.gena.GENASubscription
import org.fourthline.cling.model.message.UpnpResponse
import org.fourthline.cling.model.meta.Device
import org.fourthline.cling.model.meta.Service
import org.fourthline.cling.support.avtransport.lastchange.AVTransportLastChangeParser
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable
import org.fourthline.cling.support.lastchange.EventedValue
import org.fourthline.cling.support.lastchange.LastChange
import org.fourthline.cling.support.model.TransportAction
import org.fourthline.cling.support.model.TransportState
import java.net.URI
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.reflect.KClass

class DeviceControl(val udn: String, properties: Properties) {

    private val storage = PropertiesDelegate(properties, "$udn.")

    var tracked: Boolean by storage.default(false)
    var autoPlay: Boolean by storage.default(true)
    var name: String? by storage

    var device: Device<*, *, *>? = null
    var service: Service<*, *>? = null
    @Volatile
    var subscriptionCallback: SubscriptionCallback? = null
    private val subscription: GENASubscription<*>? get() = subscriptionCallback?.subscription
    @Volatile
    var playPreparing = false
    @Volatile
    var currentPlayItemIndex = -1
    @Volatile
    var hasAutoStop = false

    var lastChange: LastChange? = null
        private set

    var availableActions: Array<TransportAction>? = null
        private set
    var state: TransportState? = null
        private set
    var currentUri: URI? = null
        private set
    var currentDuration: String? = null
        private set
//    var timePosition: String? = null
//        private set

    fun release() {
        device = null
        service = null
        subscriptionCallback?.end()
        subscriptionCallback = null
        currentPlayItemIndex = -1
        hasAutoStop = false
        playPreparing = false
        onLastStateChanged()
    }

    fun onLastStateChanged() {
        lastChange = subscription?.run {
            LastChange(AVTransportLastChangeParser(), currentValues["LastChange"].toString())
        }
        availableActions = getLastValue(AVTransportVariable.CurrentTransportActions::class, availableActions)
        state = getLastValue(AVTransportVariable.TransportState::class, state)
        currentUri = getLastValue(AVTransportVariable.CurrentTrackURI::class, currentUri)
//        timePosition = getLastValue(AVTransportVariable.AbsoluteTimePosition::class, timePosition)
//        timePosition = getLastValue(AVTransportVariable.AbsoluteCounterPosition::class, timePosition)
        currentDuration = getLastValue(AVTransportVariable.CurrentTrackDuration::class, currentDuration)
    }

    private fun <T> getLastValue(valueClass: KClass<out EventedValue<T>>, prevValue: T? = null): T? =
        lastChange?.let { it.getEventedValue(0, valueClass.java)?.value ?: prevValue }

    suspend fun subscribe(
        upnpService: UpnpService,
        onEvent: (DeviceControl) -> Unit = {}
    ): Unit = suspendCancellableCoroutine { continuation ->
        if (subscriptionCallback != null || service == null) {
            continuation.resume(Unit)
        } else {
            subscriptionCallback = SubscriptionCallbackImpl(continuation, onEvent).also {
                upnpService.controlPoint.execute(it)
            }
        }
    }

    fun unsubscribe() {
        subscriptionCallback?.end()
    }

    inner class SubscriptionCallbackImpl(
        private val continuation: CancellableContinuation<Unit>,
        private val onEvent: (DeviceControl) -> Unit
    ) : SubscriptionCallback(service) {

        override fun established(subscription: GENASubscription<*>) {
            if (continuation.isActive) {
                continuation.resume(Unit)
            }
        }

        override fun eventReceived(subscription: GENASubscription<*>) {
            onLastStateChanged()
            onEvent(this@DeviceControl)
        }

        override fun ended(
            subscription: GENASubscription<*>,
            reason: CancelReason?,
            responseStatus: UpnpResponse?
        ) {
            subscriptionCallback = null
        }

        override fun eventsMissed(subscription: GENASubscription<*>, numberOfMissedEvents: Int) {}

        override fun failed(
            subscription: GENASubscription<*>,
            responseStatus: UpnpResponse?,
            exception: Exception?,
            defaultMsg: String?
        ) {
            subscriptionCallback = null
            if (continuation.isActive) {
                continuation.resumeWithException(
                    SubscriptionException(subscription, responseStatus, exception, defaultMsg)
                )
            }
        }

    }

}
