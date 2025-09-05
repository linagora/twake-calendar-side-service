# opensearch.properties

Optional. If omitted the side service will default to memory data structure.

This file in mostly based on Apache James [opensearch.properties]() file.

The following properties are supported:

 - `opensearch.hosts`
 - `opensearch.clusterName`
 - `opensearch.user`
 - `opensearch.password`
 - `opensearch.hostScheme`
 - `opensearch.hostScheme.https.sslValidationStrategy`
 - `opensearch.hostScheme.https.hostNameVerifier`
 - `opensearch.nb.shards`
 - `opensearch.nb.replica`
 - `opensearch.retryConnection.maxRetries`
 - `opensearch.retryConnection.minDelay`

In addition, the auto-complete properties defined in [Twake mail documentation](https://github.com/linagora/tmail-backend/blob/master/docs/modules/ROOT/pages/tmail-backend/configure/opensearch.adoc)
needs to be specified. This includes:

 - `opensearch.index.contact.user.name`
 - `opensearch.alias.read.contact.user.name`
 - `opensearch.alias.write.contact.user.name`
 - `opensearch.index.contact.domain.name`
 - `opensearch.alias.read.contact.domain.name`
 - `opensearch.alias.write.contact.domain.name`
 - `opensearch.index.contact.min.ngram`
 - `opensearch.index.contact.max.ngram.diff`