import { NitroModules, getHostComponent } from 'react-native-nitro-modules'
import type { HybridViewMethods } from 'react-native-nitro-modules'
import type { PdfDocument } from './specs/PdfDocument.nitro'
import type { PdfPageViewProps } from './specs/PdfPageView.nitro'
import PdfPageViewConfig from '../nitrogen/generated/shared/json/PdfPageViewConfig.json'

export const createPdfDocument = (): PdfDocument =>
  NitroModules.createHybridObject<PdfDocument>('PdfDocument')

export const PdfPageView = getHostComponent<PdfPageViewProps, HybridViewMethods>(
  'PdfPageView',
  () => PdfPageViewConfig,
)

export type * from './specs/PdfDocument.nitro'
export type * from './specs/PdfPageView.nitro'
