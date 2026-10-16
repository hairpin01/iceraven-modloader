/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.modules

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import mozilla.components.compose.browser.toolbar.concept.Action
import mozilla.components.compose.browser.toolbar.concept.Action.ActionButton
import mozilla.components.compose.browser.toolbar.concept.Action.ActionButton.State
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarAction
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

/**
 * Toolbar event raised when a button contributed by a runtime module is tapped.
 *
 * `BrowserToolbarEvent` is the one interaction type that is open for extension, which is what lets modules reuse the
 * regular toolbar slots instead of getting a private rendering path.
 */
data class ModuleToolbarActionClicked(
    val actionId: String,
    override val source: BrowserToolbarEvent.Source,
) : BrowserToolbarEvent

/** Routes toolbar taps back into the module that contributed the button. */
class ModuleToolbarActionMiddleware : Middleware<BrowserToolbarState, BrowserToolbarAction> {
    override suspend fun process(
        action: BrowserToolbarAction,
        store: Store<BrowserToolbarState, BrowserToolbarAction>,
    ) {
        if (action is ModuleToolbarActionClicked) {
            ModuleManager.invokeToolbarAction(action.actionId)
        }
    }
}

/**
 * Builds the toolbar buttons modules registered, so `BrowserToolbarMiddleware` only has to append them to the
 * start or end action list.
 */
internal object ModuleToolbarBridge {

    fun actions(context: Context, source: BrowserToolbarEvent.Source): List<Action> =
        ModuleManager.toolbarActions.value
            .filter { action -> action.visible }
            .map { action ->
                ActionButton(
                    drawable = action.icon?.let { icon -> BitmapDrawable(context.resources, icon) },
                    contentDescription = action.label,
                    state = if (action.enabled) State.DEFAULT else State.DISABLED,
                    onClick = ModuleToolbarActionClicked(action.id, source),
                )
            }
}