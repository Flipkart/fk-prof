package fk.prof.backend.util;

import io.vertx.core.Future;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.zookeeper.KeeperException;

import java.util.concurrent.CompletableFuture;

public class ZookeeperUtil {

  public static byte[] readZNode(CuratorFramework curatorClient, String zNodePath)
      throws Exception {
    return curatorClient.getData().forPath(zNodePath);
  }

  public static void writeZNode(CuratorFramework curatorClient, String zNodePath, byte[] data, boolean create)
      throws Exception {
    if (create) {
      curatorClient.create().forPath(zNodePath, data);
    } else {
      curatorClient.setData().forPath(zNodePath, data);
    }
  }

  public static Future<Void> sync(CuratorFramework curatorClient, String zNodePath) {
    Future<Void> future = Future.future();
    try {
      curatorClient.sync().inBackground((client, event) -> {
        if (KeeperException.Code.OK.intValue() == event.getResultCode()) {
          future.complete();
        } else {
          future.fail(new RuntimeException("Error when zk sync issued for node path = " + zNodePath + " with result code = " + event.getResultCode()));
        }
      }).forPath(zNodePath);
    } catch (Exception ex) {
      future.fail(ex);
    }
    return future;
  }

  public static CompletableFuture<byte[]> readZNodeAsync(CuratorFramework curatorClient, String zNodePath) {
    CompletableFuture<byte[]> future = new CompletableFuture<>();
    try {
      curatorClient.getData().inBackground((client, event) -> completeFuture(event, event.getData(), future)).forPath(zNodePath);
    } catch (Exception e) {
      future.completeExceptionally(new RuntimeException("Error reading data from Zookeeper znode."));
    }
    return future;
  }

  public static CompletableFuture<Void> writeZNodeAsync(CuratorFramework curatorClient, String zNodePath, byte[] data, boolean create) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    try {
      if (create) {
        curatorClient.create().creatingParentsIfNeeded().inBackground((client, event) -> completeFuture(event, null, future)).forPath(zNodePath, data);
      } else {
        curatorClient.setData().inBackground((client, event) -> completeFuture(event, null, future)).forPath(zNodePath, data);
      }
    } catch (Exception e) {
      future.completeExceptionally(new RuntimeException("Error writing data to Zookeeper znode."));
    }
    return future;
  }

  public static CompletableFuture<Void> deleteZNodeAsync(CuratorFramework curatorClient, String zNodePath) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    try {
      curatorClient.delete().inBackground((client, event) -> completeFuture(event, null, future)).forPath(zNodePath);
    } catch (Exception e) {
      future.completeExceptionally(new RuntimeException("Error writing data to Zookeeper znode."));
    }
    return future;
  }

  private static <T> void completeFuture(CuratorEvent event, T result, CompletableFuture<T> future) {
    if (KeeperException.Code.OK.intValue() == event.getResultCode()) {
      future.complete(result);
    } else {
      future.completeExceptionally(new RuntimeException("Error from ZooKeeper, result=" + KeeperException.Code.get(event.getResultCode()).name()));
    }
  }
}
