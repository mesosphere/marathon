import akka.stream.scaladsl.{Source, Sink}
import akka.util.Timeout
import scala.concurrent.{Future, Await}
import akka.stream.Materializer
import scala.collection.immutable.Seq

object InternalHelpers {
  /**
    * get arguments from environment variable; we need to do this because
    * Ammonite is launched in repl mode, and as of Ammonite 1.0.1, repl mode
    * does not take additional program arguments.
    */
  def argsFromEnv: List[String] = {
    "(?<!\\\\)( +)".r.split(sys.env.getOrElse("MARATHON_ARGS", "").trim)
      .filterNot(_ == "")
      .map { arg =>
        /* This unescapes an argument by simply removing the leading backslashes
         * 'zk://zk-1.zk\,zk-2.zk:8181/marathon'
         *   becomes
         * 'zk://zk-1.zk,zk-2.zk:8181/marathon'
         */
        arg.replaceAll("\\\\(.)", "$1")
      }
      .toList
  }
}

object Helpers {
  def await[T](f: Future[T])(implicit timeout: Timeout): T = {
    Await.result(f, timeout.duration)
  }

  def await[T](s: Source[T, Any])(implicit timeout: Timeout, mat: Materializer): Seq[T] = {
    val f: Future[Seq[T]] = s.completionTimeout(timeout.duration).runWith(Sink.seq[T])
    await(f)
  }

}
