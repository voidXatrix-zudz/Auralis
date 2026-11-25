/*
 * AURALIS vendored copy of Kyant0/backdrop v1.0.0
 * Upstream: https://github.com/Kyant0/AndroidLiquidGlass (Apache License 2.0)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Local modifications (2026): package renamed to com.auralis.app.glass.backdrop;
 * the Kotlin context parameter in LayerRecorder.kt replaced by an explicit
 * parameter; @Language("AGSL") annotations dropped. See NOTICE.
 */

package com.auralis.app.glass.backdrop.effects

import android.graphics.RenderEffect
import android.os.Build
import com.auralis.app.glass.backdrop.BackdropEffectScope

fun BackdropEffectScope.effect(effect: RenderEffect) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

    val currentEffect = renderEffect
    renderEffect =
        if (currentEffect != null) {
            RenderEffect.createChainEffect(effect, currentEffect)
        } else {
            effect
        }
}

fun BackdropEffectScope.effect(effect: androidx.compose.ui.graphics.RenderEffect) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

    effect(effect.asAndroidRenderEffect())
}
