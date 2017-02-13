package fk.prof.backend.leader.election;

import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.lang.reflect.Method;

public enum KillBehavior {
  DO_NOTHING {
    private Logger logger = LoggerFactory.getLogger(KillBehavior.class);

    @Override
    public void process() {
      logger.warn("Relinquished leadership, doing nothing");
    }
  },

  SHUTDOWN {
    private Logger logger = LoggerFactory.getLogger(KillBehavior.class);

    @Override
    public void process() {
      logger.warn("Relinquished leadership, shutting down");
      System.exit(1);
    }
  },

  KILL {
    private Logger logger = LoggerFactory.getLogger(KillBehavior.class);

    @Override
    public void process() throws Exception {
      logger.warn("Relinquished leadership, attempting to suicide!");
      Class<?> clazz = Class.forName("java.lang.UNIXProcess");
      Method destroyMethod = clazz.getDeclaredMethod("destroyProcess", int.class, boolean.class);
      destroyMethod.setAccessible(true);
      destroyMethod.invoke(null, 0, true);
    }
  };

  public abstract void process() throws Exception;
}
