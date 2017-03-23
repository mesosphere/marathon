package mesosphere.marathon
package integration.setup

import java.util.Date
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.integration.setup.ServiceMock._
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.server.{ Request, Server }
import play.api.libs.json._

import scala.util.control.NonFatal
import scala.util.parsing.combinator.RegexParsers

/**
  * ServiceMock that speaks the service upgrade protocol v1 + admin handlers.
  *
  * GET  /v1/plan -> returns the plan
  * POST  /v1/plan/continue -> continues the plan with the command flag (decision point)
  * POST  /v1/plan/interrupt -> This request changes the “has_decision_point” property of the next block to true.
  * POST  /v1/plan/restart -> This request changes the “status” property of the indicated block to Pending.
  * POST  /v1/plan/force_complete -> This request changes the “status” property of the indicated block to Complete.
  *
  * POST /admin/rollback -> rollback the plan
  * POST /admin/finalize -> finalize the plan
  * POST /admin/next -> proceed with next step (no decision point)
  * POST /admin/error -> add an error to the plan
  *
  * @param plan the plan to execute on
  */
class ServiceMock(plan: Plan) extends AbstractHandler with StrictLogging {

  def start(port: Int): Unit = {
    try {
      val server = new Server(port)
      server.setHandler(this)
      server.start()
      logger.info(s"ServiceMock: has taken the stage at port $port.")
      server.join()
      logger.info("ServiceMock: says goodbye")
    } catch {
      // exit process, if an exception is encountered
      case ex: Throwable =>
        logger.error("ServiceMock: failed. Exit.", ex)
        sys.exit(1)
    }
  }

  override def handle(
    target: String,
    baseRequest: Request,
    request: HttpServletRequest,
    response: HttpServletResponse): Unit = synchronized {
    def status[T](t: T, code: Int)(implicit writes: Writes[T]): Unit = {
      response.getWriter.write(Json.prettyPrint(Json.toJson(t)))
      response.setHeader("Content-Type", "application/json")
      response.setStatus(code)
    }
    def ok[T](t: T)(implicit writes: Writes[T]): Unit = status(t, 200)
    def error[T](t: T)(implicit writes: Writes[T]): Unit = status(t, 503)

    def handleRequest(): Unit = (request.getMethod, request.getPathInfo) match {
      case ("GET", "/ping") => if (plan.errors.isEmpty) ok("pong") else error("error")
      case ("GET", "/v1/plan") =>
        if (plan.isDone) ok(plan) else error(plan)
      case ("POST", "/v1/plan/continue") =>
        plan.next(withContinue = true)
        ok(Json.obj("result" -> "Received cmd: continue"))
      case ("POST", "/v1/plan/interrupt") =>
        plan.allBlocks.dropWhile(b => b.isDone | b.isInProgress).headOption.foreach(_.decisionPoint = true)
        ok(Json.obj("result" -> "Received cmd: interrupt"))
      case ("POST", "/v1/plan/restart") =>
        val pos = Json.parse(request.getInputStream).as[BlockPosition]
        if (plan.validPosition(pos)) {
          plan.allBlocks.dropWhile(_.name != pos.block_id).foreach(_.rollback())
          ok(Json.obj("result" -> "Rescheduled Tasks: []"))
        } else error(Json.obj("Invalid position" -> pos))
      case ("POST", "/v1/plan/force_complete") =>
        val pos = Json.parse(request.getInputStream).as[BlockPosition]
        if (plan.validPosition(pos)) {
          plan.allBlocks.takeWhile(_.name != pos.block_id).foreach(_.doFinalize())
          ok(Json.obj("result" -> "Rescheduled Tasks: []"))
        } else error(Json.obj("Invalid position" -> pos))
      case ("POST", "/admin/rollback") =>
        plan.rollback()
        ok(Json.obj("Result" -> plan.status))
      case ("POST", "/admin/finalize") =>
        plan.doFinalize()
        ok(Json.obj("Result" -> plan.status))
      case ("POST", "/admin/next") =>
        plan.next(withContinue = false)
        ok(Json.obj("Result" -> plan.status))
      case ("POST", "/admin/error") =>
        plan.errors ::= s"Got an error at ${new Date}"
        ok(plan.errors)
      case ("DELETE", "/admin/error") =>
        plan.errors = List.empty
        ok(plan.errors)
    }

    try {
      baseRequest.setHandled(true)
      handleRequest()
    } catch {
      case js: JsResultException => error(Json.obj("error" -> "Invalid Json", "details" -> js.errors.toString()))
      case NonFatal(ex) => error(Json.obj("error" -> ex.getMessage))
    }
  }
}

object ServiceMock {

  case class Plan(phases: List[Phase]) {
    var errors: List[String] = List.empty
    def isDone = phases.forall(_.isDone)
    def status: Status = currentPhase.fold[Status](Complete)(_.status)
    def currentPhase: Option[Phase] = phases.find(_.currentBlock.isDefined)
    def allBlocks: List[Block] = phases.flatMap(_.blocks)
    def validPosition(pos: BlockPosition): Boolean = allBlocks.exists(_.name == pos.block_id)

    /**
      * Go to next state.
      * @param withContinue can continue on a decision point
      * @return true if plan is finished, otherwise false
      */
    def next(withContinue: Boolean): Boolean = {
      currentPhase.fold(true) { phase =>
        val done = phase.next(withContinue)
        if (done) currentPhase.fold(true)(_.next(withContinue)) else done
      }
    }
    def doFinalize(): Unit = {
      phases.foreach(_.blocks.foreach(_.doFinalize()))
    }
    def rollback(): Unit = {
      phases.foreach(_.rollback())
      errors = List.empty
    }
  }

  case class Phase(name: String, blocks: List[Block]) {
    def isDone = blocks.forall(_.isDone)
    def status: Status = currentBlock.fold[Status](Complete)(_.status)
    def currentBlock: Option[Block] = blocks.find(b => b.isInProgress || b.notStarted)
    def next(withContinue: Boolean): Boolean = {
      currentBlock.fold(true) { block =>
        val done = block.next(withContinue)
        if (done) block.next(withContinue) else done
      }
    }
    def rollback(): Unit = blocks.foreach(_.rollback())
  }

  case class Block(name: String, var decisionPoint: Boolean) {
    var status: Status = Pending
    def isDone = status.isDone
    def isInProgress = status.isInProgress
    def notStarted = !isInProgress && !isDone

    def next(decideYes: Boolean): Boolean = {
      status = status match {
        case Pending if decisionPoint => Waiting
        case Pending => InProgress
        case Waiting if decideYes => Complete
        case InProgress => Complete
        case current => current
      }
      isDone
    }

    def rollback(): Unit = {
      status = Pending
    }

    def doFinalize(): Unit = {
      status = Complete
    }
  }

  sealed trait Status {
    def isDone: Boolean
    def isInProgress: Boolean
    override def toString: String = getClass.getSimpleName.replaceAllLiterally("$", "")
  }
  case object Complete extends Status {
    override def isDone: Boolean = true
    override def isInProgress: Boolean = false
  }
  case object Pending extends Status {
    override def isDone: Boolean = false
    override def isInProgress: Boolean = false
  }
  case object Waiting extends Status {
    override def isDone: Boolean = false
    override def isInProgress: Boolean = false
  }
  case object InProgress extends Status {
    override def isDone: Boolean = false
    override def isInProgress: Boolean = true
  }

  case class BlockPosition(phase_id: String, block_id: String)

  implicit val blockPositionFormat: Format[BlockPosition] = Json.format[BlockPosition]

  implicit val statusWrites: Writes[Status] = Writes { status => JsString(status.toString) }
  implicit val blockWrites: Writes[Block] = Writes { block =>
    Json.obj(
      "has_decision_point" -> block.decisionPoint,
      "id" -> block.name,
      "message" -> block.name,
      "name" -> block.name,
      "status" -> block.status
    )
  }

  implicit val phaseWrites: Writes[Phase] = Writes { phase =>
    Json.obj(
      "blocks" -> phase.blocks,
      "id" -> phase.name,
      "name" -> phase.name,
      "status" -> phase.status
    )
  }

  implicit val planWrites: Writes[Plan] = Writes { plan =>
    Json.obj(
      "errors" -> plan.errors,
      "phases" -> plan.phases,
      "status" -> plan.status
    )
  }

  /**
    * Parse a plan from a string.
    * If a block needs a decision add an ! at the end
    * Format: phase(block,block,block),phase(decision!,decision!)
    */
  class PlanParser extends RegexParsers {
    def ident: Parser[String] = """([-A-Za-z0-9_.])+""".r
    def block: Parser[Block] = ident ~ opt("!") ^^ {
      case name ~ decisionPoint => Block(name, decisionPoint.isDefined)
    }
    def blocks: Parser[List[Block]] = "(" ~> repsep(block, ",") <~ ")"
    def phase: Parser[Phase] = ident ~ blocks ^^ {
      case name ~ blocks => Phase(name, blocks)
    }
    def plan: Parser[Plan] = repsep(phase, ",") ^^ Plan

    def parsePlan(in: String): Plan = {
      parseAll(plan, in) match {
        case Success(plan, _) => plan
        case NoSuccess(message, r) => throw new RuntimeException(message + r.pos)
      }
    }
  }

  /**
    * Start a ServiceMock that listens on $PORT0 with plan defined by argv[0]
    * Usage: test:runMain mesosphere.marathon.integration.setup.ServiceMock phase(block1!,block2!,block3!)
    */
  def main(args: Array[String]): Unit = {
    val port = sys.env.getOrElse("PORT0", "8080").toInt
    val plan = new PlanParser().parsePlan(args(0))
    plan.next(withContinue = false) //start plan
    new ServiceMock(plan).start(port)
  }
}
