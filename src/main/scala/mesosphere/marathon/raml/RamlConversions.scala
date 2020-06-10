package mesosphere.marathon
package raml

trait RamlConversions
    extends ConstraintConversion
    with ContainerConversion
    with EnvVarConversion
    with NetworkConversion
    with PodConversion
    with PodStatusConversion
    with VolumeConversion
    with HealthCheckConversion
    with QueueInfoConversion
    with AppConversion
    with InstanceConversion
    with DeploymentPlanConversion

object RamlConversions extends RamlConversions
