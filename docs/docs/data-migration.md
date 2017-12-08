---
title: Data migration
---

# Data migration

Marathon stores its data in ZooKeeper. When you upgrade Marathon, your data will be migrated, because different versions of Marathon can have different data layouts in ZooKeeper. When a newer version of Marathon becomes leader for the first time, it moves the data in ZooKeeper to the new layout.

The following tips will help you avoid data loss during a Marathon upgrade.

- Back up your data before upgrade in case the connection to ZooKeeper drops or there is a bug in Marathon.

- Do not interrupt the leading Marathon instance while it is performing data migration.

If data migration gets interrupted for any reason, restore the data and start another Marathon instance of the newer version to repeat the process. While performing data migration, Marathon creates a `state/migration-in-progress` node. If a Marathon is interrupted during migration, it will fail when it becomes leader again and detects the existence of the `state/migration-in-progress` node.
