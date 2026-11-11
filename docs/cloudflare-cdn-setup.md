# Cloudflare CDN & Origin Setup for Resumable Downloads

This guide outlines the exact Cloudflare CDN rules and origin configuration required to support reliable, interrupted-stream byte-range resume with `@picsa/capacitor-resumable-download`.

---

## 1. The Core HTTP Range Contract

For client-side download resumption to succeed, the CDN/origin must satisfy four HTTP criteria:

1. **`Accept-Ranges: bytes`**: The server advertises byte-range support.
2. **`HTTP 206 Partial Content`**: When the client requests `Range: bytes=<offset>-`, the server responds with status `206` and header `Content-Range: bytes <start>-<end>/<total>`.
3. **Strong `ETag` (No `W/"..."`)**: When the client sends `If-Range: <etag>`, the server validates the entity tag. If Cloudflare compresses the asset dynamically, it prefixes the ETag with `W/` (weak ETag). HTTP specifications state that weak ETags cannot be used for byte-range validation, causing the server to fall back to `HTTP 200 OK` (re-downloading from byte 0).
4. **No Dynamic Content-Encoding (Gzip/Brotli)**: Video files, audio files, PDFs, and ZIP archives are already compressed. Applying gzip or brotli breaks byte offsets and consumes unnecessary CPU.

---

## 2. Cloudflare Configuration Steps

### Step 1: Disable Compression for Binary Assets (Preserves Strong ETags)

By default, Cloudflare enables Brotli and Gzip on edge responses. When compression is applied to an asset, Cloudflare converts the origin's strong ETag to a weak ETag (e.g. `W/"1a2b3c"`).

To prevent this:

1. Navigate to **Cloudflare Dashboard** $\rightarrow$ **Rules** $\rightarrow$ **Compression Rules**.
2. Click **Create rule**.
3. Set Rule Name: `Disable Compression for Downloadable Media`.
4. Configure expression:
   ```text
   (http.request.uri.path contains "/videos/") or
   (http.request.uri.path contains "/resources/") or
   (http.request.uri.path.extension in {"mp4" "mkv" "webm" "mp3" "wav" "pdf" "zip" "tar"})
   ```
5. Action: **No compression**.
6. Deploy rule.

---

### Step 2: Cache Rules (Edge TTL & Byte Range Caching)

Cloudflare supports byte-range requests for cached assets:

1. Navigate to **Rules** $\rightarrow$ **Cache Rules**.
2. Set Rule Name: `Cache Long-Lived Static Downloads`.
3. Expression:
   ```text
   (http.request.uri.path.extension in {"mp4" "mp3" "pdf" "zip"})
   ```
4. Cache eligibility: **Eligible for cache**.
5. Edge TTL: **Respect origin** (or specify e.g. 30 days).
6. Browser TTL: **Respect origin**.

---

### Step 3: CORS Configuration (If downloading from Web / WebViews)

If web-fallback clients or cross-origin requests are used:

1. Under **Transform Rules** $\rightarrow$ **Modify Response Header**:
   - `Access-Control-Allow-Origin: *`
   - `Access-Control-Allow-Methods: GET, HEAD, OPTIONS`
   - `Access-Control-Allow-Headers: Range, If-Range, Content-Type`
   - `Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges, ETag`

---

## 3. Verifying Your Setup with the Verification Tool

Run the built-in verification script against any URL:

```bash
yarn verify:cdn https://cdn.example.com/resources/intro-video.mp4
```

### Expected Successful Output:

```text
=== CDN / Storage Byte-Range Verification ===
Target: https://cdn.example.com/resources/intro-video.mp4

1. Checking HEAD Response Headers...
  ✔ [PASS] Accept-Ranges header is present: "bytes"
  ✔ [PASS] Strong ETag present: "65123abc-8ef"

2. Testing HTTP 206 Partial Content (bytes=0-1023)...
  ✔ [PASS] Server returned HTTP 206 Partial Content
  ✔ [PASS] Content-Range header matches expected byte slice: "bytes 0-1023/15420194"
  ✔ [PASS] No dynamic Content-Encoding (compression is disabled)

3. Testing If-Range validation...
  ✔ [PASS] If-Range with matching ETag returned HTTP 206 Partial Content

=== Verification Summary ===
ALL CHECKS PASSED! This URL fully supports reliable HTTP byte-range resumable downloads.
```

### Manual Curl Verification

You can also verify with standard curl:

```bash
# 1. Check HEAD
curl -I https://cdn.example.com/resources/intro-video.mp4

# 2. Check 206 Range slice
curl -i -H "Range: bytes=0-1023" https://cdn.example.com/resources/intro-video.mp4

# 3. Check If-Range with ETag
curl -i -H "Range: bytes=1024-2047" -H 'If-Range: "65123abc-8ef"' https://cdn.example.com/resources/intro-video.mp4
```
