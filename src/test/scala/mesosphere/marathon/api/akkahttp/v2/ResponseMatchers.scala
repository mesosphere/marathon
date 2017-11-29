package mesosphere.marathon
package api.akkahttp.v2

import mesosphere.UnitTest
import mesosphere.marathon.state.PathId
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
  def definedNetworkName(name: String) = new HavePropertyMatcher[JsValue, Option[String]] {
    override def apply(actual: JsValue) = {
      val maybeNetworkName = (actual \ "networks" \ 0 \ "name").asOpt[String]
      val matches = maybeNetworkName.contains(name)
      HavePropertyMatchResult(matches, "networkName", Some(name), maybeNetworkName)
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
      HavePropertyMatchResult(matches, "networkMode", Some(mode.value), maybeMode)
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

  /**
    * Have property matcher for id (e.g. of an app)
    * @param pathId id of an app
    * @return Match result
    */
  def id(pathId: PathId) = new HavePropertyMatcher[JsValue, Option[PathId]] {
    override def apply(actual: JsValue) = {
      val maybeId = (actual \ "id").asOpt[String].map(PathId(_))
      HavePropertyMatchResult(maybeId.contains(pathId), "id", Some(pathId), maybeId)
    }
  }

  def podId(id: String) = new HavePropertyMatcher[JsValue, Option[String]] {
    override def apply(actual: JsValue) = {
      val maybeId = (actual \ "id").asOpt[String]
      val matches = maybeId.contains(id)
      HavePropertyMatchResult(matches, "podId", Some(id), maybeId)
    }
  }

  /**
    * Have property matcher for a valid task id.
    * @param taskId The expected task id.
    * @return Match result
    */
  def taskId(taskId: String) = new HavePropertyMatcher[JsValue, Option[String]] {
    override def apply(actual: JsValue) = {
      val maybeId = (actual \ "id").asOpt[String]
      val matches = maybeId.contains(taskId)
      HavePropertyMatchResult(matches, "id", Some(taskId), maybeId)
    }
  }

  /**
    * Have property matcher for a pod status.
    * @param state The expected RAML pod state
    * @return Match result
    */
  def podState(state: raml.PodState) = new HavePropertyMatcher[JsValue, Option[raml.PodState]] {
    override def apply(actual: JsValue) = {
      val maybeState = (actual \ "status").asOpt[String].flatMap(raml.PodState.fromString)
      val matches = maybeState.contains(state)
      HavePropertyMatchResult(matches, "podState", Some(state), maybeState)
    }
  }

  /**
    * Have property matcher for scaling policy instances.
    * @param instances expected number of instances
    * @return Match result
    */
  def scalingPolicyInstances(instances: Int) = new HavePropertyMatcher[JsValue, Option[Int]] {
    override def apply(actual: JsValue) = {
      val maybeScalingPolicy = (actual \ "scaling" \ "instances").asOpt[Int]
      val matches = maybeScalingPolicy.contains(instances)
      HavePropertyMatchResult(matches, "podScalingPolicy", Some(instances), maybeScalingPolicy)
    }
  }
}
