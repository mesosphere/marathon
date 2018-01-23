package mesosphere.marathon.repository

import akka.NotUsed
import akka.stream.scaladsl.Flow
import mesosphere.marathon.state._

object StorageLayer {
  sealed trait StorageCommand {
    val requestId: Int
  }

  case class StoreApps(
    runSpec: RunSpec)

  val storageLayer: Flow[StorageCommand, Int, NotUsed] = ???
}
