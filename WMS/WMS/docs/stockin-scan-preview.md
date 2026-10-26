# Read-only inbound barcode preview

`GET /api/v1/stockin/{orderId}/scan-preview?barcode=...` requires
`stockin:read` and the same authenticated company, warehouse and owner scope as
the inbound detail endpoint. The response uses the normal WMS `code: "OK"`
envelope and contains `orderId`, `orderCode`, `state`, normalized `barcode`, and
`matches`, using the existing inbound line fields.

Barcode input is trimmed and limited to 128 characters. A missing match returns
a business error. Multiple lines for the same barcode, including different
batches, are returned together; the client must select and confirm the intended
line rather than silently receiving the first match.

This operation reads the authorized order and changes no receipt, inventory,
operation ledger or sequence. The existing `POST /stockin/{orderId}/scan`
continues to receive stock and must not be used for preview before confirmation
or handwritten signature.

Preview does not implement signature capture, upload, association or signed
receipt submission. Those capabilities require their own transaction and
idempotent-result contract before a mobile receipt workflow is complete.
