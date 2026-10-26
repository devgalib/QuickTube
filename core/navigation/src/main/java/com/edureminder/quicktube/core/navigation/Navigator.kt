package com.edureminder.quicktube.core.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder

/** Each feature contributes its screens through one of these; the app wires them together. */
fun interface FeatureEntry {
    fun NavGraphBuilder.register(nav: NavController)
}

fun NavController.navigateSingle(route: Any) = navigate(route) { launchSingleTop = true }
