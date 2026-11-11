import { useEffect, useMemo, useState } from 'react'
import { Image, Pressable, ScrollView } from 'react-native'
import { Text, XStack, YStack } from 'tamagui'
import type { PdfViewerApi } from '@hbox/rn-pdf-viewer'

export interface ThumbnailSheetProps {
  api: PdfViewerApi
  /** Thumbnail width in pixels. */
  width?: number
}

export function ThumbnailSheet({ api, width = 120 }: ThumbnailSheetProps) {
  const [uris, setUris] = useState<Record<number, string>>({})
  const pages = useMemo(
    () => Array.from({ length: api.pageCount }, (_, index) => index + 1),
    [api.pageCount],
  )
  const { thumbnailUri } = api

  useEffect(() => {
    let cancelled = false
    for (const page of pages) {
      thumbnailUri(page, width)
        .then((uri) => {
          if (!cancelled) {
            setUris((current) => ({ ...current, [page]: uri }))
          }
        })
        .catch(() => {
          // A page that fails to render keeps its placeholder.
        })
    }
    return () => {
      cancelled = true
    }
  }, [thumbnailUri, pages, width])

  return (
    <ScrollView horizontal showsHorizontalScrollIndicator={false}>
      <XStack gap="$2" paddingHorizontal="$3" paddingVertical="$2">
        {pages.map((page) => {
          const uri = uris[page]
          return (
            <Pressable key={page} onPress={() => api.goTo(page)}>
              <YStack
                alignItems="center"
                gap="$1"
                borderWidth={api.page === page ? 2 : 1}
                borderColor={api.page === page ? '$blue10' : '$borderColor'}
                borderRadius="$2"
                padding="$1"
              >
                {uri ? (
                  <Image
                    source={{ uri: `file://${uri}` }}
                    style={{ width, height: width * 1.4 }}
                    resizeMode="contain"
                  />
                ) : (
                  <YStack width={width} height={width * 1.4} backgroundColor="$backgroundPress" />
                )}
                <Text fontSize="$1">{page}</Text>
              </YStack>
            </Pressable>
          )
        })}
      </XStack>
    </ScrollView>
  )
}
