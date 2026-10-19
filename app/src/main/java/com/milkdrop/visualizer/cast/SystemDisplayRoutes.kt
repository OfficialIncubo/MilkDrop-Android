package com.milkdrop.visualizer.cast

import android.content.Context
import android.media.MediaRouter
import android.view.Display

/** Public platform routing API, including routes omitted by a vendor's AndroidX provider. */
internal class SystemDisplayRoutes(private val context: Context) {
    private val router = context.getSystemService(MediaRouter::class.java)
    private fun routes() = (0 until router.routeCount).map(router::getRouteAt)
    fun names(display: Display): List<String> {
        val matching = routes().filter { it.presentationDisplay?.displayId == display.displayId }
        val selected = router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO)
        val active = selected.takeIf { it !== router.defaultRoute &&
            it.supportedTypes and MediaRouter.ROUTE_TYPE_LIVE_VIDEO != 0 &&
            (it.presentationDisplay?.displayId?.let { id -> id == display.displayId } ?: true) }
        return (matching + listOfNotNull(active)).map { it.getName(context).toString() }
    }

    fun disconnect(display: Display) = runCatching {
        // Selecting the public default video route asks Android to disconnect an active WFD.
        // Some OEMs leave it on the default route; associate an already-connected route first.
        val selected = router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO)
        if (selected === router.defaultRoute) {
            routes().firstOrNull { it !== router.defaultRoute && it.presentationDisplay?.displayId == display.displayId }
                ?.let { router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO, it) }
        }
        router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO, router.defaultRoute)
    }.isSuccess
}

internal object ReceiverNames {
    fun choose(candidates: List<String?>, fallback: String): String = candidates.firstOrNull { candidate ->
        val text = candidate?.trim().orEmpty()
        text.isNotEmpty() && text.lowercase(java.util.Locale.ROOT).filter(Char::isLetterOrDigit) !in
            setOf("castreceiver", "wirelessdisplay", "wifidisplay", "default", "phone")
    }?.trim() ?: fallback
}
