package mesosphere.marathon
package raml

/**
  * Consolidation of apps-related API things, for example special labels, environment variables, and
  * default values.
  */
trait Apps {

  val LabelSingleInstanceApp = "MARATHON_SINGLE_INSTANCE_APP"
  val LabelDcosMigrationApiPath = "DCOS_MIGRATION_API_PATH"
  val LabelDcosMigrationApiVersion = "DCOS_MIGRATION_API_VERSION"
  val LabelDcosPackageFrameworkName = "DCOS_PACKAGE_FRAMEWORK_NAME"

  /**
    * should be kept in sync with [[mesosphere.marathon.state.AppDefinition.DefaultNetworks]]
    */
  val DefaultNetworks = Seq(Network(mode = NetworkMode.Host))
  val DefaultPortDefinitions = Seq(PortDefinition(name = Some("default")))
  val DefaultPortMappings = Seq(ContainerPortMapping(name = Option("default")))
  val DefaultAppResidency = Some(AppResidency())
  val DefaultResources = Resources(cpus = App.DefaultCpus, mem = App.DefaultMem, disk = App.DefaultDisk, gpus = App.DefaultGpus)
}

object Apps extends Apps
