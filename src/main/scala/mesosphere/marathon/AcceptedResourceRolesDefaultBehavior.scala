package mesosphere.marathon

/**
  * The accepted resource roles default behavior defines whether which values are selected for the accepted resource roles by default
  *
  *  - [[AcceptedResourceRolesDefaultBehavior.Any]] indicates that either reserved or unreserved resources will be accepted. (default)
  *  - [[AcceptedResourceRolesDefaultBehavior.Unreserved]] Only unreserved ( offers with role '*' ) will be accepted.
  *  - [[AcceptedResourceRolesDefaultBehavior.Reserved]] Only reserved (offers with the role of the starting task) will be accepted.
  *
  */
sealed trait AcceptedResourceRolesDefaultBehavior {
  val name: String
  override def toString: String = name
}

object AcceptedResourceRolesDefaultBehavior {
  case object Any extends AcceptedResourceRolesDefaultBehavior {
    override val name: String = "Any"
  }

  case object Unreserved extends AcceptedResourceRolesDefaultBehavior {
    override val name: String = "Unreserved"
  }

  case object Reserved extends AcceptedResourceRolesDefaultBehavior {
    override val name: String = "Reserved"
  }

  val all = Seq(Any, Unreserved, Reserved)

  def fromString(s: String): Option[AcceptedResourceRolesDefaultBehavior] = {
    s.toLowerCase match {
      case "any" => Some(AcceptedResourceRolesDefaultBehavior.Any)
      case "unreserved" => Some(AcceptedResourceRolesDefaultBehavior.Unreserved)
      case "reserved" => Some(AcceptedResourceRolesDefaultBehavior.Reserved)
      case _ => None
    }
  }
}
