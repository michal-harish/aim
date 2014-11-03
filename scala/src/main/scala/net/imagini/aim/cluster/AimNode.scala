package net.imagini.aim.cluster

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConverters.mapAsScalaConcurrentMapConverter
import grizzled.slf4j.Logger
import net.imagini.aim.types.AimSchema
import java.util.concurrent.TimeoutException
import net.imagini.aim.partition.AimPartition
import net.imagini.aim.partition.StatScanner
import net.imagini.aim.tools.AbstractScanner
import net.imagini.aim.partition.QueryParser
import net.imagini.aim.client.AimConsole

object AimNode extends App {
  val log = Logger[AimNode.this.type]
  var port: Int = 4000
  var zkConnect: String = "localhost:2181"
  val argsIterator = args.iterator
  while (argsIterator.hasNext) {
    argsIterator.next match {
      case "--zookeeper" ⇒ zkConnect = argsIterator.next
      case "--port"      ⇒ port = argsIterator.next.toInt
      case arg: String   ⇒ println("Unknown argument " + arg)
    }
  }

  //SPAWNING CLUSTER
  val manager = new DriftManagerZk(zkConnect, 4)
  new AimNode(1, "localhost:4000", manager)
  new AimNode(2, "localhost:4001", manager)
  new AimNode(3, "localhost:4002", manager)
  new AimNode(4, "localhost:4003", manager)

  //CREATING TABLES
  manager.createTable("addthis", "pageviews", "at_id(STRING), url(STRING), timestamp(LONG)", true)
  manager.createTable("addthis", "syncs", "at_id(STRING), vdna_user_uid(UUID:BYTEARRAY[16]), timestamp(LONG)", true)
  manager.createTable("vdna", "events", "user_uid(UUID:BYTEARRAY[16]), timestamp(LONG), type(STRING), url(STRING)", true)
  manager.createTable("vdna", "syncs", "user_uid(UUID:BYTEARRAY[16]), timestamp(LONG), id_space(STRING), partner_user_id(STRING)", true)

  //ATTACH CONSOLE
  val console = new AimConsole("localhost", 4000)
  console.start

  //WAIT FOR DISTRIBUTED SHUTDOWN
  manager.synchronized(manager.wait)
  console.close

}

class AimNode(val id: Int, val address: String, val manager: DriftManager) {

  val log = Logger[AimNode.this.type]
  val nodes: ConcurrentMap[Int, URI] = new ConcurrentHashMap[Int, URI]()
  def peers = nodes.asScala.filter(n ⇒ n._1 != id)

  private var expectedNumNodes = -1
  private var suspended = new AtomicBoolean(true)
  private var keyspaceRefs = new ConcurrentHashMap[String, ConcurrentMap[String, AimPartition]]()
  def keyspaces: List[String] = keyspaceRefs.asScala.keys.toList
  def keyspace(k: String): Map[String, AimPartition] = keyspaceRefs.get(k).asScala.toMap
  def stats(keyspaceName: String): AbstractScanner = {
    new StatScanner(id, keyspace(keyspaceName).map { case (t, partition) ⇒ t -> partition }.toMap)
  }
  def query(keyspaceName: String, query: String) = new QueryParser(keyspace(keyspaceName)).parse(query)

  @volatile var isShutdown = false
  private var shutDownHook: Option[AtomicBoolean] = None
  var sessions = scala.collection.mutable.ListBuffer[AimNodeSession]()
  def session(s: AimNodeSession) = {
    sessions += s
    s.start
  }

  val acceptor = new AimNodeAcceptor(this, new URI("drift://" + address).getPort)
  log.info("Drift Node accepting connections on port " + acceptor.port)
  manager.registerNode(id, address)
  manager.watchData("/drift/nodes/" + id.toString, (data: Option[String]) ⇒ data match {
    case Some(address) ⇒ {} //TODO check if address is this address
    case None          ⇒ doShutdown
  })

  manager.watch("/drift/nodes", (children: Map[String, String]) ⇒ {
    val newNodes = children.map(p ⇒ p._1.toInt -> new URI(p._2)).toMap
    nodes.asScala.keys.filter(!newNodes.contains(_)).map(nodes.remove(_))
    newNodes.map(n ⇒ nodes.put(n._1, n._2))
    rebalance
  })
  manager.watchData("/drift/nodes", (num: Option[Int]) ⇒ num match {
    case Some(n) ⇒ { expectedNumNodes = n; rebalance }
    case None    ⇒ { expectedNumNodes = -1; rebalance }
  })
  manager.watch("/drift/keyspaces", (ks: Map[String, String]) ⇒ {
    keyspaceRefs.asScala.keys.filter(!ks.contains(_)).map(keyspaceRefs.remove(_))
    ks.keys.filter(!keyspaceRefs.containsKey(_)).map(k ⇒ {
      keyspaceRefs.put(k, new ConcurrentHashMap[String, AimPartition]())
      manager.watch("/drift/keyspaces/" + k, (tables: Map[String, String]) ⇒ {
        keyspaceRefs.get(k).asScala.keys.filter(!tables.contains(_)).map(keyspaceRefs.get(k).remove(_))
        tables.filter(t ⇒ !keyspaceRefs.get(k).containsKey(t._1)).map(t ⇒ {
          val schema = AimSchema.fromString(t._2)
          keyspaceRefs.get(k).put(t._1, new AimPartition(schema, 10485760 * 20))
          log.debug(id + ": " + k + "." + t._1 + " " + keyspaceRefs.get(k).get(t._1).toString)
        })
      })
    })
  })

  def rebalance {
    if (expectedNumNodes == nodes.size) {
      if (suspended.compareAndSet(true, false)) {
        log.info(id + ": resuming")
      }
    } else if (suspended.compareAndSet(false, true)) {
      log.info(id + ": suspending")
    }
  }

  private def doShutdown {
    isShutdown = true
    log.debug(id + ": Node at port " + acceptor.port + " shutting down.")
    acceptor.close
    sessions.foreach(session ⇒ try {
      session.close
    } catch {
      case e: Throwable ⇒ log.error("Force session close", e)
    })

    shutDownHook match {
      case Some(atomic) ⇒ { atomic.set(true); atomic.synchronized(atomic.notify) }
      case None         ⇒ {}
    }
    manager.synchronized(manager.notify)
  }

  def shutdown = {
    val OK = new AtomicBoolean(false)
    shutDownHook = Some(OK)
    manager.unregisterNode(id)
    try {
      OK.synchronized {
        for (i ← (1 to 100)) if (!OK.get) OK.wait(200L)
      }
      if (!OK.get) throw new TimeoutException() else log.debug(id + ": Node clean shutdown successful")
    } catch {
      case e: Throwable ⇒ {
        log.error(id + ": Node clean shutdown failed", e)
        throw e
      }
    }
  }
}