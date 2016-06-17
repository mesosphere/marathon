package mesosphere.marathon.state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.plugin

case class Secret(source: String) extends plugin.Secret

object Secret {
  def secretsValidator: Validator[Map[String, Secret]] = new Validator[Map[String, Secret]] {
    override def apply(secrets: Map[String, Secret]): Result = {
      secrets.toSeq.map(validate(_)(secretValidator)).fold(Success)(_ and _)
    }
  }

  def validSecret: Validator[Secret] = validator[Secret] { secret =>
    secret.source is notEmpty
  }

  def secretValidator: Validator[Tuple2[String, Secret]] = validator[Tuple2[String, Secret]] { t =>
    t._1 as s"(${t._1})" is notEmpty
    t._2 as s"(${t._1})" is valid(validSecret)
  }
}
