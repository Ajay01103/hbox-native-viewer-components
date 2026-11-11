import type { DocxPalette } from '@hbox/react-native-nitro-docx'

/**
 * Neutral shadcn-style tokens that the native side needs as concrete colors.
 * Exported so host apps can merge them into their own theme.
 */
export const docxThemeExtension = {
  docxAppBg: { light: '#f4f4f5', dark: '#18181b' },
  docxPageBg: { light: '#ffffff', dark: '#1f1f23' },
  docxPageBorder: { light: '#e4e4e7', dark: '#2e2e33' },
  docxText: { light: '#18181b', dark: '#e4e4e7' },
  docxMuted: { light: '#71717a', dark: '#a1a1aa' },
  docxSelection: { light: 'rgba(59,130,246,0.30)', dark: 'rgba(96,165,250,0.35)' },
  docxCommentHighlight: { light: 'rgba(250,204,21,0.35)', dark: 'rgba(250,204,21,0.25)' },
  docxInsert: { light: '#16a34a', dark: '#4ade80' },
  docxDelete: { light: '#dc2626', dark: '#f87171' },
} as const

type ExtensionKey = keyof typeof docxThemeExtension

export interface PaletteOverrides {
  appBg?: string
  pageBg?: string
  pageBorder?: string
  text?: string
  muted?: string
  selection?: string
  commentHighlight?: string
  insertColor?: string
  deleteColor?: string
}

const OVERRIDE_KEYS: Record<keyof PaletteOverrides, ExtensionKey> = {
  appBg: 'docxAppBg',
  pageBg: 'docxPageBg',
  pageBorder: 'docxPageBorder',
  text: 'docxText',
  muted: 'docxMuted',
  selection: 'docxSelection',
  commentHighlight: 'docxCommentHighlight',
  insertColor: 'docxInsert',
  deleteColor: 'docxDelete',
}

export const LIGHT_PALETTE: DocxPalette = {
  appBg: '#f4f4f5',
  pageBg: '#ffffff',
  pageBorder: '#e4e4e7',
  text: '#18181b',
  muted: '#71717a',
  selection: 'rgba(59,130,246,0.30)',
  commentHighlight: 'rgba(250,204,21,0.35)',
  insertColor: '#16a34a',
  deleteColor: '#dc2626',
  pageDarkMode: false,
}

export const DARK_PALETTE: DocxPalette = {
  appBg: '#18181b',
  pageBg: '#1f1f23',
  pageBorder: '#2e2e33',
  text: '#e4e4e7',
  muted: '#a1a1aa',
  selection: 'rgba(96,165,250,0.35)',
  commentHighlight: 'rgba(250,204,21,0.25)',
  insertColor: '#4ade80',
  deleteColor: '#f87171',
  pageDarkMode: true,
}

/**
 * Resolves the palette handed to the native painter. Host theme values are
 * optional: without them the neutral defaults above are used, so the viewer
 * works with any Tamagui config.
 */
export function resolvePalette(isDark: boolean, overrides: PaletteOverrides = {}): DocxPalette {
  const base = isDark ? DARK_PALETTE : LIGHT_PALETTE
  const resolved: DocxPalette = { ...base }
  for (const key of Object.keys(overrides) as Array<keyof PaletteOverrides>) {
    const value = overrides[key]
    if (value) {
      resolved[key] = value
    }
  }
  return resolved
}

export function paletteForMode(pageDarkMode: boolean, base: DocxPalette): DocxPalette {
  return { ...base, pageDarkMode }
}
