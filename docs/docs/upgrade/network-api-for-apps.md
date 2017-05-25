---
title: Migrating Apps to the 1.5 Networking API
---

# Migrating Apps to the 1.5 Networking API

When you upgrade to Marathon 1.5, your apps will also be automatically modified to conform to the new networking API. However, support for deprecated networking API fields will be dropped completely in some future release according to Marathon's official API deprecation policy [MARATHON-7165](https://jira.mesosphere.com/browse/MARATHON-7165).

Use the options below to finely tune automatic app migration:

- Set the `MIGRATION_1_5_0_MARATHON_DEFAULT_NETWORK_NAME` environment variable.
    * Older MESOS IP/CT app definitions were not required to declare an `ipAddress/networkName`; Marathon 1.5 requires a resolvable network name.
    * Migration automatically configures `container` networking mode for each migrated legacy MESOS IP/CT app.
    * At migration time, legacy MESOS IP/CT app definitions are configured to use the network name defined by the migration-specific environment variable above.
- Use the `--default_network_name` flag.
    *  Migration only uses the network name defined by the `--default_network_name` flag if the `MIGRATION_1_5_0_MARATHON_DEFAULT_NETWORK_NAME` environment variable is **unset**.

**Note:** For older MESOS IP/CT apps that do declare a networkName: if neither the environment variable nor the flag are set, app migration will fail.
