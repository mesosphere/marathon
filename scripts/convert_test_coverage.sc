#!/usr/bin/env amm

/**
  * Convert test coverage data into the format that Phabricator/Harbormaster understands which is actually a
  * 'fake' unit test.
  *
  * {{{
  * {
  *    "name": "Test Coverage"
  *    "result": "pass"
  *    "coverage": {
  *       "file": "NNUCCC"
  *    }
  * }
  * }}}
  *
  * N = Not Executable
  * U = Not Covered
  * C = Covered
  */

import $ivy.`com.typesafe.play:play-json_2.11:2.5.12`

import scala.xml.XML
import play.api.libs.json._
import java.io.File

val report = new File("target/scala-2.11/coverage-report/cobertura.xml")
if (report.exists() && report.canRead()) {
  val xml = XML.loadFile(report)
  // Map(filename -> Map(lineNo -> hit count))
  val lineHits = (xml \\ "class").map { `class` =>
    var results = Map.empty[Int, Int]
    (`class` \\ "methods" \ "method" \ "lines" \ "line").map { line =>
      val lineNo = (line \ "@number").text.toInt
      val hits = results.get(lineNo).fold(0)(_ + (line \ "@hits").text.toInt)
      results = results + (lineNo -> hits)
    }

    (`class` \ "@filename").text -> results
  }

  // N = Not Executable, C = Covered, U = NotCovered
  // Map(filename -> NNCCU)
  val coverage: Map[String, String] = lineHits.map { case (file, lineData) =>
    file -> 1.to(lineData.keys.max).map { lineNo =>
      lineData.get(lineNo) match {
        case None => "N"
        case Some(x) if x == 0 => "U"
        case Some(x) => "C"
      }
    }.mkString("")
  }(collection.breakOut)

  case class HMTest(coverage: Map[String, String], result: String = "pass", name: String = "Test Coverage")
  implicit val hmtestFormat = Json.format[HMTest]
  println(Json.toJson(Seq(HMTest(coverage))))
} else {
  println("[]")
}
