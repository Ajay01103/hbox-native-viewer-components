import type { PageSize } from '@hbox/react-native-nitro-docx'
import type { Zoom } from './types'

export interface Viewport {
  width: number
  height: number
}

const MIN_SCALE = 0.2
const MAX_SCALE = 6

export function fitWidthScale(page: PageSize, viewport: Viewport): number {
  if (!page.width || !viewport.width) return 1
  return viewport.width / page.width
}

export function fitPageScale(page: PageSize, viewport: Viewport): number {
  if (!page.width || !page.height || !viewport.width || !viewport.height) return 1
  return Math.min(viewport.width / page.width, viewport.height / page.height)
}

/**
 * `automatic` picks fit-width on narrow (phone) viewports and fit-page when the
 * viewport is wide enough to show a whole page beside the chrome.
 */
export function resolveScale(zoom: Zoom, page: PageSize, viewport: Viewport): number {
  const raw =
    typeof zoom === 'number'
      ? zoom
      : zoom === 'fit-width'
        ? fitWidthScale(page, viewport)
        : zoom === 'fit-page'
          ? fitPageScale(page, viewport)
          : viewport.width < 700
            ? fitWidthScale(page, viewport)
            : fitPageScale(page, viewport)
  if (!Number.isFinite(raw) || raw <= 0) return 1
  return Math.min(MAX_SCALE, Math.max(MIN_SCALE, raw))
}

export function zoomLabel(zoom: Zoom, scale: number): string {
  if (zoom === 'fit-width') return 'Fit width'
  if (zoom === 'fit-page') return 'Fit page'
  if (zoom === 'automatic') return 'Auto'
  return `${Math.round(scale * 100)}%`
}
