package com.edureminder.quicktube.core.designsystem

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

/** Material 3 "emphasized" easing curves. */
object QtEasing {
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

/**
 * Screen transitions. Only translation/alpha/scale are animated (GPU layer properties),
 * so they don't trigger relayout of the screens and stay smooth on low-end phones.
 */
object QtTransitions {
    private const val IN = 340
    private const val OUT = 220

    val push: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(tween(IN, easing = QtEasing.EmphasizedDecelerate)) { it / 5 } + fadeIn(tween(IN - 80, 40))
    }
    val pushExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(tween(IN, easing = QtEasing.EmphasizedDecelerate)) { -it / 12 } + fadeOut(tween(OUT))
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(tween(IN, easing = QtEasing.EmphasizedDecelerate)) { -it / 12 } + fadeIn(tween(IN - 80))
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(tween(OUT + 40, easing = QtEasing.EmphasizedAccelerate)) { it / 5 } + fadeOut(tween(OUT))
    }

    /**
     * Bottom-tab switches are instant (as in SnapTube); the nav bar's sliding pill carries the motion.
     * A full-screen fade would force an off-screen layer every frame, which budget GPUs can't afford at 90 Hz.
     */
    val tabEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = { EnterTransition.None }
    val tabExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = { ExitTransition.None }
}
