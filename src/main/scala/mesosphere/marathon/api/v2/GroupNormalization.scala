package mesosphere.marathon
package api.v2

object GroupNormalization {

  def partialUpdateNormalization(conf: MarathonConf): Normalization[raml.GroupPartialUpdate] = Normalization { update =>
    if (update.enforceRole.isEmpty) {
      val defaultEnforceRole = conf.groupRoleBehavior() match {
        case GroupRoleBehavior.Off => false
        case GroupRoleBehavior.Top => true
      }
      update.copy(enforceRole = Some(defaultEnforceRole))
    } else update
  }
}
