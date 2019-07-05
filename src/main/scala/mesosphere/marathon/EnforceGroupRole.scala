package mesosphere.marathon

sealed trait EnforceGroupRole {
  val name: String
}

object EnforceGroupRole {
  case object Off extends EnforceGroupRole {
    override val name: String = "off"

    override def toString: String = name
  }

  case object Top extends EnforceGroupRole {
    override val name: String = "top"
  }

  val all = Seq(Off, Top)

  def fromString(s: String): Option[EnforceGroupRole] = {
    s.toLowerCase match {
      case "off" => Some(EnforceGroupRole.Off)
      case "top" => Some(EnforceGroupRole.Top)
      case _ => None
    }
  }
}
