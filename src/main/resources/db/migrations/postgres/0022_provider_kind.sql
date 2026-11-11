-- Login providers (docs/auth.md#oidc): 'oidc' (OpenID Connect: discovery from the issuer, an ID
-- token) or 'oauth2' (plain OAuth 2.0: the endpoints, the userinfo URL and the claim paths are in
-- claim_mapping_json). Fixed when the provider is created.
ALTER TABLE oidc_providers ADD COLUMN kind TEXT NOT NULL DEFAULT 'oidc';
