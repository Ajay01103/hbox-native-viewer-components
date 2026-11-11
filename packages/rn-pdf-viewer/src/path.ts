/** Strips a `file://` scheme; native code only accepts filesystem paths. */
export function toLocalPath(uri: string): string {
  if (!uri.startsWith('file://')) {
    return uri
  }
  const withoutScheme = uri.slice('file://'.length)
  try {
    return decodeURI(withoutScheme)
  } catch {
    return withoutScheme
  }
}
