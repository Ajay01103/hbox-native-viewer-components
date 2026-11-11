#import "DocxInflate.h"
#import <zlib.h>

@implementation DocxInflate

+ (nullable NSData *)inflateRaw:(NSData *)data expectedSize:(NSUInteger)expectedSize {
  if (data.length == 0) {
    return [NSData data];
  }

  NSUInteger capacity = expectedSize > 0 ? expectedSize : data.length * 4;
  if (capacity == 0) {
    capacity = 64 * 1024;
  }
  const NSUInteger limit = 128 * 1024 * 1024;

  NSMutableData *output = [NSMutableData dataWithLength:capacity];
  z_stream stream;
  memset(&stream, 0, sizeof(stream));

  // -MAX_WBITS selects raw DEFLATE, which is what ZIP stores.
  if (inflateInit2(&stream, -MAX_WBITS) != Z_OK) {
    return nil;
  }

  stream.next_in = (Bytef *)data.bytes;
  stream.avail_in = (uInt)data.length;
  stream.next_out = (Bytef *)output.mutableBytes;
  stream.avail_out = (uInt)capacity;

  int status = Z_OK;
  while (status == Z_OK) {
    status = inflate(&stream, Z_NO_FLUSH);
    if (status == Z_BUF_ERROR || (status == Z_OK && stream.avail_out == 0)) {
      if (output.length >= limit) {
        inflateEnd(&stream);
        return nil;
      }
      NSUInteger used = output.length;
      [output increaseLengthBy:capacity];
      stream.next_out = (Bytef *)output.mutableBytes + used;
      stream.avail_out = (uInt)(output.length - used);
      if (status == Z_BUF_ERROR) {
        status = Z_OK;
      }
      continue;
    }
    if (stream.avail_in == 0 && status == Z_OK) {
      break;
    }
  }

  NSUInteger produced = stream.total_out;
  inflateEnd(&stream);

  if (status != Z_OK && status != Z_STREAM_END) {
    return nil;
  }
  [output setLength:produced];
  return output;
}

@end
