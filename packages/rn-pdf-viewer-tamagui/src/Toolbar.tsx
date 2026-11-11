import { Button, Text, XStack } from 'tamagui'
import type { PdfViewerApi } from '@hbox/rn-pdf-viewer'

export interface ToolbarProps {
  api: PdfViewerApi
  showRotateControls?: boolean
  onDownload?: () => void
}

/** Toolbar skin built on the consumer's Tamagui config. */
export function Toolbar({ api, showRotateControls = false, onDownload }: ToolbarProps) {
  return (
    <XStack
      alignItems="center"
      gap="$2"
      paddingHorizontal="$3"
      paddingVertical="$2"
      backgroundColor="$background"
    >
      <Button disabled={api.page <= 1} onPress={() => api.goTo(api.page - 1)}>
        Prev
      </Button>
      <Text fontSize="$3">
        {api.page} / {api.pageCount}
      </Text>
      <Button
        disabled={api.page >= api.pageCount}
        onPress={() => api.goTo(api.page + 1)}
      >
        Next
      </Button>
      {showRotateControls ? (
        <Button onPress={() => api.rotate(1)}>Rotate</Button>
      ) : null}
      {onDownload ? <Button onPress={onDownload}>Download</Button> : null}
    </XStack>
  )
}
