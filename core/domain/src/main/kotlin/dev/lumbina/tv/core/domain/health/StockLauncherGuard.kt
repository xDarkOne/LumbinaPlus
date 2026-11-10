package dev.lumbina.tv.core.domain.health

import dev.lumbina.tv.core.domain.access.LocalAdb
import dev.lumbina.tv.core.domain.device.PackageState
import dev.lumbina.tv.core.domain.device.PackageStates

sealed interface StockLauncherResult {
    /** Nothing to do: no such launcher, already off, or Lumbina is not meant to catch Home. */
    data object NotNeeded : StockLauncherResult

    /** Turning it off needs debugging over the network, as the other adb steps do. */
    data object NotConnected : StockLauncherResult
    data object Disabled : StockLauncherResult
    data object Refused : StockLauncherResult
}

/**
 * Hisense's own launcher ([STOCK_LAUNCHER], a user app) takes Home as soon as Lumbina's service is
 * not catching it, which shows the firmware's screen instead of Lumbina. While Lumbina is meant to
 * catch Home it is turned off, and Home is pointed at [ourHome]. Giving it back: `adb shell pm
 * enable com.oversea.aslauncher`, or removing Lumbina's service from the settings it was told.
 */
class StockLauncherGuard(
    private val packages: PackageStates,
    private val adb: LocalAdb,
    private val ourHome: () -> String?,
    private val interceptRemote: suspend () -> Boolean = { true },
) {
    suspend operator fun invoke(): StockLauncherResult {
        if (!interceptRemote()) return StockLauncherResult.NotNeeded
        if (packages.of(STOCK_LAUNCHER) != PackageState.Enabled) return StockLauncherResult.NotNeeded
        val home = ourHome() ?: return StockLauncherResult.NotNeeded
        // Pointed first: with the stock launcher gone and Home unset the system would ask which to use.
        if (adb.shell("cmd package set-home-activity $home") == null) return StockLauncherResult.NotConnected
        val out = adb.shell("pm disable-user --user 0 $STOCK_LAUNCHER") ?: return StockLauncherResult.NotConnected
        return if (out.contains("disabled")) StockLauncherResult.Disabled else StockLauncherResult.Refused
    }

    companion object {
        const val STOCK_LAUNCHER = "com.oversea.aslauncher"
    }
}
