# Self-issued JWTs instead of Keycloak

**Status:** accepted

Keycloak was the obvious choice for a project that wants to look like real SaaS, and the brief offered it.
We rejected it: it adds a container, realm-import configuration and a token-exchange dance, and it still
leaves us to map users onto businesses ourselves — so it *hides* the part of authentication worth
demonstrating while adding the part that is only operational overhead. We issue our own tokens: BCrypt
password hashing, 15-minute HS256 access tokens, and opaque refresh tokens with rotation and replay
detection that revokes the whole token family.

## Consequences

- Spring Security is configured as an **OAuth2 resource server** from the first commit, even though we are
  also the issuer. Adopting Keycloak later replaces the issuer and changes no controller.
- We own password storage and refresh-token lifecycle, and therefore own their security. This is deliberate:
  it is the part a reviewer should be able to read.
- No social login, no enterprise SSO, no built-in account recovery. All are V1.2 items, and all are easier
  once an external IdP is introduced.
- Tokens travel as httpOnly cookies on a single origin, so no token is ever reachable from JavaScript.
  This depends on the reverse-proxy topology and is why Caddy exists in phase 01.
