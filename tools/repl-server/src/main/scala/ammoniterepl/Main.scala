package ammoniterepl

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import ammonite.ops._
import ammonite.sshd._
import mesosphere.marathon.{MarathonApp, Main => MarathonMain}
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.config.keys.AuthorizedKeysAuthenticator
import org.apache.sshd.server.session.ServerSession
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scala.reflect.ClassTag

object Main {
  var app: MarathonApp = _
  object Prelude {
    def inject[T](implicit ct: ClassTag[T]): T = {
      app.injector.getInstance(ct.runtimeClass).asInstanceOf[T]
    }
    def instanceTracker = inject[mesosphere.marathon.core.task.tracker.InstanceTracker]
    def coreModule = inject[mesosphere.marathon.core.CoreModule]
    def storageModule = coreModule.storageModule

    implicit def actorSystem = inject[ActorSystem]
    implicit lazy val materializer = ActorMaterializer()
    implicit val executionContext = scala.concurrent.ExecutionContext.global

    def await[T](future: Future[T]): T = Await.result(future, 10.seconds)
  }

  def main(args: Array[String]): Unit = {
    app = new MarathonApp(MarathonMain.envToArgs(sys.env) ++ args.toVector)

    val host = sys.env.getOrElse("AMMONITE_HOST", "localhost")
    val port = sys.env.getOrElse("AMMONITE_PORT", "22222").toInt
    val keyPath: Option[Path] = sys.env.get("AMMONITE_PUBKEY_PATH") match {
      case None => Some(home / ".ssh" / "id_rsa.pub")
      case Some("") => None
      case Some(path) => Some(Path(path))
    }

    val sshUser = sys.env.getOrElse("AMMONITE_USER", "debug")
    val sshPass = sys.env.get("AMMONITE_PASS")

    val pubkeyAuth: Option[PublickeyAuthenticator] = keyPath.map { kp => new AuthorizedKeysAuthenticator(kp.toIO) }
    val passAuth: Option[PasswordAuthenticator] = sshPass.map { pass =>
      new PasswordAuthenticator {
        def authenticate(username: String, password: String, session: ServerSession): Boolean = {
          (username -> password) == (sshUser -> pass)
        }
      }
    }

    val sshConfig = SshServerConfig(
      address = host,
      port = port,
      publicKeyAuthenticator = pubkeyAuth,
      passwordAuthenticator = passAuth)

    val predef = """
import mesosphere.marathon._
import ammoniterepl.Main.app
import ammoniterepl.Main.Prelude._
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.stream.scaladsl._
import akka.stream.ActorMaterializer
import akka.NotUsed
import akka.Done
import scala.concurrent.duration._
"""

    val replServer = new SshdRepl(
      sshConfig = sshConfig,
      predef = predef
    )

    println("Starting repl server")
    replServer.start()
    println("Starting app")
    app.start()
  }
}
