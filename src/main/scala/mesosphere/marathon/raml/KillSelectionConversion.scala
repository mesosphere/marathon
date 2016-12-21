package mesosphere.marathon
package raml

/**
  * Conversion from [[mesosphere.marathon.state.KillSelection]] to [[mesosphere.marathon.raml.KillSelection]] and vice versa.
  */
trait KillSelectionConversion {

  implicit val ramlKillSelectionRead = Reads[KillSelection, state.KillSelection] {
    case KillSelection.OldestFirst => state.KillSelection.OldestFirst
    case KillSelection.YoungestFirst => state.KillSelection.YoungestFirst
  }

  implicit val ramlKillSelectionWrite = Writes[state.KillSelection, KillSelection] {
    case state.KillSelection.YoungestFirst => KillSelection.YoungestFirst
    case state.KillSelection.OldestFirst => KillSelection.OldestFirst
  }
}

object KillSelectionConversion extends KillSelectionConversion
