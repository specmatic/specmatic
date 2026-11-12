# Decode gzip at HTTP mock ingress

The HTTP mock decodes a request with a single, case-insensitive `gzip` content coding before parsing its media type. Stream the decoded bytes into the existing receive path so Ktor still parses URL-encoded and multipart forms and Specmatic still parses and matches JSON. Request-level gzip applies to the whole multipart body. Individual part handling and the standalone recording proxy retain their current behavior.

Ktor sees the original `Content-Type`, including the multipart boundary, and receives the decoded stream with its length unspecified. Count bytes as they are decoded and complete gzip validation before handing the parsed request to adapters or matching. Remove the consumed `Content-Encoding`. If the original request supplied `Content-Length`, replace it in the internal request with the decoded byte count. Otherwise leave it absent. The outbound client frames any pass-through request using the bytes it sends.

Malformed gzip uses the existing HTTP 400 request-parse response. Start without a new decoded-size limit and leave heap exhaustion to the runtime. A configurable limit can be considered if real usage warrants one.

Verify through the running mock that gzipped JSON matches its schema, Ktor parses gzipped URL-encoded and multipart forms, and both present and absent wire `Content-Length` values follow the header policy above. Include a malformed gzip request and a well-formed gzip request whose decoded JSON fails schema matching.
