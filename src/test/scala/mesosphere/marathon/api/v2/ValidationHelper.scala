package mesosphere.marathon.api.v2

import com.wix.accord._

object ValidationHelper {
  case class ViolationMessageAndProperty(message: String, property: Option[String]) {
    override def toString: String = property.map(p => s"Property: $p. Message: $message")
      .getOrElse(s"Message: $message")
  }

  def getAllRuleConstrains(r: Result): Seq[ViolationMessageAndProperty] = {
    def loop(v: Violation, prop: Option[String]): Seq[ViolationMessageAndProperty] = {
      v match {
        case g: GroupViolation =>
          g.children.flatMap { c =>
            val nextProp = prop.map(p =>
              Some(c.description.map(desc => s"$p.$desc").getOrElse(p))).getOrElse(c.description)
            loop(c, nextProp)
          }.toSeq
        case r: RuleViolation => Seq(ViolationMessageAndProperty(r.constraint, prop))
      }
    }

    r match {
      case f: Failure => f.violations.flatMap(v => loop(v, v.description)).toSeq
      case _          => Seq.empty[ViolationMessageAndProperty]
    }
  }
}
