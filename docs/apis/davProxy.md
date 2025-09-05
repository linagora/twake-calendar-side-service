# Dav Proxy

This proxy, served on the same port than the [OpenPaaS REST API](openpaasApi.md) allows to wrap OpenID connect around 
the DAV server, which is OIDC ignorant.

All request whose URL starts by `/dav/` regardless of the verb will be OIC-authenticated then proxied to the DAV server.

Impersonation is used by the side service to authenticate the request onto esn-sabre dav server.