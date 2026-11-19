import { NitroModules, getHostComponent } from 'react-native-nitro-modules'
import type { HybridViewMethods } from 'react-native-nitro-modules'
import type { DocxEngine } from './specs/DocxEngine.nitro'
import type { DocxViewProps } from './specs/DocxView.nitro'
import type { DocxThumbnailProps } from './specs/DocxThumbnail.nitro'
import type { DocxEditorViewProps } from './specs/DocxEditorView.nitro'
import DocxViewConfig from '../nitrogen/generated/shared/json/DocxViewConfig.json'
import DocxThumbnailConfig from '../nitrogen/generated/shared/json/DocxThumbnailConfig.json'
import DocxEditorViewConfig from '../nitrogen/generated/shared/json/DocxEditorViewConfig.json'

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

/**
 * The complete DOCX viewer: native toolbar, tab strip, page/outline/comment
 * panels and the native page canvas in one host view.
 */
export const DocxEditorView = getHostComponent<DocxEditorViewProps, HybridViewMethods>(
  'DocxEditorView',
  () => DocxEditorViewConfig,
)

export type * from './specs/types'
export type * from './specs/DocxEngine.nitro'
export type * from './specs/DocxView.nitro'
export type * from './specs/DocxThumbnail.nitro'
export type * from './specs/DocxEditorView.nitro'
