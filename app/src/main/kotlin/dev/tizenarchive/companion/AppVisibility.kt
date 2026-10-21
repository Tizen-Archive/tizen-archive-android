package dev.tizenarchive.companion

/** Whether an activity of the app is on screen: only then may the app open the system's install confirmation itself. */
internal object AppVisibility {
    @Volatile
    var inFront = false
}
