# Configuration

The side service intend to be statically configured through configuration files.

The side service leverages [comons-configuration2](https://commons.apache.org/proper/commons-configuration) in order to parse its configuration files. Especially, it is
capable of [interpolation of environment variable](https://commons.apache.org/proper/commons-configuration/userguide/howto_basicfeatures.html).

The side service can be suppied the following configuration files:

 - [configuration.properties](configuration.md) is the main configuration file, with all values specific to the side service.
 - [logback.xml](https://logback.qos.ch/manual/configuration.html) allow configuring the logger. We rely on vanilla upstream format.
 - [redis.properties](https://james.staged.apache.org/james-project/3.9.0/servers/distributed/configure/redis.html) 
inherited of Apache James. Only `redisUrl` property is needed. Optional: if omitted a memory cache is used instead.
 - [extensions.properties](https://james.staged.apache.org/james-project/3.9.0/servers/distributed/configure/extensions.html)
inherited of Apache James. Currently unused. Optional.
 - [healthcheck.properties](https://james.staged.apache.org/james-project/3.9.0/servers/distributed/configure/healthcheck.html)
   inherited of Apache James. Only `healthcheck.period` is used. Optional.
 - [jvm.properties](https://james.staged.apache.org/james-project/3.9.0/servers/distributed/configure/jvm.html)
inherited of Apache James. See [this example](../../app/src/main/conf/jvm.properties) for specific values for the side service.
Optional.
 - [usersreposiory.xml](https://james.staged.apache.org/james-project/3.9.0/servers/distributed/configure/usersrepository.html#_configuring_a_ldap). 
Optional. If present, it is used to set up LDAP connection following the exact James semantic.
 - [rabbitmq.properties](https://james.staged.apache.org/james-project/3.9.0/servers/distributed/configure/rabbitmq.html) matches the semantic in James. 
The following properties are supported: `uri`, `management.uri`, `management.user`, `mangement.password`, `scheduled.consumer.reconnection.enabled`, 
`scheduled.consumer.reconnection.interval`. Compulsory. When `twp.settings.enabled`, `common.contacts.enabled` or `saas.subscription.enabled` is set, additional
Twake Workplace integration properties are read from this file, see [Twake Workplace RabbitMQ properties](configuration.md#twake-workplace-rabbitmq-properties).
 - [opensearch.properties](opensearch.md) enable setting up the OpenSearch service.
 - [webadmn.properties](https://james.staged.apache.org/james-project/3.9.0/servers/distributed/configure/webadmin.html)
inherited of Apache James.

## JVM system properties

Add these properties to `jvm.properties` or pass them with the JVM `-D` option. Restart the service after changing them.

| Property | Description | Default |
| --- | --- | --- |
| `twake.calendar.smtp.max-retries` | Maximum number of retries for a complete SMTP create-and-send attempt. Set to `0` to disable retries. | `3` |
