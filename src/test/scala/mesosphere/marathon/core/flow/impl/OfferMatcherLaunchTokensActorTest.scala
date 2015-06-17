package mesosphere.marathon.core.flow.impl

import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.flow.LaunchTokenConfig
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.bus.{
  TaskStatusUpdateTestHelper,
  MarathonTaskStatusTestHelper,
  TaskStatusObservables
}
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import org.mockito.Mockito
import rx.lang.scala.{ Observable, Subject }
import rx.lang.scala.subjects.PublishSubject

class OfferMatcherLaunchTokensActorTest extends MarathonSpec {
  test("initially setup tokens") {
    Mockito.verify(taskStatusObservables).forAll
    Mockito.verify(offerMatcherManager).setLaunchTokens(conf.launchTokens())
  }

  test("refill on running tasks without health info") {
    // startup
    Mockito.verify(taskStatusObservables).forAll
    Mockito.verify(offerMatcherManager).setLaunchTokens(conf.launchTokens())

    allObservable.onNext(TaskStatusUpdateTestHelper.running.wrapped)

    Mockito.verify(offerMatcherManager).addLaunchTokens(1)
  }

  test("refill on running healthy task") {
    // startup
    Mockito.verify(taskStatusObservables).forAll
    Mockito.verify(offerMatcherManager).setLaunchTokens(conf.launchTokens())

    allObservable.onNext(TaskStatusUpdateTestHelper.runningHealthy.wrapped)

    Mockito.verify(offerMatcherManager).addLaunchTokens(1)
  }

  test("DO NOT refill on running UNhealthy task") {
    // startup
    Mockito.verify(taskStatusObservables).forAll
    Mockito.verify(offerMatcherManager).setLaunchTokens(conf.launchTokens())

    allObservable.onNext(TaskStatusUpdateTestHelper.runningUnhealthy.wrapped)
  }

  private[this] implicit var actorSystem: ActorSystem = _
  private[this] var allObservable: Subject[TaskStatusUpdate] = _
  private[this] var conf: LaunchTokenConfig = _
  private[this] var taskStatusObservables: TaskStatusObservables = _
  private[this] var offerMatcherManager: OfferMatcherManager = _
  private[this] var actorRef: TestActorRef[OfferMatcherLaunchTokensActor] = _

  before {
    actorSystem = ActorSystem()
    conf = new LaunchTokenConfig {}
    conf.afterInit()
    allObservable = PublishSubject[TaskStatusObservables.TaskStatusUpdate]()
    taskStatusObservables = mock[TaskStatusObservables]
    Mockito.when(taskStatusObservables.forAll).thenReturn(allObservable)
    offerMatcherManager = mock[OfferMatcherManager]

    actorRef = TestActorRef[OfferMatcherLaunchTokensActor](
      OfferMatcherLaunchTokensActor.props(conf, taskStatusObservables, offerMatcherManager)
    )
  }

  after {
    Mockito.verifyNoMoreInteractions(taskStatusObservables)
    Mockito.verifyNoMoreInteractions(offerMatcherManager)

    actorSystem.shutdown()
    actorSystem.awaitTermination()
  }
}
