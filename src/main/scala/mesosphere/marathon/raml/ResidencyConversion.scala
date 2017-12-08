package mesosphere.marathon
package raml

trait ResidencyConversion {

  implicit val residencyWrites: Writes[state.Residency, Residency] = Writes { residency =>
    Residency(residency.relaunchEscalationTimeoutSeconds, residency.taskLostBehavior.toRaml)
  }

  implicit val residencyReads: Reads[Residency, state.Residency] = Reads { residency =>
    state.Residency(
      relaunchEscalationTimeoutSeconds = residency.relaunchEscalationTimeoutSeconds,
      taskLostBehavior = residency.taskLostBehavior.fromRaml
    )
  }
}

object ResidencyConversion extends ResidencyConversion
