package mesosphere.mesos

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition
import org.apache.mesos.Protos.Offer
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.util.Try

object Int {
  def unapply(s: String): Option[Int] = Try(s.toInt).toOption
}

object Constraints {

  private[this] val log = LoggerFactory.getLogger(getClass.getName)
  val GroupByDefault = 0

  private def getIntValue(s: String, default: Int): Int = s match {
    case "inf"  => Integer.MAX_VALUE
    case Int(x) => x
    case _      => default
  }

  private final class ConstraintsChecker(tasks: Iterable[Task], offer: Offer, constraint: Constraint) {
    val field = constraint.getField
    val value = constraint.getValue
    lazy val attr = offer.getAttributesList.asScala.find(_.getName == field)

    def isMatch: Boolean =
      if (field == "hostname") {
        checkHostName
      }
      else if (attr.nonEmpty) {
        checkAttribute
      }
      else {
        // This will be reached in case we want to schedule for an attribute
        // that's not supplied.
        checkMissingAttribute
      }

    private def checkGroupBy(constraintValue: String, groupFunc: (Task) => Option[String]) = {
      // Minimum group count
      val minimum = List(GroupByDefault, getIntValue(value, GroupByDefault)).max
      // Group tasks by the constraint value, and calculate the task count of each group
      val groupedTasks = tasks.groupBy(groupFunc).mapValues(_.size)
      // Task count of the smallest group
      val minCount = groupedTasks.values.reduceOption(_ min _).getOrElse(0)

      // Return true if any of these are also true:
      // a) this offer matches the smallest grouping when there
      // are >= minimum groupings
      // b) the constraint value from the offer is not yet in the grouping
      groupedTasks.find(_._1.contains(constraintValue)) match {
        case Some(pair) => (groupedTasks.size >= minimum) && (pair._2 == minCount)
        case None       => true
      }
    }

    private def checkHostName =
      constraint.getOperator match {
        case Operator.LIKE     => offer.getHostname.matches(value)
        case Operator.UNLIKE   => !offer.getHostname.matches(value)
        // All running tasks must have a hostname that is different from the one in the offer
        case Operator.UNIQUE   => tasks.forall(_.agentInfo.host != offer.getHostname)
        case Operator.GROUP_BY => checkGroupBy(offer.getHostname, (task: Task) => Some(task.agentInfo.host))
        case Operator.CLUSTER =>
          // Hostname must match or be empty
          (value.isEmpty || value == offer.getHostname) &&
            // All running tasks must have the same hostname as the one in the offer
            tasks.forall(_.agentInfo.host == offer.getHostname)
        case _ => false
      }

    private def checkAttribute = {
      def matches: Iterable[Task] = matchTaskAttributes(tasks, field, attr.get.getText.getValue)
      constraint.getOperator match {
        case Operator.UNIQUE => matches.isEmpty
        case Operator.CLUSTER =>
          // If no value is set, accept the first one. Otherwise check for it.
          (value.isEmpty || attr.get.getText.getValue == value) &&
            // All running tasks should have the matching attribute
            matches.size == tasks.size
        case Operator.GROUP_BY =>
          val groupFunc = (task: Task) =>
            task.agentInfo.attributes
              .find(_.getName == field)
              .map(_.getText.getValue)
          checkGroupBy(attr.get.getText.getValue, groupFunc)
        case Operator.LIKE =>
          if (value.nonEmpty) {
            attr.get.getText.getValue.matches(value)
          }
          else {
            log.warn("Error, value is required for LIKE operation")
            false
          }
        case Operator.UNLIKE =>
          if (value.nonEmpty) {
            !attr.get.getText.getValue.matches(value)
          }
          else {
            log.warn("Error, value is required for UNLIKE operation")
            false
          }
      }
    }

    private def checkMissingAttribute = constraint.getOperator == Operator.UNLIKE

    /**
      * Filters running tasks by matching their attributes to this field & value.
      */
    private def matchTaskAttributes(tasks: Iterable[Task], field: String, value: String) =
      tasks.filter {
        _.agentInfo.attributes
          .filter { y =>
            y.getName == field &&
              y.getText.getValue == value
          }.nonEmpty
      }
  }

  def meetsConstraint(tasks: Iterable[Task], offer: Offer, constraint: Constraint): Boolean =
    new ConstraintsChecker(tasks, offer, constraint).isMatch

  /**
    * Select tasks to kill while maintaining the constraints of the application definition.
    * Note: It is possible, that the result of this operation does not select as much tasks as needed.
    *
    * @param app the application definition of the tasks.
    * @param runningTasks the list of running tasks to filter
    * @param toKillCount the expected number of tasks to select for kill
    * @return the selected tasks to kill. The number of tasks will not exceed toKill but can be less.
    */
  //scalastyle:off return
  def selectTasksToKill(
    app: AppDefinition, runningTasks: Iterable[Task], toKillCount: Int): Iterable[Task] = {

    require(toKillCount <= runningTasks.size, "Can not kill more instances than running")

    //short circuit, if all tasks shall be killed
    if (runningTasks.size == toKillCount) return runningTasks

    //currently, only the GROUP_BY operator is able to select tasks to kill
    val distributions = app.constraints.filter(_.getOperator == Operator.GROUP_BY).map { constraint =>
      def groupFn(task: Task): Option[String] = constraint.getField match {
        case "hostname"    => Some(task.agentInfo.host)
        case field: String => task.agentInfo.attributes.find(_.getName == field).map(_.getText.getValue)
      }
      val taskGroups: Seq[Map[Task.Id, Task]] =
        runningTasks.groupBy(groupFn).values.map(Task.tasksById(_)).toSeq
      GroupByDistribution(constraint, taskGroups)
    }

    //short circuit, if there are no constraints to align with
    if (distributions.isEmpty) return Set.empty

    var toKillTasks = Map.empty[Task.Id, Task]
    var flag = true
    while (flag && toKillTasks.size != toKillCount) {
      val tried = distributions
        //sort all distributions in descending order based on distribution difference
        .toSeq.sortBy(_.distributionDifference(toKillTasks)).reverseIterator
        //select tasks to kill (without already selected ones)
        .flatMap(_.tasksToKillIterator(toKillTasks)) ++
        //fallback: if the distributions did not select a task, choose one of the not chosen ones
        runningTasks.iterator.filterNot(task => toKillTasks.contains(task.taskId))

      val matchingTask =
        tried.find(tryTask => distributions.forall(_.isMoreEvenWithout(toKillTasks + (tryTask.taskId -> tryTask))))

      matchingTask match {
        case Some(task) => toKillTasks += task.taskId -> task
        case None       => flag = false
      }
    }

    //log the selected tasks and why they were selected
    if (log.isInfoEnabled) {
      val taskDesc = toKillTasks.values.map { task =>
        val attrs = task.agentInfo.attributes.map(a => s"${a.getName}=${a.getText.getValue}").mkString(", ")
        s"${task.taskId} host:${task.agentInfo.host} attrs:$attrs"
      }.mkString("Selected Tasks to kill:\n", "\n", "\n")
      val distDesc = distributions.map { d =>
        val (before, after) = (d.distributionDifference(), d.distributionDifference(toKillTasks))
        s"${d.constraint.getField} changed from: $before to $after"
      }.mkString("Selected Constraint diff changed:\n", "\n", "\n")
      log.info(s"$taskDesc$distDesc")
    }

    toKillTasks.values
  }

  /**
    * Helper class for easier distribution computation.
    */
  private case class GroupByDistribution(constraint: Constraint, distribution: Seq[Map[Task.Id, Task]]) {
    def isMoreEvenWithout(selected: Map[Task.Id, Task]): Boolean = {
      val diffAfterKill = distributionDifference(selected)
      //diff after kill is 0=perfect, 1=tolerated or minimizes the difference
      diffAfterKill <= 1 || distributionDifference() > diffAfterKill
    }

    def tasksToKillIterator(without: Map[Task.Id, Task]): Iterator[Task] = {
      val updated = distribution.map(_ -- without.keys).groupBy(_.size)
      if (updated.size == 1) /* even distributed */ Iterator.empty else {
        updated.maxBy(_._1)._2.iterator.flatten.map { case (taskId, task) => task }
      }
    }

    def distributionDifference(without: Map[Task.Id, Task] = Map.empty): Int = {
      val updated = distribution.map(_ -- without.keys).groupBy(_.size).keySet
      updated.max - updated.min
    }
  }
}

