import { useState } from 'react'
import { Button, Input, Text, XStack } from 'tamagui'
import type { PdfViewerApi } from '@hbox/rn-pdf-viewer'

export interface SearchBarProps {
  api: PdfViewerApi
}

export function SearchBar({ api }: SearchBarProps) {
  const [query, setQuery] = useState('')

  return (
    <XStack alignItems="center" gap="$2" paddingHorizontal="$3" paddingVertical="$2">
      <Input
        flex={1}
        value={query}
        onChangeText={setQuery}
        placeholder="Search document"
        returnKeyType="search"
        onSubmitEditing={() => void api.search(query)}
      />
      <Button onPress={() => void api.search(query)}>Find</Button>
      <Text fontSize="$2">
        {api.hits.length > 0 ? `${api.hits.length} hits` : 'No hits'}
      </Text>
      <Button disabled={api.hits.length === 0} onPress={api.prevHit}>
        ↑
      </Button>
      <Button disabled={api.hits.length === 0} onPress={api.nextHit}>
        ↓
      </Button>
    </XStack>
  )
}
