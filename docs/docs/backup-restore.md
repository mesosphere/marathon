---
title: Backup & Restore
---

# Backup and Restore

As of version 1.5, Marathon has built-in backup and restore functionality.
The complete state of Marathon, which is kept in the persistent data store, 
can be backed up to an external file or to an external storage provider.
Restoring from a backup brings Marathon to the exact state it was in at the time of backup creation.

## Limitations

Marathon backups only capture the state of Marathon itself. 
A Marathon backup will **not** capture the state of any other system component. 
To create a full backup of your cluster, you must back up all system components.

Restoring a backup in Marathon without restoring state in other system components can lead to unwanted behavior.
Tasks that Marathon has started after the backup was created will be unknown to Marathon after a restore operation and will be killed.
Tasks that Marathon has killed after the backup was created will be expunged during the next reconciliation.
All changes to apps, groups, and pods after the backup has been created, are lost if this backup is restored.

## Backup

A backup operation reads the existing state from Marathon in ZooKeeper and writes this data to a storage location.
This data contains all entities under `zk://host:port/path/state/`. 
It will not backup the state of the leader election: `zk://host:port/path/leader/`

A backup is a single file in [tar](http://www.gnu.org/software/tar/) format, which contains all elements as file entries.

### Options to create a backup 

There are multiple ways to create a backup:

- cmd line flag `--backup_location {URI}`
Marathon can be started with the command line option: `--backup_location` to a configurable URI. 
This option is strongly recommended because it will automatically create a backup of Marathon's state each time Marathon performs a migration.
A migration usually happens during an upgrade of Marathon.

- API endpoint `DELETE /v2/leader?backup={URI}` (experimental)
You can create a backup at any time using the leader endpoint.
The API call above will force the current leader to abdicate. The next leader will create a backup before it takes over leadership.
This method is experimental: the API endpoint may change in the future.

- Executable packaged with Marathon
The default Marathon package ships with a start script you can use to make a backup. </br>
**Note**: This command connects directly to ZooKeeper and backs up state from the location you defined. 
Only use this method if there is no Marathon instance running.


```
$> marathon -main mesosphere.marathon.core.storage.backup.Backup --help
$> marathon -main mesosphere.marathon.core.storage.backup.Backup --backup_location {backup_uri} --zk {zk_uri}
```


## Restore

You can restore Marathon to a backup you have created. 

Every restore operation will perform the following steps:
- Remove the current state in ZooKeeper under the defined path.
- Restore the state from the backup to the defined ZooKeeper path.
- The next Marathon leader migrates this state to the current version.

### Options to restore from a backup 

There are multiple ways to restore a backup:

- API endpoint `DELETE /v2/leader?restore={URI}` (experimental)
You can use the `leader` endpoint to instruct a running Marathon instance to restore its state from backup.
The API call above will force the current leader to abdicate. 
The next leader will perform a clean and restore operation, do a migration (if needed), and start up.
This method is experimental: the API endpoint may change in the future.

- Executable packaged with Marathon
The default Marathon package ships with a start script you can use to restore from a backup.
**Note:** This command connects directly to ZooKeepter and restores the state from the location you defined. 
Only use this method if there is no Marathon instance running.
 
 
```
$> marathon -main mesosphere.marathon.core.storage.backup.Restore --help
$> marathon -main mesosphere.marathon.core.storage.backup.Restore --backup_location {backup_uri} --zk {zk_uri}
```


## Backup Storage Provider

The backup & restore functionality allows different storage providers to save and load a backup.
Currently, `file` and `s3` providers are implemented. 
If you need additional providers, please create a feature request in our [issue tracker](https://jira.mesosphere.com/secure/CreateIssue!default.jspa?pid=10401)
  
### File storage
This provider uses the file system to store and load a backup.
It uses the default file URI syntax.

Example: `file:///path/to/file`

Attention: do not use the local file system if you are running a multi-master setup or the leading master can run on different machines.
The backup and restore operation could be performed on different machines and the backup file might not be accessible.
Use a shared file system to use the file storage provider in such a setup.

### S3 storage
This provider will use Amazon S3 to store and load a backup.
Define an S3 location with the following URI syntax. See [S3 tools](http://s3tools.org/s3_about) for more information.

Example: `s3://bucket-name/key/in/bucket`

The following query parameters are supported:
- `region` - the s3 region. Defaults to `us-east-1`
- `access_key` and `secret_key` - to define the s3 credentials in the URI.
  Only define these parameters in a fully secured environment: they will be stored in ZooKeeper and also visible in the logs.
  See the provider chain to make credentials available without defining them in the URI. 

Provider chain:

If credentials are not provided via the URI, the [AWS default credentials provider chain](http://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html) is used to look up aws credentials.
