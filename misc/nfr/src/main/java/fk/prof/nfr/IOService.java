package fk.prof.nfr;

import com.mashape.unirest.http.Unirest;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class IOService {

    private final String ip;

    private final int port;

    private final UserDAO userDAO;

    private Random rndm = new Random(0);

    public IOService(String driverIp, int driverPort, UserDAO userDAO) {
        this.ip = driverIp;
        this.port = driverPort;
        this.userDAO = userDAO;
    }

    public  Map<String, Object> soSomeIOWork(int id) {
        Map<String, Object> srvrResponse = new HashMap<>();

        long start = System.currentTimeMillis();

        Object httpResp, sqlResp;

        // http
        try {
            httpResp = Unirest.get(clientUri("/driver/data"))
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
            User u = userDAO.getById(id);
            if (u == null) {
                u = new User(id, 1);
            } else {
                u.setAccessId(u.getAccessId() + 1);
            }
            userDAO.createOrUpdate(u);
            sqlResp = u;
        } catch (Exception e) {
            sqlResp = e.getMessage();
        }
        // sql end

        srvrResponse.put("data", httpResp);
        srvrResponse.put("user", sqlResp);

        long end = System.currentTimeMillis();
        srvrResponse.put("time", end - start);

        return srvrResponse;
    }

    private int dataSz() {
        return Math.max(0, (int) (rndm.nextGaussian() * 128 + 1024));
    }

    private int delay() {
        return Math.max(0, (int) (rndm.nextGaussian() * 50 + 100));
    }

    private String clientUri(String path) {
        return "http://" + ip + ":" + port + path;
    }
}
