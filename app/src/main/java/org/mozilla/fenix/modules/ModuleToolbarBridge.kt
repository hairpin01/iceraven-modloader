/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.modules

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import mozilla.components.compose.browser.toolbar.concept.Action
import mozilla.components.compose.browser.toolbar.store.BrowserDisplayToolbarAction
import mozilla.components.compose.browser.toolbar.store.BrowserDisplayToolbarAction.BrowserActionsEndUpdated
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarAction
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent.Source
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import org.mozilla.fenix.modules.api.ModuleToolbarAction

/**
 * A toolbar tap that belongs to a module rather than to the browser itself.
 *
 * Declaring it as an event lets the browser's own middleware observe taps the same way it observes its own buttons,
 * while the actual work is dispatched to the owning module through [ModuleManager].
 */
data class ModuleToolbarActionClicked(
    val actionId: String,
    override val source: Source,
) : BrowserToolbarEvent

/**
 * Keeps module buttons on the browser toolbar.
 *
 * The browser computes its own toolbar actions when the store is created, which is before modules have been read from
 * disk, so appending to that computation would never show a button. Instead this bridge merges module actions into
 * every `BrowserActionsStartUpdated` / `BrowserActionsEndUpdated` action that flows through the store, and replays the
 * last known browser action lists whenever [ModuleManager.toolbarActions] changes. Replaying the cached *base* lists is
 * what keeps the merge idempotent: buttons are rebuilt from scratch instead of appended twice.
 */
internal object ModuleToolbarBridge {

    private const val TAG = "GeckoLoaderToolbar"

    @VisibleForTesting
    @Volatile
    internal var context: Context? = null

    /** Browser actions without module buttons, as last dispatched by the browser middleware. */
    @Volatile
    private var lastBrowserActionsEnd: List<Action> = emptyList()

    private val attached = mutableSetOf<Store<BrowserToolbarState, BrowserToolbarAction>>()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** Merges module buttons into the toolbar and routes their taps back to the owning module. */
    val actionMiddleware: Middleware<BrowserToolbarState, BrowserToolbarAction> = { _, next, action ->
        when (action) {
            // Only the trailing slot is used: the browser leaves it empty on narrow screens, so it is the one place
            // where a button is guaranteed to be rendered on every device.
            is BrowserActionsEndUpdated -> {
                lastBrowserActionsEnd = action.actions
                next(
                    BrowserActionsEndUpdated(
                        action.actions + actions(context, Source.AddressBar.BrowserEnd),
                    ),
                )
            }

            is ModuleToolbarActionClicked -> {
                ModuleManager.invokeToolbarAction(action.actionId)
                next(action)
            }

            else -> next(action)
        }
    }

    /**
     * Binds the bridge to a toolbar store and keeps it in sync with the modules.
     *
     * Called once per browser screen when the store is built. Safe to call again for a new store, which happens after a
     * configuration change.
     */
    @Synchronized
    fun attach(context: Context, store: Store<BrowserToolbarState, BrowserToolbarAction>) {
        this.context = context.applicationContext

        val toolbarStore = store
        if (attached.add(toolbarStore)) {
            Log.i(TAG, "Listening for module toolbar buttons")
            scope.launch {
                ModuleManager.toolbarActions.drop(1).collect { refresh(toolbarStore) }
            }
        }

        refresh(store)
    }

    /**
     * Rebuilds the toolbar from the cached browser action lists plus the current module buttons.
     *
     * The dispatch passes through the bridge middleware again, which re-appends the module actions, and then through
     * the browser middleware, which recomputes its own lists from the unchanged browser state.
     */
    private fun refresh(store: Store<BrowserToolbarState, BrowserToolbarAction>) {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                store.dispatch(
                    BrowserActionsEndUpdated(lastBrowserActionsEnd),
                )
            }.onFailure { Log.w(TAG, "Could not refresh the toolbar with module buttons", it) }
        }
    }

    /**
     * Current module buttons for [source], as toolbar actions.
     *
     * The icon is optional: a module without one still gets a button, the toolbar draws its standard placeholder.
     */
    fun actions(context: Context?, source: BrowserToolbarEvent.Source): List<Action> {
        val resources: Resources? = context?.resources
        return ModuleManager.toolbarActions.value
            .filter { it.visible }
            .map { action -> toToolbarAction(action, resources, source) }
    }

    private fun toToolbarAction(
        action: ModuleToolbarAction,
        resources: Resources?,
        source: Source,
    ): Action = Action.ActionButton(
        drawable = action.icon?.let { icon -> BitmapDrawable(requireNotNull(resources), icon) },
        contentDescription = action.label,
        state = if (action.enabled) Action.ActionButton.State.DEFAULT else Action.ActionButton.State.DISABLED,
        onClick = ModuleToolbarActionClicked(action.id, source),
    )
}