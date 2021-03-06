package net.imagini.drift.cluster

import scala.collection.JavaConverters.asScalaBufferConverter
import org.I0Itec.zkclient.IZkChildListener
import org.I0Itec.zkclient.IZkDataListener
import org.I0Itec.zkclient.ZkClient
import org.apache.zookeeper.CreateMode
import java.util.concurrent.ConcurrentHashMap
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentMap
import scala.collection.JavaConverters._
import grizzled.slf4j.Logger
import net.imagini.drift.types.DriftSchema

class DriftManagerZk(val zkConnect: String, override val clusterId:String = "default") extends DriftManager {
  val zkClient = new ZkClient(zkConnect)
  init
  override protected def get[T](path: String): T = zkClient.readData(path)
  override protected def list[T](path: String): Map[String, T] = {
    zkClient.getChildren(path).asScala.map(child => child -> get(path + "/" + child)).toMap
  }
  override protected def pathExists(path: String) = zkClient.exists(path)
  override protected def pathCreatePersistent(path: String, data:Any) = zkClient.create(path, data, CreateMode.PERSISTENT)
  override protected def pathUpdate(path: String, data:Any) = zkClient.writeData(path, data)
  override protected def pathCreateEphemeral(path: String, data:Any) = zkClient.create(path, data, CreateMode.EPHEMERAL)
  override def close = zkClient.close
  override protected def pathDelete(path: String) = zkClient.delete(path)

  override protected def watchPathData[T](zkPath: String, listener: (Option[T] ⇒ Unit)) {
    zkClient.subscribeDataChanges(zkPath, new IZkDataListener {
      override def handleDataChange(zkPath: String, data: Object) = listener(Some(data.asInstanceOf[T]))
      override def handleDataDeleted(zkPath: String) = listener(None)
    });
    listener(Some(zkClient.readData(zkPath)))
  }
  override protected def watchPathChildren[T](zkPath: String, listener: (Map[String, T]) ⇒ Unit) {
    zkClient.subscribeChildChanges(zkPath, new IZkChildListener {
      override def handleChildChange(parentPath: String, currentChilds: java.util.List[String]) = update(zkPath, currentChilds, listener);
    })
    update(zkPath, zkClient.getChildren(zkPath), listener)
  }
  private def update[T](zkPath: String, children: java.util.List[String], listener: (Map[String, T]) ⇒ Unit) = {
    listener(zkClient.getChildren(zkPath).asScala.map(p ⇒ p -> zkClient.readData[T](zkPath + "/" + p)).toMap)
  }

}