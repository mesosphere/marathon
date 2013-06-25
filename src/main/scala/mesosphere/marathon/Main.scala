package mesosphere.marathon

import com.yammer.dropwizard.ScalaService
import com.yammer.dropwizard.config.{Bootstrap, Environment}
import com.google.inject.Guice
import mesosphere.marathon.api.v1.ServiceResource
import com.yammer.dropwizard.bundles.ScalaBundle
import scala.collection.JavaConversions._

/**
 * @author Tobi Knaup
 */
object Main extends ScalaService[MarathonConfiguration] {
  def initialize(bootstrap: Bootstrap[MarathonConfiguration]) {
    bootstrap.setName("Marathon")
    bootstrap.addBundle(new ScalaBundle)
  }

  def run(config: MarathonConfiguration, env: Environment) {
    val injector = Guice.createInjector(List(
      new MarathonModule(config)
    ))

    env.addResource(injector.getInstance(classOf[ServiceResource]))
    env.addHealthCheck(injector.getInstance(classOf[MarathonHealthCheck]))
    env.manage(injector.getInstance(classOf[MarathonSchedulerManager]))
  }

}