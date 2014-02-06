package mesosphere.marathon.api.v1

import mesosphere.marathon.Protos.MarathonTask
import scala.language.implicitConversions

/**
 * Implicits for the API
 *
 * @author Tobi Knaup
 */

object Implicits {

  implicit def MarathonTask2Map(task: MarathonTask): Map[String, Object] = {
    Map(
      "id" -> task.getId,
      "host" -> task.getHost,
      "ports" -> task.getPortsList
      // TODO attributes
    )
  }
}
