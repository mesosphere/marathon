package mesosphere.marathon.state

import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.Protos.AutoScaleInfo
import mesosphere.marathon.Protos.AutoScaleInfo.ScalingPolicyInfo
import org.apache.mesos.Protos

import scala.collection.JavaConverters._

case class AutoScaleDefinition(policies: Seq[AutoScalePolicyDefinition]) {
  def toProto: AutoScaleInfo = {
    AutoScaleInfo.newBuilder()
      .addAllPolicies(policies.map(_.toProto).asJava)
      .build()
  }
}

object AutoScaleDefinition {

  def fromProto(proto: AutoScaleInfo): AutoScaleDefinition = {
    val policies = proto.getPoliciesList.asScala.map(AutoScalePolicyDefinition.fromProto)
    AutoScaleDefinition(policies)
  }

  implicit lazy val autoScaleValidator = validator[AutoScaleDefinition] { scaleDef =>
    scaleDef.policies has size <= 1
    scaleDef.policies is valid
  }
}

case class AutoScalePolicyDefinition(name: String, parameter: Map[String, String]) {
  def toProto: ScalingPolicyInfo = {
    val params = parameter.map {
      case (key, value) =>
        Protos.Parameter.newBuilder().setKey(key).setValue(value).build()
    }
    ScalingPolicyInfo.newBuilder()
      .setName(name)
      .addAllParameters(params.asJava)
      .build()
  }
}

object AutoScalePolicyDefinition {
  def fromProto(proto: ScalingPolicyInfo): AutoScalePolicyDefinition = {
    val params = proto.getParametersList.asScala.map(t => t.getKey -> t.getValue).toMap
    AutoScalePolicyDefinition(proto.getName, params)
  }

  implicit lazy val policyValidator = validator[AutoScalePolicyDefinition] { policy =>
    // The mesos agent count algorithm
    policy.name should matchRegexFully("^mesos_agent_count$")
    policy.parameter is empty
  }
}
