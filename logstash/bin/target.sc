#!/usr/bin/env amm

import scala.annotation.tailrec
import $ivy.`com.typesafe.akka::akka-stream:2.5.7`
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.ByteString
import akka.stream.scaladsl._
import ammonite.ops._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import java.io.{BufferedReader, InputStreamReader, FileInputStream}
import java.util.zip.GZIPInputStream

class LineIterator(reader: BufferedReader) extends Iterator[String] {
  var currentLine: String = _
  def next(): String = {
    val nextLine = currentLine
    currentLine = reader.readLine()
    nextLine
  }
  def hasNext = true

  next()
}

def withoutColorCodes(msg: String) =
  msg.replaceAll("\u001B\\[[;\\d]*m", "")

def openGzipLines[T](file: Path)(fn: Iterator[String] => T): T = {
  val reader = new BufferedReader(
    new InputStreamReader(
      new GZIPInputStream(
        new FileInputStream(file.toIO))))
  val iterator = new LineIterator(reader)
  try fn(iterator)
  finally reader.close
}

trait LogFormat {
  def matches(s: String): Boolean
  val codec: String
  val unframe: String
}

@tailrec def await[T](f: Future[T]): T = f.value match {
  case None =>
    Thread.sleep(10)
    await(f)
  case Some(v) =>
    v.get
}

def renderTemplate(template: String, vars: (String, String)*): String =
  vars.foldLeft(template) { case (str, (env, value)) =>
    str.replace(s"%${env}%", value)
  }

object DcosLogFormat extends LogFormat {
  val regexPrefix = "^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}:".r
  val regex = s"${regexPrefix} \\[".r

  def matches(s: String): Boolean =
    regex.findFirstMatchIn(s).nonEmpty

  val codec = s"""|multiline {
                  |  pattern => "${regexPrefix} [^\\[]"
                  |  what => "previous"
                  |  max_lines => 1000
                  |}""".stripMargin

  val unframe = s"""|filter {
                    |  grok {
                    |    match => {
                    |      "message" => "(%{YEAR}-%{MONTHNUM}-%{MONTHDAY} %{TIME}): *%{GREEDYDATA:message}"
                    |    }
                    |    tag_on_failure => []
                    |    overwrite => [ "message" ]
                    |  }
                    |}""".stripMargin

}

object LogFormat {
  val all = List(DcosLogFormat)
}


@main def config(path: Path): Unit = {
  implicit val as = ActorSystem()
  implicit val mat = ActorMaterializer()
  // val path = Path("/Users/tim/Downloads/athena/bundle-2017-12-22T18-10-40-485007522")
  println( path)

  val entries = ls!(path)
  val masterPaths = entries.filter(_.last.endsWith("_master"))
  val masters = masterPaths.map { path => path.last.takeWhile(_ != '_') -> path }.toMap

  println(s"${masters.size} discovered: ${masters.keys.mkString(", ")}")

  val target = pwd / 'target
  val loading = target / 'loading
  val printing = target / 'printing

  rm(target)
  Seq(target,loading,printing).foreach(mkdir!(_))

  def bundleLogGzipped(masterPath: Path) =
    masterPath / "dcos-marathon.service.gz"

  def bundleLogGunzipped(master: Path) =
    master / "dcos-marathon.service"

  def sampleIOSource = {
    val Some((compressed, input)) = masters.values.flatMap { master =>
      Seq(true -> bundleLogGzipped(master), false -> bundleLogGunzipped(master))
    }.find { case (_, f) => f.toIO.exists }

    val source = FileIO.fromPath(input.toNIO)
    if (compressed)
      source.via(Compression.gunzip(1024))
    else
      source
  }

  val linesSample = await(
    sampleIOSource.via(Framing.delimiter(ByteString("\n"), 128000000, false))
      .take(100)
      .map { bytes => withoutColorCodes(bytes.utf8String) }
      .runWith(Sink.seq))

  val maybeCodec = (for {
    line <- linesSample.take(100)
    codec <- LogFormat.all if codec.matches(line)
  } yield codec).headOption

  val codec = maybeCodec match {
    case Some(codec) => codec
    case _ =>
      println(s"Couldn't find a codec for these lines:")
      println()
      linesSample.foreach(println)
      // sys.exit(1)
      ???
  }

  // Write out the debug template set
  val tcpReaderTemplate = read!(pwd / "conf" / "tcp-reader.conf")
  val tcpReader = renderTemplate(tcpReaderTemplate, "CODEC" -> codec.codec)

  write.over(printing / "10-input.conf", tcpReader)
  write.over(printing / "12-filters-remove-ansi.conf", read!(pwd / "conf" / "filter-remove-ansi.conf"))
  write.over(printing / "15-filters-format.conf", codec.unframe)
  write.over(printing / "20-filters.conf", read!(pwd / "conf" / "dcos-marathon-1.4.x-filters-general.conf"))
  write.over(printing / "30-output.conf", read!(pwd / "conf" / "console-writer.conf"))

  def escapeString(s: String) = s""""${s}""""

  val fileReaderTemplate = read!(pwd / "conf" / "file-reader.conf")
  masters.foreach { case (master, masterPath) =>
    val gzippedFilePath = bundleLogGzipped(masterPath)
    val gunzippedFilePath = bundleLogGunzipped(masterPath)
    if (!gunzippedFilePath.toIO.exists) {
      if (!gzippedFilePath.toIO.exists) {
        throw new Exception("Couldn't find ${gzippedFilePath} or ${gunzippedFilePath}")
      }
      val result = await(FileIO.fromPath(gzippedFilePath.toNIO)
        .via(Compression.gunzip(1024))
        .runWith(FileIO.toPath(gunzippedFilePath.toNIO)))
      if (!result.wasSuccessful) {
        println(s"WARNING! Error extracting ${gzippedFilePath}; ${result.status}. ${result.count} bytes were written.")
      }
    }
    val loadingDbPath = loading / s"since-db-${master}.db"
    val inputConf = renderTemplate(fileReaderTemplate,
      "FILE" -> escapeString(gunzippedFilePath.toString),
      "SINCEDB" -> escapeString(loadingDbPath.toString),
      "CODEC" -> codec.codec,
      "EXTRA" -> s"""|"add_field" => {
                     |  "hostname" => ${escapeString(master)}
                     |}
                     |""".stripMargin
    )
    write.over(loading / s"10-input-${master}.conf", inputConf)
  }

  write.over(loading / "12-filters-remove-ansi.conf", read!(pwd / "conf" / "filter-remove-ansi.conf"))
  write.over(loading / "15-filters-format.conf", codec.unframe)
  write.over(loading / "20-filters.conf", read!(pwd / "conf" / "dcos-marathon-1.4.x-filters-general.conf"))
  write.over(loading / "30-output.conf", read!(pwd / "conf" / "es-writer.conf"))
}
