package mesosphere.chaos

import com.google.common.util.concurrent.{ Service, ServiceManager }
import com.google.inject.{ Guice, Module }
import org.rogach.scallop.ScallopConf
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler

trait App extends scala.App {
  import scala.collection.JavaConverters._

  // Handle java.util.logging with SLF4J
  SLF4JBridgeHandler.removeHandlersForRootLogger()
  SLF4JBridgeHandler.install()

  lazy val injector = Guice.createInjector(modules().asJava)
  private val log = LoggerFactory.getLogger(getClass.getName)
  private var serviceManager: Option[ServiceManager] = None

  def conf(): ScallopConf with AppConfiguration

  def modules(): Iterable[_ <: Module]

  def run(classes: Class[_ <: Service]*) {
    val services = classes.map(injector.getInstance(_))
    val serviceManager = new ServiceManager(services.asJava)
    this.serviceManager = Some(serviceManager)

    sys.addShutdownHook(shutdownAndWait())

    serviceManager.startAsync()

    try {
      serviceManager.awaitHealthy()
    } catch {
      case e: Exception =>
        log.error(s"Failed to start all services. Services by state: ${serviceManager.servicesByState()}", e)
        shutdownAndWait()
        System.exit(1)
    }

    log.info("All services up and running.")
  }

  def shutdown() {
    log.info("Shutting down services")
    serviceManager.foreach(_.stopAsync())
  }

  def shutdownAndWait() {
    serviceManager.foreach { serviceManager =>
      shutdown()
      log.info("Waiting for services to shut down")
      serviceManager.awaitStopped()
    }
  }
}
