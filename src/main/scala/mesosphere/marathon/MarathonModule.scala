package mesosphere.marathon

import com.google.inject.{Singleton, Provides, Scopes, AbstractModule}
import org.apache.mesos.state.{ZooKeeperState, State}
import java.util.concurrent.TimeUnit

/**
 * @author Tobi Knaup
 */

class MarathonModule(conf: MarathonConfiguration) extends AbstractModule {
  def configure() {
    bind(classOf[MarathonConfiguration]).toInstance(conf)
    bind(classOf[MarathonSchedulerService]).in(Scopes.SINGLETON)
  }

  @Provides
  @Singleton
  def provideMesosState(): State = {
    new ZooKeeperState(
      conf.zooKeeperHosts.get.get,
      conf.zooKeeperTimeout.get.get,
      TimeUnit.SECONDS,
      conf.zooKeeperPath.get.get
    )
  }
}