# Twake Calendar Utility App

This module provides CLI tools for Twake Calendar,
including cleanup operations such as purging old scheduling objects stored in MongoDB.

---

## Build Docker Image (using Jib)

This project uses **Jib** to build the Docker image without requiring a Dockerfile.

### Build image into local Docker daemon

```bash
mvn clean prepare-package jib:dockerBuild
```

After this, you will find the new image in your local Docker images: `linagora/twake-calendar-utility:latest `


## ⚙️ Configuration

The utility CLI reads its configuration from a properties file.

When running via Docker, mount a directory containing:

```
/root/conf/configuration.properties
```

Example configuration:

```properties
mongo.url=mongodb://mongo:27017
mongo.database=esn_docker
```

---

## 🚀 Run CLI Commands

General usage pattern:

```bash
docker run --rm \
    -v $(pwd)/config:/config \
    twake-calendar-utility:latest \
    <command> [options] 
```

---

## Example: Purge old scheduling objects

Delete scheduling objects older than a given retention:

```bash
docker run --rm \
    -v ./configuration.properties:/root/conf/configuration.properties:ro \
    twake-calendar-utility:latest \
    purgeInbox \
    --retention-period 30d 
```

Example output:

```
Starting purge of schedulingobjects older than 2024-10-20T00:00:00Z
Found 121 total records, 120 old records to delete
Batch 1/2 (50%) - 100 deleted
Batch 2/2 (100%) - 20 deleted
Purge completed successfully: deleted 120 items, skipped 1 recent docs
```
