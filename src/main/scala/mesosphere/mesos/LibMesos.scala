package mesosphere.mesos

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.util.SemanticVersion
import org.apache.mesos.MesosNativeLibrary

object LibMesos extends StrictLogging {

  /**
    * The changelog should describe why this version is needed.
    */
  val MesosMasterMinimumVersion: SemanticVersion = SemanticVersion(1, 5, 0)

  val LibMesosMinimumVersion: SemanticVersion = MesosMasterMinimumVersion

  /**
    * Try to load the libmesos version.
    * @return SemanticVersion if libmesos is found a version could be read.
    */
  lazy val version: SemanticVersion = {
    // This can throw an java.lang.UnsatisfiedLinkError, that can not be handled
    MesosNativeLibrary.load()
    val version = MesosNativeLibrary.version()
    if (version.major < 0 || version.minor < 0 || version.patch < 0 ||
      version.major > 100 || version.minor > 100 || version.patch > 100) {
      logger.error(s"libmesos version returned ${version.major}.${version.minor}.${version.patch}; " +
        "this is likely due to an ABI mismatch in libmesos.")
      // Some versions of libmesos give garbage values, so consider those as unknown and don't load them.
      SemanticVersion(0, 0, 0)
    } else {
      SemanticVersion(version.major.toInt, version.minor.toInt, version.patch.toInt)
    }
  }

  /**
    * Indicates if this version of libmesos is compatible
    */
  def isCompatible: Boolean = {
    if (version < LibMesosMinimumVersion) {
      logger.error(s"Found libmesos version $version is incompatible with minimum required version: $LibMesosMinimumVersion")
    }
    version >= LibMesosMinimumVersion
  }

  /**
    * Indicates if the given version of Mesos Master is compatible.
    */
  def masterCompatible(masterVersion: String): Boolean = {
    SemanticVersion(masterVersion).exists(_ >= MesosMasterMinimumVersion)
  }
}
