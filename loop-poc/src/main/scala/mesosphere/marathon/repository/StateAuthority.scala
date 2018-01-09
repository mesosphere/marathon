package mesosphere.marathon.repository

import java.util.UUID
import mesosphere.marathon.state._
import monocle.Lens
import monocle.macros.GenLens
import monocle.macros.syntax.lens._

case class Frame(
  rootGroup: RootGroup,
  version: Long)

sealed trait StateCommand
case class PutApp(
  runSpec: RunSpec) extends StateCommand

case class Rejection(reason: String)

sealed trait StateTransition

case class RunSpecUpdated(ref: RunSpecRef, runSpec: Option[RunSpec]) extends StateTransition

case class Result(
  stateTransitions: Seq[StateTransition],
)

object StateAuthority {
  // probably extract and share with Marathon scheduler
  def applyTransitions(frame: Frame, effects: Seq[StateTransition]): Frame = {
    effects.foldLeft(frame) {
      case (frame, update: RunSpecUpdated) =>
        update.runSpec match {
          case Some(runSpec) =>
            frame.lens(_.rootGroup).modify(_.withApp(runSpec))
          case None =>
            frame.lens(_.rootGroup).modify(_.withoutApp(update.ref))
        }
    }
  }

  def update(frame: Frame, command: StateCommand): Either[Rejection, Result] = {
    command match {
      case addApp: PutApp =>
        // we'd apply a validation here
        Right(
          Result(
            Seq(
              RunSpecUpdated(ref = addApp.runSpec.ref, runSpec = Some(addApp.runSpec)))))
    }
  }
}
