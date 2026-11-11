import { NitroModules, getHostComponent } from 'react-native-nitro-modules'
import type { HybridViewMethods } from 'react-native-nitro-modules'
import type { DocxEngine } from './specs/DocxEngine.nitro'
import type { DocxViewProps } from './specs/DocxView.nitro'
import type { DocxThumbnailProps } from './specs/DocxThumbnail.nitro'
import DocxViewConfig from '../nitrogen/generated/shared/json/DocxViewConfig.json'
import DocxThumbnailConfig from '../nitrogen/generated/shared/json/DocxThumbnailConfig.json'

export const createDocxEngine = (): DocxEngine =>
  NitroModules.createHybridObject<DocxEngine>('DocxEngine')

export const DocxView = getHostComponent<DocxViewProps, HybridViewMethods>(
  'DocxView',
  () => DocxViewConfig,
)

export const DocxThumbnail = getHostComponent<DocxThumbnailProps, HybridViewMethods>(
  'DocxThumbnail',
  () => DocxThumbnailConfig,
)

export type * from './specs/types'
export type * from './specs/DocxEngine.nitro'
export type * from './specs/DocxView.nitro'
export type * from './specs/DocxThumbnail.nitro'
