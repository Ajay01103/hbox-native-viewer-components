#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Raw DEFLATE (no zlib header), as stored by ZIP entries.
@interface DocxInflate : NSObject

/// Returns nil when the stream is corrupt or exceeds `expectedSize` by too much.
+ (nullable NSData *)inflateRaw:(NSData *)data expectedSize:(NSUInteger)expectedSize;

@end

NS_ASSUME_NONNULL_END
