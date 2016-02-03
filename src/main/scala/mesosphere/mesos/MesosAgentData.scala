package mesosphere.mesos

import play.api.libs.json._

/**
  * Information the mesos master has for every agent.
  * @param id the the agent.
  * @param pid the lib process id of the agent.
  * @param active indicates, if the agent is active or not.
  * @param hostname the host on which this agent is running on.
  * @param attributes the attributes of this agent.
  */
case class MesosAgentData(id: String, pid: String, active: Boolean, hostname: String, attributes: Map[String, String])

object MesosAgentData {

  implicit lazy val MesosSlaveDataFormat: Format[MesosAgentData] = {
    // mesos attributes values can either hold strings, numbers, booleans etc.
    implicit lazy val mapReads: Reads[Map[String, String]] = new Reads[Map[String, String]] {
      override def reads(json: JsValue): JsResult[Map[String, String]] = {
        def stringify(v: JsValue): String = v match {
          case JsString(value) => value
          case value: JsValue  => Json.stringify(value)
        }
        json match {
          case JsObject(props) => JsSuccess(props.map{ case (k, v) => k -> stringify(v) }.toMap)
          case _               => JsError("Json object expected")
        }
      }
    }
    Json.format[MesosAgentData]
  }
}

