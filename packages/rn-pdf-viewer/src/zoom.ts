import type { Zoom, Rotation } from './types'

export interface Size {
  width: number
  height: number
}

export function rotateSize(size: Size, rotation: Rotation): Size {
  return rotation === 90 || rotation === 270
    ? { width: size.height, height: size.width }
    : size
}

/**
 * Resolves a zoom mode to a concrete scale. `automatic` is fit-width on
 * portrait containers and fit-page on landscape or tablet sized containers.
 */
export function resolveScale(
  zoom: Zoom,
  pageSize: Size,
  containerSize: Size,
  rotation: Rotation = 0,
): number {
  if (typeof zoom === 'number') {
    return zoom
  }
  const { width, height } = rotateSize(pageSize, rotation)
  if (width <= 0 || height <= 0 || containerSize.width <= 0) {
    return 1
  }
  const fitWidth = containerSize.width / width
  const fitPage = Math.min(fitWidth, containerSize.height / height)

  switch (zoom) {
    case 'fit-page':
      return fitPage
    case 'fit-width':
      return fitWidth
    case 'automatic':
      return containerSize.width > containerSize.height ? fitPage : fitWidth
    default:
      return fitWidth
  }
}

export function rotateBy(rotation: Rotation, dir: 1 | -1): Rotation {
  const next = (rotation + dir * 90 + 360) % 360
  return next as Rotation
}
