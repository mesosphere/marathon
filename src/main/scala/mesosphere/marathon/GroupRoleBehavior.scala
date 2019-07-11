package mesosphere.marathon

sealed trait GroupRoleBehavior {
  val name: String
}

object GroupRoleBehavior {
  case object Off extends GroupRoleBehavior {
    override val name: String = "off"

    override def toString: String = name
  }

  case object Top extends GroupRoleBehavior {
    override val name: String = "top"
  }

  val all = Seq(Off, Top)

  def fromString(s: String): Option[GroupRoleBehavior] = {
    s.toLowerCase match {
      case "off" => Some(GroupRoleBehavior.Off)
      case "top" => Some(GroupRoleBehavior.Top)
      case _ => None
    }
  }
}
