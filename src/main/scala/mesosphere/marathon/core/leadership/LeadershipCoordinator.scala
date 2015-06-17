package mesosphere.marathon.core.leadership

import scala.concurrent.Future

trait LeadershipCoordinator {
  /** Prepare for starting. After the Future completes, all actors are ready to receive messages. */
  def prepareForStart(): Future[Unit]
  def stop(): Unit
}
