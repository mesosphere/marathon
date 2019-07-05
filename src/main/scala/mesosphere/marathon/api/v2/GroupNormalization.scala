package mesosphere.marathon
package api.v2

object GroupNormalization {

  def partialUpdateNormalization(conf: MarathonConf): Normalization[raml.GroupPartialUpdate] = Normalization { update =>
    if (update.enforceRole.isEmpty) {
      val defaultEnforceRole = raml.EnforceRole.fromString(conf.defaultEnforceGroupRole().name)
      update.copy(enforceRole = defaultEnforceRole)
    } else update
  }
}
