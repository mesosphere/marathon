package mesosphere.marathon.state

import com.wix.accord._
import com.wix.accord.dsl._

case class Secret(
  source: String
)

object Secret {
  implicit val validSecret: Validator[Secret] = new Validator[Secret] {
    override def apply(secret: Secret): Result = {
      validate(secret.source)(notEmpty)
    }
  }

  val validSecretId: Validator[String] = new Validator[String] {
    override def apply(id: String): Result = {
      validate(id)(notEmpty)
    }
  }
}
