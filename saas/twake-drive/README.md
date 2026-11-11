# TDrive attachment storage

The side service stores attachments in the user's TDrive.
Anyone with the public link can read the file.
Attachment storage is off by default.

## Configure the side service

Add this example to `configuration.properties` for Cloudery, the default resolver.
Replace the example URLs and tokens. Enter tokens without the `Bearer` prefix.

```properties
twp.drive.enabled=true
twp.drive.token.url.template=https://manager.example.com/api/public/instances/{fqdn}/token
twp.drive.issuer.token=<token-issuance-bearer-token>
twp.drive.public.url.template=https://{instanceLabel}-drive.{instanceSuffix}/public?sharecode={sharecode}#/
twp.drive.instance.resolver=cloudery
twp.drive.lookup.url=https://manager.example.com/api/v2/instances
twp.drive.lookup.token=<instance-lookup-bearer-token>
```

### Optional settings

```properties
twp.drive.folder.name=Calendar attachments
twp.drive.timeout=10s
twp.drive.trust.all.ssl.certs=false
```

## Property reference

| Property | Required? | Default | Purpose |
| --- | --- | --- | --- |
| `twp.drive.enabled` | Optional | `false` | Enables TDrive attachment storage. |
| `twp.drive.token.url.template` | Required when enabled | None | Token issuer endpoint. Must include `{fqdn}`. |
| `twp.drive.issuer.token` | Required when enabled | None | Bearer token sent to the issuer. The returned service token authenticates Drive API calls at `https://{fqdn}`. |
| `twp.drive.public.url.template` | Required when enabled | None | Builds the public link saved in the event. Must include `{sharecode}`. |
| `twp.drive.instance.resolver` | Optional | `cloudery` | Finds the user's Drive hostname. `ldap` reads `twakeWorkspaceUrl` via the LDAP user repository. `local-part` builds the hostname from the email. |
| `twp.drive.lookup.url` | Required when enabled with `cloudery` | None | Cloudery endpoint for looking up the instance by full email. |
| `twp.drive.lookup.token` | Required when enabled with `cloudery` | None | Bearer token for the lookup endpoint. Needs permission to list instances. |
| `twp.drive.local-part.domain` | Optional, `local-part` only | User's email domain | Overrides the email domain when Drive uses a different domain. |
| `twp.drive.folder.name` | Optional | `Calendar attachments` | Folder for uploaded attachments in the user's Drive. |
| `twp.drive.timeout` | Optional | `10s` | Timeout per outgoing HTTP request. |
| `twp.drive.trust.all.ssl.certs` | Optional | `false` | Accepts untrusted TLS certificates when set to `true`. |

With `ldap`, configure the user repository in `usersrepository.xml` and limit writes to
`twakeWorkspaceUrl` to trusted provisioning or administrators. With `local-part`,
provision Drive hostnames using the same naming rule and ensure they are unique.

Use `{fqdn}` for the full instance hostname. The sample template uses
`{instanceLabel}-drive.{instanceSuffix}` to turn `alice.staging.example.com`
into `alice-drive.staging.example.com`.

## Enable CalDAV attachment uploads

Use a Sabre version that supports `CALDAV_BINARY_ATTACHMENT_MODE=offload` and set it.
Sabre sends inline attachments to the side service at `/api/attachments`.

- If set, `TRUSTED_ATTACH_URL_BASE` must match the generated public links.
- Sabre's bundled Nginx uses `NGINX_CLIENT_MAX_BODY_SIZE` to cap the full request, including Base64.
