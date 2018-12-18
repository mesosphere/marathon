package mesosphere.marathon
package state

trait Volume extends plugin.VolumeSpec

/**
  * ExternalVolumeInfo captures the specification for a volume that survives task restarts.
  *
  * `name` is the *unique name* of the storage volume. names should be treated as case insensitive labels
  * derived from an alpha-numeric character range [a-z0-9]. while there is no prescribed length limit for
  * volume names it has been observed that some storage provider implementations may refuse names greater
  * than 31 characters. YMMV. Although `name` is optional, some storage providers may require it.
  *
  * `name` uniqueness:
  *  <li> A volume name MUST be unique within the scope of a volume provider.
  *  <li> A fully-qualified volume name is expected to be unique across the cluster and may formed, for example,
  *       by concatenating the volume provider name with the volume name. E.g “dvdi.volume123”
  *
  * `provider` is optional; if specified it indicates which storage provider will implement volume
  * lifecycle management operations for the external volume. if unspecified, “agent” is assumed.
  * the provider names “dcos”, “agent”, and "docker" are currently reserved. The contents of provider
  * values are restricted to the alpha-numeric character range [a-z0-9].
  *
  * `options` contains provider-specific volume options. some items may be required in order for a volume
  * driver to function properly. Given a storage provider named “dvdi” all options specific to that
  * provider MUST be namespaced with a “dvdi/” prefix.
  *
  * future DCOS-specific options will be prefixed with “dcos/”. an example of a DCOS option might be
  * “dcos/label”, a user-assigned, human-friendly label that appears in a UI.
  *
  * @param size absolute size of the volume (MB)
  * @param name identifies the volume within the context of the storage provider.
  * @param provider identifies the storage provider responsible for volume lifecycle operations.
  * @param options contains storage provider-specific configuration configuration
  */
case class ExternalVolumeInfo(
    size: Option[Long] = None,
    name: String,
    provider: String,
    options: Map[String, String] = Map.empty[String, String])

case class ExternalVolume(name: Option[String], external: ExternalVolumeInfo)
  extends Volume

case class PersistentVolumeInfo(
    size: Long,
    maxSize: Option[Long] = None,
    `type`: DiskType = DiskType.Root,
    profileName: Option[String] = None,
    constraints: Set[Constraint] = Set.empty)

case class PersistentVolume(name: Option[String], persistent: PersistentVolumeInfo)
  extends Volume
