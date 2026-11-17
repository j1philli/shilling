# Binary receipt chunks

Receipt traffic uses the existing ordered, reliable WebRTC data channel. The
signaling server remains control-plane only. Receipt reads and completed writes
remain behind Store5 and `FileTransferManager` retains its 50 MiB receive budget.

## Required format

All receipt chunks use binary frames. `FileRequest` contains only `receiptId`;
there is no capability negotiation or JSON/Base64 chunk fallback. JSON chunk
messages are rejected. This is a pre-GA protocol change: communicating peers
must use the current format.

Receipt IDs must be nonempty, valid Unicode and at most 1,024 UTF-8 bytes.
Invalid IDs are rejected before reading a file or allocating a receive buffer.

Headers, completion, unavailable and request messages remain JSON. A header
retains the existing fixed 16,384-byte raw chunk layout. Frame receipt IDs and
indices permit duplicate/reordering checks. Transport
frames decode into the existing file-transfer flow; they do not persist data.

## Binary frame version 1

Integers use unsigned network byte order (big endian).

| Offset | Length | Value |
| --- | --- | --- |
| 0 | 4 | Magic/version: `53 48 46 01` (`SHF`, version 1) |
| 4 | 2 | UTF-8 receipt ID byte length, 1–1,024 |
| 6 | 4 | Zero-based chunk index, less than 10,000 |
| 10 | ID length | Strict UTF-8 receipt ID |
| 10 + ID length | Remaining bytes | Raw chunk, 1–16,384 bytes |

The maximum frame is 17,418 bytes. The receiver rejects unknown versions,
truncation, invalid UTF-8, invalid indices and invalid payload lengths. The file
session additionally checks index bounds and the exact expected final-chunk size
before copying bytes into its bounded assembly buffer. Completion writes through
Store5 only when every chunk is present. Interrupted transfers use the existing
request/retry mechanism.

Encoding runs off the UI dispatcher with four queued messages. Together with the
consumer and suspended producer, at most six prepared messages can be live in
that pipeline. Android/browser receive text or binary through Ktor; the existing
iOS native receive adapter preserves the buffer's binary flag. An overflowing
iOS adapter closes the channel for retry instead of dropping the oldest message.
