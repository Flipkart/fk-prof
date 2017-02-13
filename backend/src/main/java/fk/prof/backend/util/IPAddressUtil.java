package fk.prof.backend.util;

import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class IPAddressUtil {
  private static Logger logger = LoggerFactory.getLogger(IPAddressUtil.class);
  private static byte[] ipAddressBytes;
  private static String ipAddressText;

  static {
    try {
      InetAddress ip = InetAddress.getLocalHost();
      ipAddressBytes = ip.getAddress();
      ipAddressText = ip.getHostAddress();
    } catch (UnknownHostException ex) {
      ipAddressBytes = null;
      ipAddressText = null;
      logger.error("Cannot determine ip address", ex);
    }
  }

  public static byte[] getIPAddressAsBytes() {
    assert ipAddressBytes != null;
    return ipAddressBytes;
  }

  public static String getIPAddressAsString() {
    assert ipAddressText != null;
    return ipAddressText;
  }

}
