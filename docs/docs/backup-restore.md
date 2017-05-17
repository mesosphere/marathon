---
title: Backup & Restore
---

# Backup & Restore

Marathon 1.5 and later has backup and restore functionality build in.
The complete state from the persistent data store can be backed up to an external file on the local disk or to an external storage provider.
A backup can be restored again and will bring back the exact same state as it was available by the time of backup creation.  

## Limitations

Marathon is only able to backup the internal state of Marathon itself. 
A backup will **not** capture the state of any other external system.
This is specifically true for Mesos, which has its own backup & restore functionality.
To create a backup of your cluster, a backup of all the system components is needed.

Restoring a backup in Marathon without restoring state in other system components can lead to unwanted behaviour.
Tasks that have been started by Marathon after the backup has been created, will be unknown to Marathon after restore and will be killed.
Tasks that have been killed by Marathon after the backup has been created, will be expunged during the next reconciliation.
All changes to apps, groups and pods after the backup has been created, are lost if this backup is restored.

## Backup

A backup operation reads the existing state from Marathon in zk and writes this data to a storage location.
This data contains all entities under `zk://host:port/path/state/`. 
It will not backup the state of the leader election: `zk://host:port/path/leader/`

A backup is a single file in [tar](http://www.gnu.org/software/tar/) format, which contains all elements as file entries.

### Options to create a backup 

There are multiple ways to create a backup:

- cmd line flag `--backup_location {URI}`
Marathon can be started with the command line option: `--backup_location` to a configurable URI. 
This option is especially useful and strongly recommended to set, since it will create a backup 
of the Marathon internal state, whenever Marathon performs a migration. 
A migration usually happens during an upgrade of Marathon.

- API endpoint `DELETE /v2/leader?backup={URI}` (experimental)
It is possible to create a backup at any point in time using the leader endpoint.
Using this endpoint will force the current running leader to abdicate.
The next elected leader will create a backup, before this instance takes over the leadership.
This method is marked experimental - the API endpoint may change in the future.

- Executable packaged with Marathon
The marathon native package ships with a start-script that can be used to create a backup.
Please note: this command will connect directly to zk and backup the state from the defined location. 
Use this method only, if there is no Marathon instance running!


```
$> marathon -main mesosphere.marathon.core.storage.backup.Backup --help
$> marathon -main mesosphere.marathon.core.storage.backup.Backup --backup_location {backup_uri} --zk {zk_uri}
```


## Restore

A backup that has been created, can be restored. Every restore operation will perform the following steps:
- the current state in zk under the defined path will be removed
- the state from the backup will be restored to the defined zk path 
- the next Marathon leader will migrate this state to the current version

### Options to restore from a backup 

There are multiple ways to restore a backup:

- API endpoint `DELETE /v2/leader?restore={URI}` (experimental)
It is possible to instruct a running marathon instance to restore its state from a backup.
Using this endpoint will force the current running leader to abdicate.
The next elected leader will perform a clean and restore operation, do a migration (if needed) and start up.
This method is marked experimental - the API endpoint may change in the future.

- Executable packaged with Marathon
The marathon native package ships with a start-script that can be used to restore a backup.
Please note: this command will connect directly to zk and restore the state from the defined location. 
Use this method only, if there is no Marathon instance running!
 
 
```
$> marathon -main mesosphere.marathon.core.storage.backup.Restore --help
$> marathon -main mesosphere.marathon.core.storage.backup.Restore --backup_location {backup_uri} --zk {zk_uri}
```


## Available Backup Storage Provider

The backup & restore functionality allows different storage providers to save and load a backup.
Currently `file` and `s3` providers are implemented. 
If you need additional providers, please create a feature request in our [issue tracker](https://jira.mesosphere.com/secure/CreateIssue!default.jspa?pid=10401)
  
### File storage
This provider uses the file system to store and load a backup.
It uses the default file URI syntax.

Example: `file:///path/to/file`

Attention: do not use the local file system if you are running a multi master setup or the leading master can run on different machines.
The backup and restore operation could be performed on different machines and the backup file might not be accessible.
Use a shared file system to use the file storage provider in such a setup.

### S3 storage
This provider will use the Amazon S3 storage to store and load a backup.
A S3 location is defined with this URI syntax (Also see [s3tools](http://s3tools.org/s3_about))

Example: `s3://bucket-name/key/in/bucket`

Following query parameter are allowed:
- `region` - the s3 region. Defaults to `us-east-1`
- `access_key` and `secret_key` - to define the s3 credentials in the URI.
  Define those parameters only in a fully secured environment, since those parameters would be stored in zk as well as be visible in the logs.
  See the provider chain to make credentials available without defining them in the URI. 

Provider chain:

If credentials are not provided via the URI, the [AWS default credentials provider chain](http://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html) is used to look up aws credentials.
