package com.margelo.nitro.hboxdocx

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.uimanager.ViewManager
import com.margelo.nitro.hboxdocx.views.HybridDocxThumbnailManager
import com.margelo.nitro.hboxdocx.views.HybridDocxViewManager

/**
 * React Native entry point for the module.
 *
 * Nitro registers its HybridObjects itself, so this package exposes no native
 * modules — it exists so autolinking finds a `ReactPackage`, and loading the
 * `HboxNitroDocx` shared library runs `JNI_OnLoad` → `registerAllNatives()`.
 */
class HboxNitroDocxPackage : BaseReactPackage() {
  init {
    HboxNitroDocxOnLoad.initializeNative()
  }

  override fun getModule(
    name: String,
    reactContext: ReactApplicationContext,
  ): NativeModule? = null

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider {
    return ReactModuleInfoProvider { emptyMap() }
  }

  override fun createViewManagers(
    reactContext: ReactApplicationContext,
  ): List<ViewManager<*, *>> {
    return listOf(HybridDocxViewManager(), HybridDocxThumbnailManager())
  }
}
