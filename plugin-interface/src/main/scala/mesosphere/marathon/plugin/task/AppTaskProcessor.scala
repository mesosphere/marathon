package mesosphere.marathon.plugin.task

import mesosphere.marathon.plugin.AppDefinition
import mesosphere.marathon.plugin.plugin
import org.apache.mesos.Protos

/**
  * AppTaskProcessor is a factory func that generates functional options that mutate Mesos
  * task info's given some app specification. For example, a factory might generate functional
  * options that inject specific labels into a Mesos task info based on some properties of an
  * app specification.
  */
trait AppTaskProcessor extends plugin.Opt.Factory.Plugin[AppDefinition, Protos.TaskInfo.Builder]
