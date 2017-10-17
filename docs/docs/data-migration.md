---
title: Data migration
---

# Data migration

Marathon can store its data either in ZooKeeper or in-memory. In this document only Zookeeper is going to be considered, because there is no data migration possible in case of the in-memory only persistence store.

Marathon stores data in ZooKeeper according to a certain layout, and different version of Marathon tend to have different data layouts in ZooKeeper. When a Marathon instance of a newer version becomes a leader for the first time, the first thing it does, is data migration. Therefore it is important

1. to create a data backup before upgrading a Marathon just in case a connection to ZooKeeper drops or there is a bug in Marathon.
1. to not interrupt the leading Marathon instance while it is performing data migration.

If data migration gets interrupted for any reason, it is recommended to restore the data, and start another Marathon instance of a newer version to repeat the process. While performing migration, Marathon stores a `migration-in-progress` node. If it gets interrupted while working on it, it is going fail once it becomes a leader again and detects that there is the node lingering.

Data migration might depend on environment variables or command-line flags or both. In the following sections all such variables and flags are explained.
