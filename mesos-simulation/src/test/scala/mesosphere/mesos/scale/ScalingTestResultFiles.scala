package mesosphere.mesos.scale

import java.io.File

import org.apache.commons.io.FileUtils
import play.api.libs.json._

object ScalingTestResultFiles {
  val resultDir: File = new File("./target")
  def jsonFile(name: String): File = new File(resultDir, s"scaleTest-$name.json")

  def writeJson[T](name: String, json: T)(implicit writes: Writes[T]): Unit = {
    FileUtils.write(jsonFile(name), Json.prettyPrint(Json.toJson(json)))
  }

  def readJson[T](name: String)(implicit reads: Reads[T]): T = {
    val fileString = FileUtils.readFileToString(jsonFile(name))
    val fileJson = Json.parse(fileString)
    Json.fromJson(fileJson).get
  }

  val relativeTimestampMs: String = "relativeTimestampMs"

  def addTimestamp(startTime: Long)(value: JsValue): JsObject = {
    value.transform(__.json.update((__ \ relativeTimestampMs).json.put(JsNumber(System.currentTimeMillis() - startTime)))).get
  }
}
