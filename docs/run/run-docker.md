# Run with docker

Compilation will build a tar that needs to be imported in your local docker engine:

```bash
docker load -i twake-calendar-side-service/app/target/jib-image.tar
```

Then it can easily be run with:

```bash
docker run --rm -ti linagora/twake-calendar-side-service:latest
```