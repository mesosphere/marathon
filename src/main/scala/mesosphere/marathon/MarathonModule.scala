package mesosphere.marathon

import com.google.inject.AbstractModule

/**
 * @author Tobi Knaup
 */

class MarathonModule(config: MarathonConfiguration) extends AbstractModule {
  def configure() {
    bind(classOf[MarathonSchedulerManager]).toInstance(
      new MarathonSchedulerManager(config)
    )
  }
}
