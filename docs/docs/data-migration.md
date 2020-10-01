---
title: Data Migration
---

# Data Migration

Marathon stores its data in ZooKeeper. When you upgrade Marathon, your data will be migrated, because different versions of Marathon can have different data layouts in ZooKeeper. When a newer version of Marathon becomes leader for the first time, it moves the data in ZooKeeper to the new layout.

The following tips will help you avoid data loss during a Marathon upgrade.

- Back up your data before upgrade in case the connection to ZooKeeper drops or there is a bug in Marathon.

- Do not interrupt the leading Marathon instance while it is performing data migration.
