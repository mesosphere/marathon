package mesosphere.marathon

import java.io.File
import javax.inject.{ Named, Inject }

import akka.actor.{ Props, ActorSystem, Actor }
import akka.event.EventStream
import mesosphere.marathon.api.{ ModelValidation, BeanValidation }
import mesosphere.marathon.event.{ EventModule, ElectedAsLeader }
import mesosphere.marathon.io.IO
import mesosphere.marathon.state.{ GroupManager, PathId, AppDefinition }
import org.apache.log4j.Logger
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.util.control.NonFatal
import mesosphere.marathon.api.v2.json.Formats._

class ApplicationOnInitStarter @Inject() (
    system: ActorSystem,
    groupManager: GroupManager,
    config: MarathonConf,
    @Named(EventModule.busName) eventStream: EventStream) {
  config.initialAppsDirectory.get.map { dir =>
    system.actorOf(Props(classOf[ApplicationOnInitStarterActor], groupManager, eventStream, new File(dir)))
  }
}

/**
  * This actor waits until the instance gets elected as leader.
  * It checks for the
  */
class ApplicationOnInitStarterActor(
    groupManager: GroupManager,
    eventStream: EventStream,
    dir: File) extends Actor with IO {

  implicit val ec = mesosphere.util.ThreadPoolContext.context
  private[this] val log = Logger.getLogger(getClass.getName)

  override def preStart(): Unit = eventStream.subscribe(self, classOf[ElectedAsLeader])

  /**
    * Read all applications from the install directory and start the application.
    */
  def installApplications(): Future[Seq[Boolean]] = {
    val futures = listFiles(dir, """.*json$""".r).map { file =>
      try {
        log.info(s"Start application from install directory: $file")
        val app = Json.parse(readFile(file)).as[AppDefinition]
        BeanValidation.requireValid(ModelValidation.checkAppConstraints(app, PathId.empty))
        groupManager.updateApp(app.id, _ => app).map(_ => true)
      }
      catch {
        case NonFatal(ex) =>
          log.warn(s"Could not import application json from $file", ex)
          Future.successful(false)
      }
    }
    Future.sequence(futures.toList)
  }

  override def receive: Receive = {
    case ElectedAsLeader(_, _) =>
      //install initial applications only, if there is no application defined
      groupManager
        .root(withLatestApps = false)
        .filter(_.transitiveApps.isEmpty)
        .foreach(_ => installApplications())
  }
}
