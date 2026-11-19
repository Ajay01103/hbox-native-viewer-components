import { NitroModules, getHostComponent } from 'react-native-nitro-modules'
import type { HybridViewMethods } from 'react-native-nitro-modules'
import type { PdfDocument } from './specs/PdfDocument.nitro'
import type { PdfPageViewProps } from './specs/PdfPageView.nitro'
import type { PdfEditorViewProps } from './specs/PdfEditorView.nitro'
import PdfPageViewConfig from '../nitrogen/generated/shared/json/PdfPageViewConfig.json'
import PdfEditorViewConfig from '../nitrogen/generated/shared/json/PdfEditorViewConfig.json'

export const createPdfDocument = (): PdfDocument =>
  NitroModules.createHybridObject<PdfDocument>('PdfDocument')

export const PdfPageView = getHostComponent<PdfPageViewProps, HybridViewMethods>(
  'PdfPageView',
  () => PdfPageViewConfig,
)

/**
 * The all-native PDF viewer and editor surface: app bar, tool strip, tool
 * options, bottom sheets and comment threads are painted by the platform.
 */
export const PdfEditorView = getHostComponent<PdfEditorViewProps, HybridViewMethods>(
  'PdfEditorView',
  () => PdfEditorViewConfig,
)

export * from './annotations'
export type * from './specs/PdfDocument.nitro'
export type * from './specs/PdfPageView.nitro'
export type * from './specs/PdfEditorView.nitro'
