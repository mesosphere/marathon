package mesosphere.marathon

/**
  * The group role behavior defines whether groups enforce their role or not by default.
  *
  *  - [[GroupRoleBehavior.Off]] indicates that groups will not enforce their role and apps will use
  *    the default Mesos role.
  *  - [[GroupRoleBehavior.Top]] indicates that only top-level groups, ie groups directly unser `/`
  *    will enforce their role, thus assign their name as the role for any [[raml.App]] or [[raml.Pod]].
  */
sealed trait GroupRoleBehavior {
  val name: String
  override def toString: String = name
}

object GroupRoleBehavior {
  case object Off extends GroupRoleBehavior {
    override val name: String = "Off"
  }

  case object Top extends GroupRoleBehavior {
    override val name: String = "Top"
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
