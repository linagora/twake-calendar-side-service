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

After this, you will find the new image in your local Docker images: `linagora/twake-calendar-utility:latest`


## ⚙️ Configuration

The utility CLI reads its configuration from a properties file.

When running via Docker, mount a directory containing:

```bash
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
    -v $(pwd)/config:/root/conf \
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

```text
Starting purge of schedulingobjects older than 2024-10-20T00:00:00Z
Found 121 total records, 120 old records to delete
Batch 1/2 (50%) - 100 deleted
Batch 2/2 (100%) - 20 deleted
Purge completed successfully: deleted 120 items, skipped 1 recent docs
```

---

## Example: Migrate the sortable full name of contacts

esn-sabre lists the contacts of all the address books of a user (`GET /contacts/{userId}.json`) sorted by their
full name, stored in the `fn_sort` field of each card. Contacts written before esn-sabre stored it have none and are
left out of that listing until this command stores it.

Run it against the **esn-sabre database** (the one holding the `cards` collection, `sabre` by default): point
`mongo.url` and `mongo.database` of `configuration.properties` to it.

```bash
docker run --rm \
    -v ./configuration.properties:/root/conf/configuration.properties:ro \
    twake-calendar-utility:latest \
    migrateContactsSortName \
    --batch-size 100
```

`--batch-size` is optional (default 100). The command is idempotent: contacts already having a sortable full name,
including the ones esn-sabre writes while the command runs, are left untouched, so it can be run again safely.

A batch failing to be stored is skipped and the next ones are processed. The command then ends with an error
(exit code 1) telling how many contacts were skipped: run it again to process them.

Example output:

```text
Found 1200 contacts, 1050 without sortable full name
Batch 1/3 (33%) - 500 contacts updated
Batch 2/3 (66%) - 500 contacts updated
Batch 3/3 (100%) - 50 contacts updated
Migration completed successfully: updated 1050 contacts
```
