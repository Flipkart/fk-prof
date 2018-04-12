package fk.prof.nfr.driver;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.core.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.util.Random;

public class Driver implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(Driver.class);

    private final HttpClient client;
    private String ip;
    private int port;
    private final Random rndm = new Random();
    long dataTransferred = 0;
    long reqDone = 0;

    public Driver(HttpClient client, String ip, int port) {
        this.client = client;
        this.ip = ip;
        this.port = port;
    }

    @Override
    public void run() {
        while(true) {
            HttpUriRequest req = new HttpGet(uri());
            HttpResponse resp = null;
            try {
                resp = client.execute(req);
                String str = EntityUtils.toString(resp.getEntity());
                dataTransferred += str.length();
                reqDone++;
            } catch (Exception e) {
                logger.error(e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    break;
                }
            } finally {
                if(resp != null) {
                    EntityUtils.consumeQuietly(resp.getEntity());
                }
            }
        }
    }

    private String uri() {
        return "http://" + ip + ":" + port + "/load-gen-app/io?id=" + rndmId();
    }

    private int rndmId() {
        return rndm.nextInt(1000);
    }
}
