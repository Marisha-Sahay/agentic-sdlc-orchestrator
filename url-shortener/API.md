# URL Shortener API

A plain-English guide to the API: what each endpoint does, what to send, and every response you
can get back. The examples were captured from the running service, except the two `500`
responses, which can't be triggered on demand and are shown as the code produces them.

- **Base URL:** `http://localhost:8080` when you run it locally.
- **Format:** JSON in, JSON out. Send `Content-Type: application/json` when you post a body.
- **Errors:** every error has the same shape; see [Errors](#errors).
- **Try it:** start the service and open <http://localhost:8080/swagger-ui.html>.
- **Formal contract:** [`api/openapi.yaml`](api/openapi.yaml) is the machine-readable version of
  this guide. If the two ever disagree, the contract wins.

## Endpoints at a glance

| Endpoint | What it does | Success | Errors you might see |
|---|---|---|---|
| `POST /api/v1/links` | Create a short link | `201` | `400`, `409`, `415`, `429`, `500` |
| `GET /api/v1/links/{code}` | Look up a link | `200` | `404` |
| `DELETE /api/v1/links/{code}` | Delete a link | `204` | `404` |
| `GET /{code}` | Follow a short link | `302` | `404`, `410` |

On top of these, a wrong HTTP method gets `405`, an `Accept` header that rules out JSON gets
`406`, and an unexpected failure gets `500`; see
[Responses any endpoint can give](#responses-any-endpoint-can-give). The operational endpoints
(`/actuator/health`, `/actuator/info`, `/actuator/metrics`) are for operators and are not covered
here.

## Errors

When something goes wrong you get a problem document
([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)) with
`Content-Type: application/problem+json`:

```json
{
  "detail": "No link exists for code 'nope1234'",
  "instance": "/nope1234",
  "status": 404,
  "title": "Link not found"
}
```

- `title` is a short summary that doesn't change between requests, so code can match on it.
- `detail` explains this particular failure.
- `status` repeats the HTTP status, and `instance` is the path you called.
- `errors` appears only on `400 Invalid request` and lists each field that was wrong.

---

## 1. Create a short link

`POST /api/v1/links`

| Field | Required | What it is |
|---|---|---|
| `url` | yes | The long URL. Must be an absolute `http` or `https` URL of at most 2048 characters. Spaces around it are trimmed. |
| `customAlias` | no | Your own code instead of a random one: 4 to 32 letters, digits, `_` or `-`. |
| `expiresAt` | no | When the link stops working, as a future ISO-8601 time such as `2026-12-31T23:59:59Z`. Leave it out and the link never expires. |

Without `customAlias` you get a random 7-character code such as `caMHkPU`. Codes are
case-sensitive.

```bash
curl -i -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url": "https://example.com/some/long/path"}'
```

### 201 Created

The link is ready. The `Location` header points at the link's details
(`Location: http://localhost:8080/api/v1/links/caMHkPU`), and `shortUrl` is the link to share.

```json
{
  "code": "caMHkPU",
  "shortUrl": "http://localhost:8080/caMHkPU",
  "targetUrl": "https://example.com/some/long/path",
  "createdAt": "2026-10-29T04:53:05.128029Z",
  "expiresAt": null
}
```

With your own alias and an expiry:

```json
{
  "url": "https://example.com/launch",
  "customAlias": "launch-2026",
  "expiresAt": "2026-12-31T23:59:59Z"
}
```

Response `201`, `Location: http://localhost:8080/api/v1/links/launch-2026`:

```json
{
  "code": "launch-2026",
  "shortUrl": "http://localhost:8080/launch-2026",
  "targetUrl": "https://example.com/launch",
  "createdAt": "2026-10-29T04:53:05.188742Z",
  "expiresAt": "2026-12-31T23:59:59Z"
}
```

All times are UTC. `expiresAt` is `null` for a link that never expires.

### 400 Bad Request: the request breaks a rule

The title is `Invalid request` and `errors` says which field is wrong and why:

```json
{
  "detail": "url must not contain user info",
  "instance": "/api/v1/links",
  "status": 400,
  "title": "Invalid request",
  "errors": [
    {
      "field": "url",
      "message": "must not contain user info"
    }
  ]
}
```

Every reason you can get:

| You sent | `field` | `message` |
|---|---|---|
| No `url`, or only spaces | `url` | `must not be blank` |
| A URL that isn't absolute `http`/`https`, e.g. `ftp://example.com/file` or `/just/a/path` | `url` | `must be an absolute http or https URL` |
| A URL with user info, e.g. `https://bank.com@evil.com` (it really goes to `evil.com`) | `url` | `must not contain user info` |
| A URL that points back at the shortener itself | `url` | `must not point at this shortener` |
| A URL longer than 2048 characters | `url` | `must be at most 2048 characters` |
| An alias that is too short, too long, or has other characters | `customAlias` | `must be 4-32 characters of letters, digits, '_' or '-'` |
| A reserved alias (list below) | `customAlias` | `is a reserved word` |
| An `expiresAt` in the past | `expiresAt` | `must be in the future` |

For a missing or blank `url` the `detail` is `Request validation failed`; for the others it is the
field and message together, as in the example above.

Reserved aliases, in any letter case: `api`, `actuator`, `health`, `admin`, `static`, `assets`,
`public`, `login`, `logout`, `error`, `favicon`, `robots`, `metrics`, `info`, `docs`, `swagger`,
`openapi`, `www`. They would clash with the service's own pages.

**Broken JSON or a value of the wrong type.** If the body isn't valid JSON, or a value can't be
read (for example `"expiresAt": "tomorrow"`), you get a plainer 400 with no `errors` list:

```json
{
  "detail": "Failed to read request",
  "instance": "/api/v1/links",
  "status": 400,
  "title": "Bad Request"
}
```

### 409 Conflict: the alias is taken

That alias is already in use. This includes deleted links: a code is never handed out twice, so a
deleted link's alias stays taken.

```json
{
  "detail": "The alias 'launch-2026' is already in use",
  "instance": "/api/v1/links",
  "status": 409,
  "title": "Alias already in use"
}
```

### 415 Unsupported Media Type

The body wasn't sent as JSON (the `Content-Type` header is missing or wrong).

```json
{
  "detail": "Content-Type 'text/plain' is not supported.",
  "instance": "/api/v1/links",
  "status": 415,
  "title": "Unsupported Media Type"
}
```

### 429 Too Many Requests

Each client (by IP address) can create 20 links a minute. The allowance refills gradually, about
one request every 3 seconds, and the `Retry-After` header says how many seconds to wait
(`Retry-After: 3`). Only creating links is limited; following and looking up links are not.

```json
{
  "detail": "Rate limit exceeded; retry after 3 seconds",
  "instance": "/api/v1/links",
  "status": 429,
  "title": "Too many requests"
}
```

### 500 Internal Server Error

Rare. One case is specific to this endpoint: the service tried 5 random codes and every one was
already taken. With about 3.5 trillion possible codes this practically never happens, but if it
does, simply try again.

```json
{
  "detail": "Could not generate a unique short code after 5 attempts",
  "instance": "/api/v1/links",
  "status": 500,
  "title": "Short code generation failed"
}
```

Any other failure is the general 500 described under
[Responses any endpoint can give](#responses-any-endpoint-can-give).

---

## 2. Look up a link

`GET /api/v1/links/{code}`

```bash
curl -i http://localhost:8080/api/v1/links/launch-2026
```

### 200 OK

```json
{
  "code": "launch-2026",
  "shortUrl": "http://localhost:8080/launch-2026",
  "targetUrl": "https://example.com/launch",
  "createdAt": "2026-10-29T04:53:05.188742Z",
  "expiresAt": "2026-12-31T23:59:59Z"
}
```

Expired links are still returned here, so you can see when they expired. Only following them
fails.

### 404 Not Found

The link never existed or was deleted.

```json
{
  "detail": "No link exists for code 'nope1234'",
  "instance": "/api/v1/links/nope1234",
  "status": 404,
  "title": "Link not found"
}
```

---

## 3. Delete a link

`DELETE /api/v1/links/{code}`

```bash
curl -i -X DELETE http://localhost:8080/api/v1/links/old-page
```

### 204 No Content

Deleted, with an empty body. The link stops working at once, and its code is never reused.

### 404 Not Found

There is no such link, or it was already deleted (deleting twice gives `404` the second time).

```json
{
  "detail": "No link exists for code 'old-page'",
  "instance": "/api/v1/links/old-page",
  "status": 404,
  "title": "Link not found"
}
```

---

## 4. Follow a short link

`GET /{code}`. This is the link you share, for example `http://localhost:8080/launch-2026`.

```bash
curl -i http://localhost:8080/launch-2026
```

### 302 Found

No body; the browser goes straight to the address in `Location`:

```text
Location: https://example.com/launch
Cache-Control: no-store
```

Why 302 and not 301: browsers keep a 301 forever and stop asking the service, so deleting or
expiring the link would have no effect. `Cache-Control: no-store` makes sure every visit checks in.

Swagger UI can't show this response. The browser follows the redirect by itself and usually
reports "Failed to fetch" because the target site blocks cross-site requests. Open the short link
in a new tab, or use `curl -i`, instead.

### 404 Not Found

The link never existed or was deleted.

```json
{
  "detail": "No link exists for code 'nope1234'",
  "instance": "/nope1234",
  "status": 404,
  "title": "Link not found"
}
```

A path that can't be a code at all (shorter than 4 or longer than 32 characters, or containing
other characters such as `.`) also gets a 404, with the generic title `Not Found`:

```json
{
  "detail": "No static resource abc.",
  "instance": "/abc",
  "status": 404,
  "title": "Not Found"
}
```

### 410 Gone

The link has expired.

```json
{
  "detail": "The link for code 'flash-sale' has expired",
  "instance": "/flash-sale",
  "status": 410,
  "title": "Link expired"
}
```

> **Known issue #1:** a link that was followed before it expired can keep redirecting for up to
> 10 minutes after `expiresAt`, because the redirect cache doesn't re-check the expiry. The
> [brownfield scenario](../docs/scenarios/brownfield.md) fixes this.

---

## Responses any endpoint can give

### 405 Method Not Allowed

The path exists but not with that method, for example `PUT /api/v1/links/launch-2026`. The
`Allow` header lists the methods that work (`Allow: GET, DELETE`).

```json
{
  "detail": "Method 'PUT' is not supported.",
  "instance": "/api/v1/links/launch-2026",
  "status": 405,
  "title": "Method Not Allowed"
}
```

### 406 Not Acceptable

You asked an endpoint that returns JSON (creating or looking up a link) for something else, for
example with `Accept: text/plain`.

```json
{
  "detail": "Acceptable representations: [application/json].",
  "instance": "/api/v1/links/launch-2026",
  "status": 406,
  "title": "Not Acceptable"
}
```

### 500 Internal Server Error

Something unexpected broke. The details go to the server log, never into the response.

```json
{
  "detail": "An unexpected error occurred",
  "instance": "/api/v1/links",
  "status": 500,
  "title": "Internal server error"
}
```
