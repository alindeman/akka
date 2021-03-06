/**
 * Copyright (C) 2014-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal

import akka.actor.Actor
import akka.persistence.{ Persistence, PersistentEnvelope, PersistentRepr }

import scala.collection.immutable

private[akka] trait WriteJournalBase {
  this: Actor ⇒

  lazy val persistence = Persistence(context.system)
  private def eventAdapters = persistence.adaptersFor(self)

  protected def preparePersistentBatch(rb: immutable.Seq[PersistentEnvelope]): immutable.Seq[PersistentRepr] =
    rb.collect { // collect instead of flatMap to avoid Some allocations
      case p: PersistentRepr ⇒ adaptToJournal(p.update(sender = Actor.noSender)) // don't store the sender
    }

  /** INTERNAL API */
  private[akka] final def adaptFromJournal(repr: PersistentRepr): immutable.Seq[PersistentRepr] =
    eventAdapters.get(repr.payload.getClass).fromJournal(repr.payload, repr.manifest).events map { adaptedPayload ⇒
      repr.withPayload(adaptedPayload)
    }

  /** INTERNAL API */
  private[akka] final def adaptToJournal(repr: PersistentRepr): PersistentRepr = {
    val payload = repr.payload
    val adapter = eventAdapters.get(payload.getClass)
    val manifest = adapter.manifest(payload)

    repr
      .withPayload(adapter.toJournal(payload))
      .withManifest(manifest)
  }

}
