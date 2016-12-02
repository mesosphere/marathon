package mesosphere.marathon
package raml

/**
  * Conversion from [[mesosphere.marathon.state.KillSelection]] to [[mesosphere.marathon.raml.KillSelection]] and vice versa.
  */
trait KillSelectionConversion {

  implicit val ramlKillSelectionRead = Reads[KillSelection, state.KillSelection] {
    case KillSelection.Oldestfirst => state.KillSelection.OldestFirst
    case KillSelection.Youngestfirst => state.KillSelection.YoungestFirst
  }

  implicit val ramlKillSelectionWrite = Writes[state.KillSelection, KillSelection] {
    case state.KillSelection.YoungestFirst => KillSelection.Youngestfirst
    case state.KillSelection.OldestFirst => KillSelection.Oldestfirst
  }
}

object KillSelectionConversion extends KillSelectionConversion
