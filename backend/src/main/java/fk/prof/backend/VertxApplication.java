package fk.prof.backend;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.apache.commons.cli.*;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class VertxApplication {
  public static void main(String[] args) throws ParseException, IOException, InterruptedException {
    ConfigManager.setDefaultSystemProperties();
    CommandLineParser parser = new DefaultParser();
    Options options = new Options();
    options.addOption(Option.builder("c")
        .longOpt("conf")
        .required()
        .hasArg()
        .optionalArg(false)
        .desc("specifies json file to be used as configuration for vertx")
        .argName("json file")
        .build());

    CommandLine cmd = parser.parse(options, args);
    String confPath = cmd.getOptionValue("c");
    JsonObject config = ConfigManager.loadFileAsJson(confPath);
    JsonObject vertxConfig = ConfigManager.getVertxConfig(config);

    JsonObject curatorConfig = ConfigManager.getCuratorConfig(config);
    if (curatorConfig == null) {
      throw new RuntimeException("Curator options are required");
    }
    CuratorFramework curatorClient = createCuratorClient(curatorConfig);
    curatorClient.start();
    curatorClient.blockUntilConnected(curatorConfig.getInteger("connection.timeout.ms", 10000), TimeUnit.MILLISECONDS);

    Vertx vertx = VertxManager.initialize(vertxConfig);
    VertxManager.launch(vertx, curatorClient, config);
  }

  private static CuratorFramework createCuratorClient(JsonObject curatorConfig) {
    return CuratorFrameworkFactory.builder()
        .connectString(curatorConfig.getString("connection.url"))
        .retryPolicy(new ExponentialBackoffRetry(1000, curatorConfig.getInteger("max.retries", 3)))
        .connectionTimeoutMs(curatorConfig.getInteger("connection.timeout.ms", 10000))
        .sessionTimeoutMs(curatorConfig.getInteger("session.timeout.ms", 10000))
        .namespace(curatorConfig.getString("namespace"))
        .build();
  }
}
