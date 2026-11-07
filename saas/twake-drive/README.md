# TDrive attachment storage

TDrive stores calendar attachments and returns a public, read-only link. The integration is disabled by default.

Add these settings to `configuration.properties` to enable it:

```properties
twp.drive.enabled=true
twp.drive.instance.domain=example.com
twp.drive.token.url.template=https://manager.example.com/api/public/instances/{fqdn}/token
twp.drive.partner.token=<token-issuance-bearer-token>
twp.drive.public.url.template=https://{driveFqdn}/public?sharecode={sharecode}#/
```

Choose how the service finds each user's Drive with `twp.drive.instance.resolver`:

- **`local-part` (default)** derives the hostname from the user's email. It lowercases the email local part, keeps only ASCII letters, digits and interior hyphens, then appends the configured instance domain. Use this mode only when provisioning uses the same rule and each hostname is unique across all accepted accounts.
- **`cloudery`** looks up the user's full email. Add the lookup URL and a partner token with permission to list instances. The token above is still required for uploads.

  ```properties
  twp.drive.instance.resolver=cloudery
  twp.drive.lookup.url=https://manager.example.com/api/v2/instances
  twp.drive.lookup.partner.token=<instance-lookup-partner-token>
  ```

- **`ldap`** requires an LDAP user repository in `usersrepository.xml`. Set `twp.drive.instance.resolver=ldap` in `configuration.properties`. Store one Drive hostname per user in `twakeWorkspaceUrl`, grant the LDAP service account read access, and reserve write access for provisioning.

All Drive hostnames must be under `twp.drive.instance.domain`. If a lookup fails, the upload stops.

Optional settings and their defaults:

```properties
twp.drive.drive.url.template=https://{fqdn}
twp.drive.folder.name=Calendar attachments
twp.drive.timeout=10s
twp.drive.trust.all.ssl.certs=false
```
