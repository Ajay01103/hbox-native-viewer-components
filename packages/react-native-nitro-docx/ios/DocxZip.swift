import Foundation

enum DocxZipError: Error, LocalizedError {
  case notAZip
  case unsupported(String)
  case corrupt(String)

  var errorDescription: String? {
    switch self {
    case .notAZip:
      return "CORRUPT: this file is not a DOCX zip container"
    case .unsupported(let detail):
      return "UNSUPPORTED: \(detail)"
    case .corrupt(let detail):
      return "CORRUPT: \(detail)"
    }
  }
}

/// Minimal, defensive reader for the parts of a DOCX package we need.
final class DocxZip {
  private(set) var entries: [String: Data] = [:]

  private let maxEntries = 4096
  private let maxTotalBytes = 96 * 1024 * 1024

  init(data: Data) throws {
    if data.count < 22 {
      throw DocxZipError.notAZip
    }
    let eocdOffset = try Self.findEndOfCentralDirectory(in: data)
    let totalEntries = Int(Self.uint16(data, eocdOffset + 10))
    let directoryOffset = Int(Self.uint32(data, eocdOffset + 16))
    guard totalEntries > 0, totalEntries <= maxEntries else {
      throw DocxZipError.corrupt("unexpected entry count")
    }

    var cursor = directoryOffset
    var totalBytes = 0
    for _ in 0 ..< totalEntries {
      guard Self.uint32(data, cursor) == 0x0201_4b50 else {
        throw DocxZipError.corrupt("bad central directory entry")
      }
      let method = Self.uint16(data, cursor + 10)
      let compressedSize = Int(Self.uint32(data, cursor + 20))
      let uncompressedSize = Int(Self.uint32(data, cursor + 24))
      let nameLength = Int(Self.uint16(data, cursor + 28))
      let extraLength = Int(Self.uint16(data, cursor + 30))
      let commentLength = Int(Self.uint16(data, cursor + 32))
      let localOffset = Int(Self.uint32(data, cursor + 42))
      let nameStart = cursor + 46
      guard nameStart + nameLength <= data.count else {
        throw DocxZipError.corrupt("truncated entry name")
      }
      let name = String(decoding: data[(nameStart) ..< (nameStart + nameLength)], as: UTF8.self)

      if !name.hasSuffix("/") {
        let payload = try Self.readPayload(
          data: data,
          localOffset: localOffset,
          compressedSize: compressedSize,
          uncompressedSize: uncompressedSize,
          method: method
        )
        totalBytes += payload.count
        if totalBytes > maxTotalBytes {
          throw DocxZipError.corrupt("TOO_LARGE: uncompressed document exceeds the limit")
        }
        entries[name] = payload
      }
      cursor = nameStart + nameLength + extraLength + commentLength
    }
  }

  func part(_ name: String) -> Data? {
    return entries[name]
  }

  func firstPart(prefix: String) -> Data? {
    guard let key = entries.keys.filter({ $0.hasPrefix(prefix) }).sorted().first else {
      return nil
    }
    return entries[key]
  }

  // MARK: - Helpers

  private static func readPayload(
    data: Data,
    localOffset: Int,
    compressedSize: Int,
    uncompressedSize: Int,
    method: UInt16
  ) throws -> Data {
    guard uint32(data, localOffset) == 0x0403_4b50 else {
      throw DocxZipError.corrupt("bad local file header")
    }
    let nameLength = Int(uint16(data, localOffset + 26))
    let extraLength = Int(uint16(data, localOffset + 28))
    let start = localOffset + 30 + nameLength + extraLength
    let end = start + compressedSize
    guard start >= 0, end <= data.count, start <= end else {
      throw DocxZipError.corrupt("truncated entry payload")
    }
    let raw = data[start ..< end]

    switch method {
    case 0:
      return Data(raw)
    case 8:
      guard let inflated = DocxInflate.inflateRaw(Data(raw), expectedSize: UInt(uncompressedSize)) else {
        throw DocxZipError.corrupt("could not inflate a document part")
      }
      return inflated as Data
    default:
      throw DocxZipError.unsupported("compression method \(method) is not supported")
    }
  }

  private static func findEndOfCentralDirectory(in data: Data) throws -> Int {
    let signature: UInt32 = 0x0605_4b50
    let minimumOffset = max(0, data.count - 66_000)
    var offset = data.count - 22
    while offset >= minimumOffset {
      if uint32(data, offset) == signature {
        return offset
      }
      offset -= 1
    }
    throw DocxZipError.notAZip
  }

  private static func uint16(_ data: Data, _ offset: Int) -> UInt16 {
    guard offset >= 0, offset + 2 <= data.count else { return 0 }
    let start = data.startIndex + offset
    return UInt16(data[start]) | (UInt16(data[start + 1]) << 8)
  }

  private static func uint32(_ data: Data, _ offset: Int) -> UInt32 {
    guard offset >= 0, offset + 4 <= data.count else { return 0 }
    let start = data.startIndex + offset
    return UInt32(data[start])
      | (UInt32(data[start + 1]) << 8)
      | (UInt32(data[start + 2]) << 16)
      | (UInt32(data[start + 3]) << 24)
  }
}
