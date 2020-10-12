package mesosphere.marathon

/**
  * The group role behavior defines whether groups enforce their role or not by default.
  *
  *  - [[NewGroupEnforceRoleBehavior.Off]] indicates that groups will not enforce their role and apps will use
  *    the default Mesos role.
  *  - [[NewGroupEnforceRoleBehavior.Top]] indicates that only top-level groups, ie groups directly under `/`
  *    will enforce their role, thus assign their name as the role for any [[raml.App]] or [[raml.Pod]].
  */
sealed trait NewGroupEnforceRoleBehavior {
  val name: String
  val option: Option[Boolean]
  override def toString: String = name
}

object NewGroupEnforceRoleBehavior {
  case object Off extends NewGroupEnforceRoleBehavior {
    override val name: String = "Off"
    override val option = Some(false)
  }

  case object Top extends NewGroupEnforceRoleBehavior {
    override val name: String = "Top"
    override val option = Some(true)
  }

  val all = Seq(Off, Top)

  def fromString(s: String): Option[NewGroupEnforceRoleBehavior] = {
    s.toLowerCase match {
      case "off" => Some(NewGroupEnforceRoleBehavior.Off)
      case "top" => Some(NewGroupEnforceRoleBehavior.Top)
      case _ => None
    }
  }
}
