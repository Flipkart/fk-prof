package fk.prof.nfr;

import com.mashape.unirest.http.Unirest;
import fk.prof.nfr.netty.client.HttpClient;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.context.internal.ManagedSessionContext;

public class IOService {

    private final SessionFactory sessionFactory;

    private final UserDAO userDAO;

    private Random rndm = new Random(0);

    public IOService(SessionFactory sessionFactory, UserDAO userDAO) {
        this.sessionFactory = sessionFactory;
        this.userDAO = userDAO;
    }

    public Sync syncSvc() {
        return new Sync();
    }

    public Async asyncSvc(HttpClient client, EventExecutorGroup executors) {
        return new Async(client, executors);
    }

    private Object doMysqlWork(int id) {
        Transaction t = null;
        try {
            t = beginSqlTransaction();
            User u = userDAO.getById(id);
            if (u == null) {
                u = new User(id, 1);
            } else {
                u.setAccessId(u.getAccessId() + 1);
            }
            userDAO.createOrUpdate(u);
            return u;
        } catch (Exception e) {
            t = rollback(t);
            return e.getMessage();
        } finally {
            commit(t);
        }
    }

    private Transaction beginSqlTransaction() {
        if (ManagedSessionContext.hasBind(sessionFactory)) {
            return null;
        }

        Session session = sessionFactory.openSession();
        ManagedSessionContext.bind(session);
        return session.beginTransaction();
    }

    private Transaction rollback(Transaction t) {
        if (t != null) {
            t.rollback();
        }
        return null;
    }

    private void commit(Transaction t) {
        if (t != null) {
            t.commit();
        }
    }

    private Map<String, Object> buildResponse(Object httpResp, Object sqlResp, long time) {
        Map<String, Object> response = new HashMap<>();
        response.put("data", httpResp);
        response.put("user", sqlResp);
        response.put("time", time);
        return response;
    }

    private <T> Object toValue(Future<T> future) {
        if (future.isSuccess()) {
            return future.getNow();
        } else {
            return future.cause().getMessage();
        }
    }

    private int dataSz() {
        return Math.max(0, (int) (rndm.nextGaussian() * 128 + 1024));
    }

    private int delay() {
        return Math.max(0, (int) (rndm.nextGaussian() * 50 + 100));
    }

    private String clientUri(String ip, int port, String path) {
        return "http://" + ip + ":" + port + path;
    }

    public class Sync {

        public Map<String, Object> doWork(String ip, int port, int id) {
            long start = System.currentTimeMillis();

            Object httpResp, sqlResp;

            // http
            try {
                httpResp = Unirest.get(clientUri(ip, port, "/load-gen-app/io/data"))
                    .header("accept", "application/json")
                    .queryString("sz", dataSz())
                    .queryString("delay", delay())
                    .asString().getBody();
            } catch (Exception e) {
                httpResp = e.getMessage();
            }
            // http end

            // sql
            try {
                sqlResp = doMysqlWork(id);
            } catch (Exception e) {
                sqlResp = e.getMessage();
            }
            // sql end

            long end = System.currentTimeMillis();

            return buildResponse(httpResp, sqlResp, end - start);
        }
    }

    public class Async {

        private final HttpClient client;

        private final EventExecutorGroup executors;

        public Async(HttpClient client, EventExecutorGroup executors) {
            this.client = client;
            this.executors = executors;
        }

        public Future<Map<String, Object>> doWork(String ip, int port, int id) {
            Promise<Map<String, Object>> srvrResponse = executors.next().newPromise();

            long start = System.currentTimeMillis();

            Promise<String> httpResp = executors.next().newPromise();
            client.doGet(ip, port, "/load-gen-app/io/data?sz=" + dataSz() + "&delay=" + delay(), httpResp);

            Future<Object> sqlResp = executors.submit(() -> doMysqlWork(id));

            httpResp.addListener(f1 -> {
                sqlResp.addListener(f2 -> {
                    long end = System.currentTimeMillis();
                    srvrResponse.setSuccess(buildResponse(toValue(f1), toValue(f2), end - start));
                });
            });

            return srvrResponse;
        }
    }
}
