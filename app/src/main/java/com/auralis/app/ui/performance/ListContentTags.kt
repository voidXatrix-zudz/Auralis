package com.auralis.app.ui.performance

/**
 * The `contentType` lambda for a homogeneous lazy list, honouring the profile's
 * [PerformanceProfile.listContentTypes] switch.
 *
 * Why this matters: a lazy list only reuses a composable for a *different* item when both the key
 * slot and the content-type slot recede out of the viewport together. Declaring the item type lets
 * Lazy keep one reusable slot per row shape instead of falling back to a single generic slot, which
 * is what re-inflates rows while scrolling a long library.
 *
 * The function type is `(Any?) -> Any?`, which Kotlin's function-type variance lets you pass
 * straight to `items(contentType = ...)` for any item type; when the flag is off it degrades to the
 * `{ null }` default, so behaviour is never worse than the platform default.
 */
fun PerformanceProfile.listContentType(tag: String): (Any?) -> Any? =
    if (listContentTypes) { _ -> tag } else { _ -> null }
