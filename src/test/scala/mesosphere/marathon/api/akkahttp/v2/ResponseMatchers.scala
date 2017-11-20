package mesosphere.marathon
package api.akkahttp.v2

import mesosphere.UnitTest
import org.scalatest.matchers.{ HavePropertyMatchResult, HavePropertyMatcher }
import play.api.libs.json.{ JsNumber, JsObject, JsValue }

trait ResponseMatchers { this: UnitTest =>

  /**
    * Have property matcher for pods executor resources.
    *
    * @param cpus expected CPUs
    * @param mem expected memory
    * @param disk expected disk
    * @return Match result.
    */
  def executorResources(cpus: Double, mem: Double, disk: Double) = new HavePropertyMatcher[JsValue, Option[JsValue]] {
    override def apply(actual: JsValue) = {
      val maybeActual = (actual \ "executorResources").toOption
      val expected = JsObject(Seq("cpus" -> JsNumber(cpus), "mem" -> JsNumber(mem), "disk" -> JsNumber(disk)))
      val matches = maybeActual.contains(expected)
      HavePropertyMatchResult(matches, "executorResources", Some(expected), maybeActual)
    }
  }

  /**
    * Have property matcher verifying that no networkname has been defined.
    */
  val noDefinedNetworkname = new HavePropertyMatcher[JsValue, Option[JsValue]] {
    override def apply(actual: JsValue) = {
      val actualNetworkname = (actual \ "networks" \ 0 \ "name").toOption
      val matches = !actualNetworkname.isDefined
      HavePropertyMatchResult(matches, "networkname", None, actualNetworkname)
    }
  }

  /**
    * Have property matcher for network name of pod.
    * @param name Expected name of network
    * @return Match result
    */
  def definedNetworkname(name: String) = new HavePropertyMatcher[JsValue, Option[String]] {
    override def apply(actual: JsValue) = {
      val maybeNetworkname = (actual \ "networks" \ 0 \ "name").asOpt[String]
      val matches = maybeNetworkname.contains(name)
      HavePropertyMatchResult(matches, "networkname", Some(name), maybeNetworkname)
    }
  }

  /**
    * Have property matcher for network mode of pod.
    * @param mode Expected mode.
    * @return Match result
    */
  def networkMode(mode: raml.NetworkMode) = new HavePropertyMatcher[JsValue, Option[String]] {
    override def apply(actual: JsValue) = {
      val maybeMode = (actual \ "networks" \ 0 \ "mode").asOpt[String]
      val matches = maybeMode.contains(mode.value)
      HavePropertyMatchResult(matches, "networkmode", Some(mode.value), maybeMode)
    }
  }

  /**
    * Have property matcher for pod environment secret.
    * @param secret Expected secret
    * @return Match result
    */
  def podContainerWithEnvSecret(secret: String) = new HavePropertyMatcher[JsValue, Option[String]] {
    override def apply(actual: JsValue) = {
      val maybeSecret = (actual \ "containers" \ 0 \ "environment" \ "vol" \ "secret").asOpt[String]
      val matches = maybeSecret.contains(secret)
      HavePropertyMatchResult(matches, "podContainerSecret", Some(secret), maybeSecret)
    }
  }

  /**
    * Have property matcher for file based secret.
    * @param secret Expected secret
    * @return Match result
    */
  def podWithFileBasedSecret(secret: String) = new HavePropertyMatcher[JsValue, Option[String]] {
    override def apply(actual: JsValue) = {
      val maybeSecret = (actual \ "volumes" \ 0 \ "secret").asOpt[String]
      val matches = maybeSecret.contains(secret)
      HavePropertyMatchResult(matches, "podContainerSecret", Some(secret), maybeSecret)
    }
  }
}
