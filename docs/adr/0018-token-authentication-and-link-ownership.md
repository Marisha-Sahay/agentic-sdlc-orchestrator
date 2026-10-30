# ADR-0018: Token authentication and link ownership (production extension)

Status: proposed, not implemented, 2026-10-29

## Context
The management and statistics API is unauthenticated: anyone can create links, read any link's
metadata and statistics, and delete any link. The requirements do not ask for authentication, so
the prototype leaves it out. This records how it should be added for production.

## Decision
Use tokens from an existing identity provider. The service does not authenticate users itself.

1. An identity provider authenticates the user and issues an access token.
2. The client sends `Authorization: Bearer <token>`.
3. Spring Security (OAuth2 resource server) validates the token and populates the
   `SecurityContext`.
4. The application checks scopes and link ownership.

| Operation | Access |
|---|---|
| Follow a short link (`GET /{code}`) | Public |
| Create a link | `links:write`; the authenticated user is saved as the owner |
| View metadata or statistics | `links:read` + ownership |
| Delete a link | `links:write` + ownership |

**Owner identity.** A new migration (`V3__add_link_owner.sql`; V1 and V2 are already applied)
adds an `owner_id` column to `links`. With a single trusted issuer, the owner is the token's `sub`
claim. It is always taken from the authenticated context, never from a request field or a header.
With several issuers, use the pair (`iss`, `sub`).

**Ownership is enforced in the SQL itself**, not checked first and acted on afterwards. Delete
becomes:

```sql
update links
   set deleted_at = :deletedAt
 where code = :code
   and owner_id = :ownerId
   and deleted_at is null
```

A user with `links:write` therefore cannot delete another user's link. Lookups and statistics add
the same `owner_id` condition.

**Minimal scope if implemented:** token validation, scope checks, storing the owner, and ownership
checks. No custom authentication service and no header that carries the user's identity.

## Consequences
- **Status codes:** no token or an invalid one → `401`; missing scope → `403`; someone else's link
  → `404`, the same as a link that does not exist. When zero rows match, the service can't tell
  the two apart, and the response doesn't reveal that the code exists.
- **Existing links have no owner.** Ownership checks deny them, so they need a one-off backfill
  or an operator path.
- **Redirects stay public and unchanged**, so the cached redirect path gains no latency.
- **Testable without a real identity provider:** `spring-security-test` can build JWTs with
  chosen scopes and subjects for the controller tests.
