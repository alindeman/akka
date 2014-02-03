/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.actor._
import akka.pattern.ask
import akka.remote.transport.AssociationRegistry
import akka.testkit._
import akka.util.ByteString
import com.typesafe.config._
import java.io.NotSerializableException
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom

object RemotingSpec {

  case class ActorForReq(s: String)
  case class ActorSelReq(s: String)

  class Echo1 extends Actor {
    var target: ActorRef = context.system.deadLetters

    def receive = {
      case (p: Props, n: String) ⇒ sender() ! context.actorOf(Props[Echo1], n)
      case ex: Exception         ⇒ throw ex
      case ActorForReq(s)        ⇒ sender() ! context.actorFor(s)
      case ActorSelReq(s)        ⇒ sender() ! context.actorSelection(s)
      case x                     ⇒ target = sender(); sender() ! x
    }

    override def preStart() {}
    override def preRestart(cause: Throwable, msg: Option[Any]) {
      target ! "preRestart"
    }
    override def postRestart(cause: Throwable) {}
    override def postStop() {
      target ! "postStop"
    }
  }

  class Echo2 extends Actor {
    def receive = {
      case "ping"                ⇒ sender() ! (("pong", sender()))
      case a: ActorRef           ⇒ a ! (("ping", sender()))
      case ("ping", a: ActorRef) ⇒ sender() ! (("pong", a))
      case ("pong", a: ActorRef) ⇒ a ! (("pong", sender().path.toSerializationFormat))
    }
  }

  class Proxy(val one: ActorRef, val another: ActorRef) extends Actor {
    def receive = {
      case s if sender().path == one.path     ⇒ another ! s
      case s if sender().path == another.path ⇒ one ! s
    }
  }

  val cfg: Config = ConfigFactory parseString (s"""
    common-ssl-settings {
      key-store = "${getClass.getClassLoader.getResource("keystore").getPath}"
      trust-store = "${getClass.getClassLoader.getResource("truststore").getPath}"
      key-store-password = "changeme"
      key-password = "changeme"
      trust-store-password = "changeme"
      protocol = "TLSv1"
      random-number-generator = "AES128CounterSecureRNG"
      enabled-algorithms = [TLS_RSA_WITH_AES_128_CBC_SHA]
    }

    common-netty-settings {
      port = 0
      hostname = "localhost"
    }

    akka {
      actor.provider = "akka.remote.RemoteActorRefProvider"

      remote {
        transport = "akka.remote.Remoting"

        retry-gate-closed-for = 1 s
        log-remote-lifecycle-events = on

        enabled-transports = [
          "akka.remote.test",
          "akka.remote.netty.tcp",
          "akka.remote.netty.udp",
          "akka.remote.netty.ssl"
        ]

        netty.tcp = $${common-netty-settings}
        netty.udp = $${common-netty-settings}
        netty.ssl = $${common-netty-settings}
        netty.ssl.security = $${common-ssl-settings}

        test {
          transport-class = "akka.remote.transport.TestTransport"
          applied-adapters = []
          registry-key = aX33k0jWKg
          local-address = "test://RemotingSpec@localhost:12345"
          maximum-payload-bytes = 32000 bytes
          scheme-identifier = test
        }
      }

      actor.deployment {
        /blub.remote = "akka.test://remote-sys@localhost:12346"
        /looker1/child.remote = "akka.test://remote-sys@localhost:12346"
        /looker1/child/grandchild.remote = "akka.test://RemotingSpec@localhost:12345"
        /looker2/child.remote = "akka.test://remote-sys@localhost:12346"
        /looker2/child/grandchild.remote = "akka.test://RemotingSpec@localhost:12345"
      }
    }
  """)

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemotingSpec extends AkkaSpec(RemotingSpec.cfg) with ImplicitSender with DefaultTimeout {

  import RemotingSpec._

  val conf = ConfigFactory.parseString(
    """
      akka.remote.test {
        local-address = "test://remote-sys@localhost:12346"
        maximum-payload-bytes = 48000 bytes
      }
    """).withFallback(system.settings.config).resolve()
  val remoteSystem = ActorSystem("remote-sys", conf)

  for (
    (name, proto) ← Seq(
      "/gonk" -> "tcp",
      "/zagzag" -> "udp",
      "/roghtaar" -> "ssl.tcp")
  ) deploy(system, Deploy(name, scope = RemoteScope(addr(remoteSystem, proto))))

  def addr(sys: ActorSystem, proto: String) =
    sys.asInstanceOf[ExtendedActorSystem].provider.getExternalAddressFor(Address(s"akka.$proto", "", "", 0)).get
  def port(sys: ActorSystem, proto: String) = addr(sys, proto).port.get
  def deploy(sys: ActorSystem, d: Deploy) {
    sys.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].deployer.deploy(d)
  }

  val remote = remoteSystem.actorOf(Props[Echo2], "echo")

  val here = system.actorFor("akka.test://remote-sys@localhost:12346/user/echo")

  private def verifySend(msg: Any)(afterSend: ⇒ Unit) {
    val bigBounceId = s"bigBounce-${ThreadLocalRandom.current.nextInt()}"
    val bigBounceOther = remoteSystem.actorOf(Props(new Actor {
      def receive = {
        case x: Int ⇒ sender() ! byteStringOfSize(x)
        case x      ⇒ sender() ! x
      }
    }).withDeploy(Deploy.local), bigBounceId)
    val bigBounceHere = system.actorFor(s"akka.test://remote-sys@localhost:12346/user/$bigBounceId")

    val eventForwarder = system.actorOf(Props(new Actor {
      def receive = {
        case x ⇒ testActor ! x
      }
    }).withDeploy(Deploy.local))
    system.eventStream.subscribe(eventForwarder, classOf[AssociationErrorEvent])
    system.eventStream.subscribe(eventForwarder, classOf[DisassociatedEvent])
    try {
      bigBounceHere ! msg
      afterSend
      expectNoMsg(500.millis.dilated)
    } finally {
      system.eventStream.unsubscribe(eventForwarder, classOf[AssociationErrorEvent])
      system.eventStream.unsubscribe(eventForwarder, classOf[DisassociatedEvent])
      eventForwarder ! PoisonPill
      bigBounceOther ! PoisonPill
    }
  }

  override def atStartup() = {
    system.eventStream.publish(TestEvent.Mute(
      EventFilter.error(start = "AssociationError"),
      EventFilter.warning(pattern = "received dead letter.*")))
    remoteSystem.eventStream.publish(TestEvent.Mute(
      EventFilter[EndpointException](),
      EventFilter.error(start = "AssociationError"),
      EventFilter.warning(pattern = "received dead letter.*(InboundPayload|Disassociate|HandleListener)")))
  }

  private def byteStringOfSize(size: Int) = ByteString.fromArray(Array.fill(size)(42: Byte))

  val maxPayloadBytes = system.settings.config.getBytes("akka.remote.test.maximum-payload-bytes").toInt

  override def afterTermination() {
    shutdown(remoteSystem)
    AssociationRegistry.clear()
  }

  "Remoting" must {

    "support remote look-ups" in {
      here ! "ping"
      expectMsg(("pong", testActor))
    }

    "send warning message for wrong address" in {
      filterEvents(EventFilter.warning(pattern = "Address is now gated for ", occurrences = 1)) {
        system.actorFor("akka.test://nonexistingsystem@localhost:12346/user/echo") ! "ping"
      }
    }

    "support ask" in {
      Await.result(here ? "ping", timeout.duration) match {
        case ("pong", s: akka.pattern.PromiseActorRef) ⇒ // good
        case m                                         ⇒ fail(m + " was not (pong, AskActorRef)")
      }
    }

    "send dead letters on remote if actor does not exist" in {
      EventFilter.warning(pattern = "dead.*buh", occurrences = 1).intercept {
        system.actorFor("akka.test://remote-sys@localhost:12346/does/not/exist") ! "buh"
      }(remoteSystem)
    }

    "not be exhausted by sending to broken connections" in {
      val tcpOnlyConfig = ConfigFactory.parseString("""akka.remote.enabled-transports = ["akka.remote.netty.tcp"]""").
        withFallback(remoteSystem.settings.config)
      val moreSystems = Vector.fill(5)(ActorSystem(remoteSystem.name, tcpOnlyConfig))
      moreSystems foreach { sys ⇒
        sys.eventStream.publish(TestEvent.Mute(
          EventFilter[EndpointDisassociatedException](),
          EventFilter.warning(pattern = "received dead letter.*")))
        sys.actorOf(Props[Echo2], name = "echo")
      }
      val moreRefs = moreSystems map (sys ⇒ system.actorSelection(RootActorPath(addr(sys, "tcp")) / "user" / "echo"))
      val aliveEcho = system.actorSelection(RootActorPath(addr(remoteSystem, "tcp")) / "user" / "echo")
      val n = 100

      // first everything is up and running
      1 to n foreach { x ⇒
        aliveEcho ! "ping"
        moreRefs(x % moreSystems.size) ! "ping"
      }

      within(5.seconds) {
        receiveN(n * 2) foreach { reply ⇒ reply should be(("pong", testActor)) }
      }

      // then we shutdown all but one system to simulate broken connections
      moreSystems foreach { sys ⇒
        shutdown(sys)
      }

      1 to n foreach { x ⇒
        aliveEcho ! "ping"
        moreRefs(x % moreSystems.size) ! "ping"
      }

      // ping messages to aliveEcho should go through even though we use many different broken connections
      within(5.seconds) {
        receiveN(n) foreach { reply ⇒ reply should be(("pong", testActor)) }
      }
    }

    "create and supervise children on remote node" in {
      val r = system.actorOf(Props[Echo1], "blub")
      r.path.toString should be("akka.test://remote-sys@localhost:12346/remote/akka.test/RemotingSpec@localhost:12345/user/blub")
      r ! 42
      expectMsg(42)
      EventFilter[Exception]("crash", occurrences = 1).intercept {
        r ! new Exception("crash")
      }
      expectMsg("preRestart")
      r ! 42
      expectMsg(42)
      system.stop(r)
      expectMsg("postStop")
    }

    "not send to remote re-created actor with same name" in {
      val echo = remoteSystem.actorOf(Props[Echo1], "otherEcho1")
      echo ! 71
      expectMsg(71)
      echo ! PoisonPill
      expectMsg("postStop")
      echo ! 72
      expectNoMsg(1.second)

      val echo2 = remoteSystem.actorOf(Props[Echo1], "otherEcho1")
      echo2 ! 73
      expectMsg(73)
      // msg to old ActorRef (different uid) should not get through
      echo2.path.uid should not be (echo.path.uid)
      echo ! 74
      expectNoMsg(1.second)

      remoteSystem.actorFor("/user/otherEcho1") ! 75
      expectMsg(75)

      system.actorFor("akka.test://remote-sys@localhost:12346/user/otherEcho1") ! 76
      expectMsg(76)

      remoteSystem.actorSelection("/user/otherEcho1") ! 77
      expectMsg(77)

      system.actorSelection("akka.test://remote-sys@localhost:12346/user/otherEcho1") ! 78
      expectMsg(78)
    }

    "look-up actors across node boundaries" in {
      val l = system.actorOf(Props(new Actor {
        def receive = {
          case (p: Props, n: String) ⇒ sender() ! context.actorOf(p, n)
          case ActorForReq(s)        ⇒ sender() ! context.actorFor(s)
        }
      }), "looker1")
      // child is configured to be deployed on remote-sys (remoteSystem)
      l ! ((Props[Echo1], "child"))
      val child = expectMsgType[ActorRef]
      // grandchild is configured to be deployed on RemotingSpec (system)
      child ! ((Props[Echo1], "grandchild"))
      val grandchild = expectMsgType[ActorRef]
      grandchild.asInstanceOf[ActorRefScope].isLocal should be(true)
      grandchild ! 43
      expectMsg(43)
      val myref = system.actorFor(system / "looker1" / "child" / "grandchild")
      myref.isInstanceOf[RemoteActorRef] should be(true)
      myref ! 44
      expectMsg(44)
      lastSender should be(grandchild)
      lastSender should be theSameInstanceAs grandchild
      child.asInstanceOf[RemoteActorRef].getParent should be(l)
      system.actorFor("/user/looker1/child") should be theSameInstanceAs child
      Await.result(l ? ActorForReq("child/.."), timeout.duration).asInstanceOf[AnyRef] should be theSameInstanceAs l
      Await.result(system.actorFor(system / "looker1" / "child") ? ActorForReq(".."), timeout.duration).asInstanceOf[AnyRef] should be theSameInstanceAs l

      watch(child)
      child ! PoisonPill
      expectMsg("postStop")
      expectTerminated(child)
      l ! ((Props[Echo1], "child"))
      val child2 = expectMsgType[ActorRef]
      child2 ! 45
      expectMsg(45)
      // msg to old ActorRef (different uid) should not get through
      child2.path.uid should not be (child.path.uid)
      child ! 46
      expectNoMsg(1.second)
      system.actorFor(system / "looker1" / "child") ! 47
      expectMsg(47)
    }

    "select actors across node boundaries" in {
      val l = system.actorOf(Props(new Actor {
        def receive = {
          case (p: Props, n: String) ⇒ sender() ! context.actorOf(p, n)
          case ActorSelReq(s)        ⇒ sender() ! context.actorSelection(s)
        }
      }), "looker2")
      // child is configured to be deployed on remoteSystem
      l ! ((Props[Echo1], "child"))
      val child = expectMsgType[ActorRef]
      // grandchild is configured to be deployed on RemotingSpec (system)
      child ! ((Props[Echo1], "grandchild"))
      val grandchild = expectMsgType[ActorRef]
      grandchild.asInstanceOf[ActorRefScope].isLocal should be(true)
      grandchild ! 53
      expectMsg(53)
      val mysel = system.actorSelection(system / "looker2" / "child" / "grandchild")
      mysel ! 54
      expectMsg(54)
      lastSender should be(grandchild)
      lastSender should be theSameInstanceAs grandchild
      mysel ! Identify(mysel)
      val grandchild2 = expectMsgType[ActorIdentity].ref
      grandchild2 should be(Some(grandchild))
      system.actorSelection("/user/looker2/child") ! Identify(None)
      expectMsgType[ActorIdentity].ref should be(Some(child))
      l ! ActorSelReq("child/..")
      expectMsgType[ActorSelection] ! Identify(None)
      expectMsgType[ActorIdentity].ref.get should be theSameInstanceAs l
      system.actorSelection(system / "looker2" / "child") ! ActorSelReq("..")
      expectMsgType[ActorSelection] ! Identify(None)
      expectMsgType[ActorIdentity].ref.get should be theSameInstanceAs l

      grandchild ! ((Props[Echo1], "grandgrandchild"))
      val grandgrandchild = expectMsgType[ActorRef]

      system.actorSelection("/user/looker2/child") ! Identify("idReq1")
      expectMsg(ActorIdentity("idReq1", Some(child)))
      system.actorSelection(child.path) ! Identify("idReq2")
      expectMsg(ActorIdentity("idReq2", Some(child)))
      system.actorSelection("/user/looker2/*") ! Identify("idReq3")
      expectMsg(ActorIdentity("idReq3", Some(child)))

      system.actorSelection("/user/looker2/child/grandchild") ! Identify("idReq4")
      expectMsg(ActorIdentity("idReq4", Some(grandchild)))
      system.actorSelection(child.path / "grandchild") ! Identify("idReq5")
      expectMsg(ActorIdentity("idReq5", Some(grandchild)))
      system.actorSelection("/user/looker2/*/grandchild") ! Identify("idReq6")
      expectMsg(ActorIdentity("idReq6", Some(grandchild)))
      system.actorSelection("/user/looker2/child/*") ! Identify("idReq7")
      expectMsg(ActorIdentity("idReq7", Some(grandchild)))
      system.actorSelection(child.path / "*") ! Identify("idReq8")
      expectMsg(ActorIdentity("idReq8", Some(grandchild)))

      system.actorSelection("/user/looker2/child/grandchild/grandgrandchild") ! Identify("idReq9")
      expectMsg(ActorIdentity("idReq9", Some(grandgrandchild)))
      system.actorSelection(child.path / "grandchild" / "grandgrandchild") ! Identify("idReq10")
      expectMsg(ActorIdentity("idReq10", Some(grandgrandchild)))
      system.actorSelection("/user/looker2/child/*/grandgrandchild") ! Identify("idReq11")
      expectMsg(ActorIdentity("idReq11", Some(grandgrandchild)))
      system.actorSelection("/user/looker2/child/*/*") ! Identify("idReq12")
      expectMsg(ActorIdentity("idReq12", Some(grandgrandchild)))
      system.actorSelection(child.path / "*" / "grandgrandchild") ! Identify("idReq13")
      expectMsg(ActorIdentity("idReq13", Some(grandgrandchild)))

      child ! Identify("idReq14")
      expectMsg(ActorIdentity("idReq14", Some(child)))
      watch(child)
      child ! PoisonPill
      expectMsg("postStop")
      expectMsgType[Terminated].actor should be(child)
      l ! ((Props[Echo1], "child"))
      val child2 = expectMsgType[ActorRef]
      child2 ! Identify("idReq15")
      expectMsg(ActorIdentity("idReq15", Some(child2)))
      system.actorSelection(child.path) ! Identify("idReq16")
      expectMsg(ActorIdentity("idReq16", Some(child2)))
      child ! Identify("idReq17")
      expectMsg(ActorIdentity("idReq17", None))

      child2 ! 55
      expectMsg(55)
      // msg to old ActorRef (different uid) should not get through
      child2.path.uid should not be (child.path.uid)
      child ! 56
      expectNoMsg(1.second)
      system.actorSelection(system / "looker2" / "child") ! 57
      expectMsg(57)
    }

    "not fail ask across node boundaries" in within(5.seconds) {
      import system.dispatcher
      val f = for (_ ← 1 to 1000) yield here ? "ping" mapTo manifest[(String, ActorRef)]
      Await.result(Future.sequence(f), remaining).map(_._1).toSet should be(Set("pong"))
    }

    "be able to use multiple transports and use the appropriate one (TCP)" in {
      val r = system.actorOf(Props[Echo1], "gonk")
      r.path.toString should be ===
        s"akka.tcp://remote-sys@localhost:${port(remoteSystem, "tcp")}/remote/akka.tcp/RemotingSpec@localhost:${port(system, "tcp")}/user/gonk"
      r ! 42
      expectMsg(42)
      EventFilter[Exception]("crash", occurrences = 1).intercept {
        r ! new Exception("crash")
      }
      expectMsg("preRestart")
      r ! 42
      expectMsg(42)
      system.stop(r)
      expectMsg("postStop")
    }

    "be able to use multiple transports and use the appropriate one (UDP)" in {
      val r = system.actorOf(Props[Echo1], "zagzag")
      r.path.toString should be ===
        s"akka.udp://remote-sys@localhost:${port(remoteSystem, "udp")}/remote/akka.udp/RemotingSpec@localhost:${port(system, "udp")}/user/zagzag"
      r ! 42
      expectMsg(10.seconds, 42)
      EventFilter[Exception]("crash", occurrences = 1).intercept {
        r ! new Exception("crash")
      }
      expectMsg("preRestart")
      r ! 42
      expectMsg(42)
      system.stop(r)
      expectMsg("postStop")
    }

    "be able to use multiple transports and use the appropriate one (SSL)" in {
      val r = system.actorOf(Props[Echo1], "roghtaar")
      r.path.toString should be ===
        s"akka.ssl.tcp://remote-sys@localhost:${port(remoteSystem, "ssl.tcp")}/remote/akka.ssl.tcp/RemotingSpec@localhost:${port(system, "ssl.tcp")}/user/roghtaar"
      r ! 42
      expectMsg(10.seconds, 42)
      EventFilter[Exception]("crash", occurrences = 1).intercept {
        r ! new Exception("crash")
      }
      expectMsg("preRestart")
      r ! 42
      expectMsg(42)
      system.stop(r)
      expectMsg("postStop")
    }

    "drop unserializable messages" in {
      object Unserializable
      EventFilter[NotSerializableException](pattern = ".*No configured serialization.*", occurrences = 1).intercept {
        verifySend(Unserializable) {
          expectNoMsg(1.second) // No AssocitionErrorEvent should be published
        }
      }
    }

    "allow messages up to payload size" in {
      val maxProtocolOverhead = 500 // Make sure we're still under size after the message is serialized, etc
      val big = byteStringOfSize(maxPayloadBytes - maxProtocolOverhead)
      verifySend(big) {
        expectMsg(1.second, big)
      }
    }

    "drop sent messages over payload size" in {
      val oversized = byteStringOfSize(maxPayloadBytes + 1)
      EventFilter[OversizedPayloadException](pattern = ".*Discarding oversized payload sent.*", occurrences = 1).intercept {
        verifySend(oversized) {
          expectNoMsg(1.second) // No AssocitionErrorEvent should be published
        }
      }
    }

    "drop received messages over payload size" in {
      // Receiver should reply with a message of size maxPayload + 1, which will be dropped and an error logged
      EventFilter[OversizedPayloadException](pattern = ".*Discarding oversized payload received.*", occurrences = 1).intercept {
        verifySend(maxPayloadBytes + 1) {
          expectNoMsg(1.second) // No AssocitionErrorEvent should be published
        }
      }
    }

    "be able to serialize a local actor ref from another actor system" in {
      val config = ConfigFactory.parseString("""
        # Additional internal serialization verification need so be off, otherwise it triggers two error messages
        # instead of one: one for the internal check, and one for the actual remote send -- tripping off this test
        akka.actor.serialize-messages = off
        akka.remote.enabled-transports = ["akka.remote.test", "akka.remote.netty.tcp"]
        akka.remote.test.local-address = "test://other-system@localhost:12347"
      """).withFallback(remoteSystem.settings.config)
      val otherSystem = ActorSystem("other-system", config)
      try {
        val otherGuy = otherSystem.actorOf(Props[Echo2], "other-guy")
        // check that we use the specified transport address instead of the default
        val otherGuyRemoteTcp = otherGuy.path.toSerializationFormatWithAddress(addr(otherSystem, "tcp"))
        val remoteEchoHereTcp = system.actorFor(s"akka.tcp://remote-sys@localhost:${port(remoteSystem, "tcp")}/user/echo")
        val proxyTcp = system.actorOf(Props(classOf[Proxy], remoteEchoHereTcp, testActor), "proxy-tcp")
        proxyTcp ! otherGuy
        expectMsg(3.seconds, ("pong", otherGuyRemoteTcp))
        // now check that we fall back to default when we haven't got a corresponding transport
        val otherGuyRemoteTest = otherGuy.path.toSerializationFormatWithAddress(addr(otherSystem, "test"))
        val remoteEchoHereSsl = system.actorFor(s"akka.ssl.tcp://remote-sys@localhost:${port(remoteSystem, "ssl.tcp")}/user/echo")
        val proxySsl = system.actorOf(Props(classOf[Proxy], remoteEchoHereSsl, testActor), "proxy-ssl")
        EventFilter[RemoteTransportException](start = "Error while resolving address", occurrences = 1).intercept {
          proxySsl ! otherGuy
          expectMsg(3.seconds, ("pong", otherGuyRemoteTest))
        }(otherSystem)
      } finally {
        shutdown(otherSystem)
      }
    }
  }
}
