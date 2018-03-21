#!/usr/bin/env amm

import $file.dependencies
import $file.util
import $file.logformat

import logformat.{LogFormat}
import scala.annotation.tailrec
import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, Materializer }
import akka.stream.IOResult
import akka.util.ByteString
import akka.NotUsed
import akka.stream.scaladsl._
import ammonite.ops._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Try, Success, Failure}
import scala.concurrent.ExecutionContext.Implicits.global

@tailrec def await[T](f: Future[T]): T = f.value match {
  case None =>
    Thread.sleep(10)
    await(f)
  case Some(v) =>
    v.get
}

def renderTemplate(template: Path, vars: (String, String)*): String =
  vars.foldLeft(read!(template)) { case (str, (env, value)) =>
    str.replace(s"%${env}%", value)
  }

def gzipSource(input: Path, maxChunkSize: Int = 1024): Source[ByteString, Future[IOResult]] = {
  FileIO.fromPath(input.toNIO)
    .via(Compression.gunzip(maxChunkSize))
    .mapMaterializedValue { resultF =>
      resultF.andThen { case Success(result) =>
        if (!result.wasSuccessful) {
          println(s"WARNING! Error extracting ${input}; ${result.status}. ${result.count} bytes were written.")
        }
      }
    }
}

def bundleLogGzipped(masterPath: Path) =
  masterPath / "dcos-marathon.service.gz"

def bundleLogGunzipped(masterPath: Path) =
  masterPath / "dcos-marathon.service"

def warningLineSplitter(file: Path, warnLength: Int) =
  Framing.delimiter(ByteString("\n"), 128000000, true).map { line =>
    if (line.length >= warnLength)
      println(s"WARNING!!! ${file} has a line length of ${line.length}")
    line
  }

def stripAnsiFlow(inputPath: Path): Flow[ByteString, ByteString, NotUsed] =
  Flow[ByteString]
    .via(warningLineSplitter(inputPath, 128000))
    .map { bytes => ByteString(util.stripAnsi(bytes.utf8String)) }
    .intersperse(ByteString("\n"))

def unzipAndStripBundleLogs(masters: Map[String, Path])(implicit mat: Materializer): Map[String, Path] = {
  masters.map { case (master, masterPath) =>
    val gunzippedFilePath = bundleLogGunzipped(masterPath)
    if (!gunzippedFilePath.toIO.exists) {

      val gzippedFilePath = bundleLogGzipped(masterPath)
      println(s"Extracting ${gzippedFilePath} to ${gunzippedFilePath}")
      val result = await {
        gzipSource(gzippedFilePath)
          .via(stripAnsiFlow(gzippedFilePath))
          .runWith(FileIO.toPath(gunzippedFilePath.toNIO))
      }
    }
    master -> gunzippedFilePath
  }
}

def detectLogFormat(logFiles: Seq[Path])(implicit mat: Materializer): Option[LogFormat] = {
  logFiles
    .filter(_.toIO.exists)
    .toSeq
    .sortBy(_.toIO.length)
    .reverse
    .toStream
    .map { input =>
      val result = Try {
        val linesSample = await(
          FileIO.fromPath(input.toNIO)
            .via(warningLineSplitter(input, 128000))
            .take(100)
            .map(_.utf8String)
            .runWith(Sink.seq))

        val maybeFormat = (for {
          line <- linesSample.take(100)
          format <- LogFormat.tryMatch(line)
        } yield format).headOption

        maybeFormat
      }
      input -> result
    }
    .flatMap {
      case (input, Success(Some(format))) =>
        println(s"""Detected log format in ${input}: "${format.example}"""")
        Some(format)
      case (input, Success(None)) =>
        println(s"Failed to detect format in ${input}")
        None
      case (input, Failure(ex)) =>
        println(s"Excpetion occurred while detecting format in ${input}: ${ex.getMessage}")
        None
    }
    .headOption
}

def setupTarget(target: Path): (Path, Path, Path, Path) = {
  val loading = target / 'loading
  val printing = target / 'printing
  val json = target / 'json
  rm(target)
  Seq(target,loading,printing,json).foreach(mkdir!(_))
  (target, printing, loading, json)
}

def writeFiles(entries: (Path, String)*): Unit = {
  entries.foreach { case (path, contents) =>
    write.over(path, contents)
    println(s"Wrote ${path}")
  }
}

def generateLogstashConfig(inputPath: Path, targetPath: Path, logFormat: LogFormat, files: Map[String, Path]) = {
  val (target, printing, loading, json) = setupTarget(targetPath)

  // Write out the debug template set
  val tcpReader = renderTemplate(
    pwd / "conf" / "input-tcp.conf.template",
    "CODEC" -> logFormat.codec)

  writeFiles(
    printing / "10-input.conf" -> tcpReader,
    printing / "15-filters-format.conf" -> logFormat.unframe,
    printing / "20-filters.conf" -> (read!(pwd / "conf" / "filter-marathon-1.4.x.conf")),
    printing / "30-output.conf" -> (read!(pwd / "conf" / "output-console.conf")))

  for {
    (host, logPath) <- files
    targetPath <- Seq(json, loading)
  } {

    val inputConf = renderTemplate(
      pwd / "conf" / "input-file.conf.template",
      "FILE" -> util.escapeString(logPath.toString),
      "SINCEDB" -> util.escapeString((targetPath / s"since-db-${host}.db").toString),
      "CODEC" -> logFormat.codec,
      "EXTRA" -> s"""|"add_field" => {
                     |  "file_host" => ${util.escapeString(host)}
                     |}
                     |""".stripMargin
    )
    writeFiles(targetPath / s"10-input-${host}.conf" -> inputConf)
  }

  writeFiles(
    loading / "11-filters-host.conf" -> (read!(pwd / "conf" / "filter-overwrite-host-with-file-host.conf")),
    loading / "15-filters-format.conf" -> logFormat.unframe,
    loading / "20-filters.conf" -> (read!(pwd / "conf" / "filter-marathon-1.4.x.conf")),
    loading / "30-output.conf" -> (read!(pwd / "conf" / "output-elasticsearch.conf")),
    target / "data-path.txt" -> inputPath.toString)

  writeFiles(
    json / "11-filters-host.conf" -> (read!(pwd / "conf" / "filter-overwrite-host-with-file-host.conf")),
    json / "15-filters-format.conf" -> logFormat.unframe,
    json / "20-filters.conf" -> (read!(pwd / "conf" / "filter-marathon-1.4.x.conf")),
    json / "30-output.conf" -> renderTemplate(
      pwd / "conf" / "output-json-ld.conf.template",
      "FILE" ->  util.escapeString((target / "output.json.ld").toString)))
}

/**
  * Generate logstash config to target a DCOS bundle
  *
  * @param path The path to the DCOS bundle
  */
def generateTargetBundle(bundlePath: Path, targetPath: Path): Unit = {
  implicit val as = ActorSystem()
  implicit val mat = ActorMaterializer()
  println(s"Generating logstash config for DCOS bundle ${bundlePath}")

  val entries = ls!(bundlePath)
  val masterPaths = entries.filter(_.last.endsWith("_master"))
  val masters = masterPaths.map { path => path.last.takeWhile(_ != '_') -> path }.toMap

  println(s"${masters.size} masters discovered: ${masters.keys.mkString(", ")}")

  val unzippedLogLocations = unzipAndStripBundleLogs(masters)

  val logFormat = detectLogFormat(unzippedLogLocations.values.toSeq).getOrElse {
    throw new Exception("Couldn't detect log format in any input files")
  }

  generateLogstashConfig(bundlePath, targetPath, logFormat, unzippedLogLocations)

  println(s"All Done")
}


def generateTargetFile(input: Path, targetPath: Path): Unit = {
  implicit val as = ActorSystem()
  implicit val mat = ActorMaterializer()
  println(s"Generating logstash config for file ${input}")

  val strippedPath = input / up / (input.last.split('.').head + ".stripped")

  if (!strippedPath.toIO.exists) {
    val isGzip = input.last.split('.').last == "gz"

    val source = if (isGzip)
      gzipSource(input)
    else
      FileIO.fromPath(input.toNIO)

    println(s"Stripping ansi and writing to ${strippedPath}")
    val result = await {
      source
        .via(stripAnsiFlow(input))
        .runWith(FileIO.toPath(strippedPath.toNIO))
    }
  }

  val logFormat = detectLogFormat(Seq(strippedPath)).getOrElse {
    throw new Exception("Couldn't detect log format in ${strippedPath}")
  }

  val host = logFormat.host.getOrElse { input.last.split('.').head }

  generateLogstashConfig(input, targetPath, logFormat, Map(host -> strippedPath))

  println(s"All Done")
}

sealed trait Kind
case object File extends Kind
case object Bundle extends Kind

implicit val KindRead: scopt.Read[Kind] = scopt.Read.reads {
  case "bundle" => Bundle
  case "file" => File
  case o => throw new RuntimeException(s"Invalid kind: ${o}; must be bundle or file")
}

/** Configration for the build
 *
 * @param kind target or
 * @param targets All release targets that should be executed.
 * @param runTests indicates whether to run tests of builds before a release.
 */
case class Config(
  kind: Kind,
  path: Path,
  targetPath: Path)

// Scopt parser for release config
val parser = new scopt.OptionParser[Config]("target") {
  head("LogStash config generator")
  arg[Kind]("kind").action { (kind, c) =>
    c.copy(kind = kind)
  }.text(s"kind of artifact to target; bundle, or single log file")

  arg[Path]("path").required.action { (path, c) =>
    c.copy(path = path)
  }.text("whether to run tests for build")

  opt[Path]("target-path").action { (targetPath, c) =>
    c.copy(targetPath = targetPath)
  }

  help("help").text("prints this usage text")
}

@main def config(args: String*): Unit = {
  val config = parser.parse(args, Config(File, root, pwd / 'target)).getOrElse {
    sys.exit(1)
    ???
  }
  config.kind match {
    case Bundle =>
      generateTargetBundle(config.path, config.targetPath)
    case File =>
      generateTargetFile(config.path, config.targetPath)
  }
}
