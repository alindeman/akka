/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.oldrouting

import language.postfixOps

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.PoisonPill
import akka.actor.Address
import scala.concurrent.Await
import akka.pattern.ask
import akka.remote.testkit.{ STMultiNodeSpec, MultiNodeConfig, MultiNodeSpec }
import akka.routing.Broadcast
import akka.routing.RandomRouter
import akka.routing.RoutedActorRef
import akka.testkit._
import scala.concurrent.duration._

object RandomRoutedRemoteActorMultiJvmSpec extends MultiNodeConfig {

  class SomeActor extends Actor {
    def receive = {
      case "hit" ⇒ sender() ! self
    }
  }

  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false))

  deployOnAll("""
      /service-hello.router = "random"
      /service-hello.nr-of-instances = 3
      /service-hello.target.nodes = ["@first@", "@second@", "@third@"]
    """)
}

class RandomRoutedRemoteActorMultiJvmNode1 extends RandomRoutedRemoteActorSpec
class RandomRoutedRemoteActorMultiJvmNode2 extends RandomRoutedRemoteActorSpec
class RandomRoutedRemoteActorMultiJvmNode3 extends RandomRoutedRemoteActorSpec
class RandomRoutedRemoteActorMultiJvmNode4 extends RandomRoutedRemoteActorSpec

class RandomRoutedRemoteActorSpec extends MultiNodeSpec(RandomRoutedRemoteActorMultiJvmSpec)
  with STMultiNodeSpec with ImplicitSender with DefaultTimeout {
  import RandomRoutedRemoteActorMultiJvmSpec._

  def initialParticipants = 4

  "A new remote actor configured with a Random router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" taggedAs LongRunningTest in {

      runOn(first, second, third) {
        enterBarrier("start", "broadcast-end", "end", "done")
      }

      runOn(fourth) {
        enterBarrier("start")
        val actor = system.actorOf(Props[SomeActor].withRouter(RandomRouter()), "service-hello")
        actor.isInstanceOf[RoutedActorRef] should be(true)

        val connectionCount = 3
        val iterationCount = 100

        for (i ← 0 until iterationCount; k ← 0 until connectionCount) {
          actor ! "hit"
        }

        val replies: Map[Address, Int] = (receiveWhile(5 seconds, messages = connectionCount * iterationCount) {
          case ref: ActorRef ⇒ ref.path.address
        }).foldLeft(Map(node(first).address -> 0, node(second).address -> 0, node(third).address -> 0)) {
          case (replyMap, address) ⇒ replyMap + (address -> (replyMap(address) + 1))
        }

        enterBarrier("broadcast-end")
        actor ! Broadcast(PoisonPill)

        enterBarrier("end")
        // since it's random we can't be too strict in the assert
        replies.values count (_ > 0) should be > (connectionCount - 2)
        replies.get(node(fourth).address) should be(None)

        // shut down the actor before we let the other node(s) shut down so we don't try to send
        // "Terminate" to a shut down node
        system.stop(actor)
        enterBarrier("done")
      }
    }
  }
}
