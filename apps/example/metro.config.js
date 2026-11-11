const { getDefaultConfig } = require('expo/metro-config')
const fs = require('fs')
const path = require('path')

const projectRoot = __dirname
const workspaceRoot = path.resolve(projectRoot, '../..')

const config = getDefaultConfig(projectRoot)

config.watchFolders = [workspaceRoot]

// On Windows pnpm's virtual store lives on a short path (C:\hbn\.pnpm, see
// pnpm-workspace.yaml) because AGP/CMake artifact paths otherwise exceed
// MAX_PATH. Metro only reads files inside watchFolders, and symlinked packages
// resolve into that store, so include it when present.
const externalStore = process.env.HBOX_PNPM_STORE ?? 'C:/hbn/.pnpm'
if (fs.existsSync(externalStore)) {
  config.watchFolders.push(externalStore)
}

config.resolver.nodeModulesPaths = [
  path.resolve(projectRoot, 'node_modules'),
  path.resolve(workspaceRoot, 'node_modules'),
]

// Force a single copy of native-bearing / singleton libraries.
const single = [
  'react',
  'react-native',
  'react-native-nitro-modules',
  'react-native-reanimated',
  'react-native-safe-area-context',
  'react-native-svg',
  'react-native-worklets',
  '@shopify/flash-list',
  'tamagui',
]
config.resolver.extraNodeModules = Object.fromEntries(
  single.map((name) => [name, path.resolve(projectRoot, 'node_modules', name)]),
)

module.exports = config
