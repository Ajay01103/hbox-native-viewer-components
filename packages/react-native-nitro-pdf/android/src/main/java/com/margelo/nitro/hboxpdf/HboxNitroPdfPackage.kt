package com.margelo.nitro.hboxpdf

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.uimanager.ViewManager
import com.margelo.nitro.hboxpdf.views.HybridPdfEditorViewManager
import com.margelo.nitro.hboxpdf.views.HybridPdfPageViewManager

/**
 * React Native entry point for the module.
 *
 * Nitro registers its HybridObjects itself, so this package exposes no native
 * modules — it exists for two reasons:
 *  1. React Native / Expo autolinking only treats an Android library as a native
 *     module when it finds a `ReactPackage` implementation.
 *  2. Loading the `HboxNitroPdf` shared library runs `JNI_OnLoad`, which calls
 *     `registerAllNatives()` and registers every HybridObject.
 */
class HboxNitroPdfPackage : BaseReactPackage() {
  init {
    HboxNitroPdfOnLoad.initializeNative()
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
    return listOf(HybridPdfPageViewManager(), HybridPdfEditorViewManager())
  }
}
