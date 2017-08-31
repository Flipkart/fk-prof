package fk.prof.userapi.api;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.S3ClientOptions;
import com.amazonaws.services.s3.model.*;
import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.model.AggregationWindowSerializer;
import fk.prof.aggregation.model.AggregationWindowSummarySerializer;
import fk.prof.aggregation.model.FinalizedAggregationWindow;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import io.vertx.core.Future;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Utility class to upload aggregated files to s3.
 * Created by gaurav.ashok on 27/03/17.
 */
public class S3Util {
    private final String sampleStackTraces = "[\n [\n\"B():10\",\n\"A():10\"\n ],\n [\n\"B():10\",\n\"A():10\"\n ],\n [\n\"C():10\",\n\"B():11\",\n\"A():10\"\n ],\n [\n\"C():10\",\n\"B():11\",\n\"A():10\"\n ],\n [\n\"B():10\",\n\"A():11\"\n ],\n [\n\"C():10\",\n\"B():11\",\n\"A():11\"\n ],\n [\n\"C():10\",\n\"B():11\",\n\"A():11\"\n ],\n [\n\"C():10\",\n\"B():11\",\n\"A():10\",\n\"C():10\"\n ],\n [\n\"B():10\",\n\"B():11\",\n\"A():10\",\n\"C():10\"\n ],\n [\n\"B():10\",\n\"C():10\"\n ]\n]";


    public void uploadFile() throws Exception {

        String startime = "2017-03-14T07:00:00";
        int durationInSeconds = 300;

        FinalizedAggregationWindow window =  MockAggregationWindow.buildAggregationWindow(startime, () -> sampleStackTraces, durationInSeconds);
        ZonedDateTime startimeZ = ZonedDateTime.parse(startime + "Z", DateTimeFormatter.ISO_ZONED_DATE_TIME);

        ByteArrayOutputStream boutWS = new ByteArrayOutputStream();
        GZIPOutputStream zoutWS = new GZIPOutputStream(boutWS);
        ByteArrayOutputStream boutSummary = new ByteArrayOutputStream();
        GZIPOutputStream zoutSummary = new GZIPOutputStream(boutSummary);

        AggregationWindowSerializer windowsSer = new AggregationWindowSerializer(window, AggregatedProfileModel.WorkType.cpu_sample_work);
        AggregationWindowSummarySerializer windowSummarySer = new AggregationWindowSummarySerializer(window);

        windowsSer.serialize(zoutWS);
        windowSummarySer.serialize(zoutSummary);

        //flush
        zoutWS.close();
        zoutSummary.close();

        System.out.println("cpusamaple size: " + boutWS.size());
        System.out.println("summary size: " + boutSummary.size());

        // check for validity
        AggregatedProfileLoader loader = new AggregatedProfileLoader(null);
        Future f1 =  Future.future();
        AggregatedProfileNamingStrategy file1 = new AggregatedProfileNamingStrategy("profiles", 1, "app1", "cluster1", "proc1", startimeZ, durationInSeconds, AggregatedProfileModel.WorkType.cpu_sample_work);
        loader.loadFromInputStream(f1, file1, new GZIPInputStream(new ByteArrayInputStream(boutWS.toByteArray())));
        assert f1.succeeded();

        Future f2 =  Future.future();
        AggregatedProfileNamingStrategy file2 = new AggregatedProfileNamingStrategy("profiles", 1, "app1", "cluster1", "proc1", startimeZ, durationInSeconds);
        loader.loadSummaryFromInputStream(f2, file2, new GZIPInputStream(new ByteArrayInputStream(boutSummary.toByteArray())));
        assert f2.succeeded();

        // assert false : "shortcut to doom";

        // write to s3
        // TODO remove the credentials from here.
        String accessKey = "<put access key here>";
        String secretKey = "<put secret key here>";
        ClientConfiguration clientConfig = new ClientConfiguration();
        clientConfig.setProtocol(Protocol.HTTP);

        AWSCredentials credentials = new BasicAWSCredentials(accessKey, secretKey);
        AmazonS3Client conn = new AmazonS3Client(credentials,clientConfig);
        conn.setEndpoint("http://<endpoint here>:<port here>");
        conn.setS3ClientOptions(new S3ClientOptions().withPathStyleAccess(true));

        // make sure bucket exit
        List<Bucket> buckets = conn.listBuckets();
        assert buckets.size() > 0;
        Optional<Bucket> profilesBucket = buckets.stream().filter(b -> "profiles".equals(b.getName())).findFirst();
        if(!profilesBucket.isPresent()) {
            profilesBucket = Optional.of(conn.createBucket("profiles"));
        }

        ObjectListing listing = conn.listObjects(new ListObjectsRequest().withBucketName("profiles"));

        System.out.println("Existing files: ");
        listing.getObjectSummaries().stream().forEach(e -> System.out.println(e.getKey()));

        writeToS3(conn, file1, profilesBucket.get(), boutWS.toByteArray());
        writeToS3(conn, file2, profilesBucket.get(), boutSummary.toByteArray());

        conn.shutdown();
    }

    private void writeToS3(AmazonS3Client conn, AggregatedProfileNamingStrategy filename, Bucket profilesBucket, byte[] bytes) {
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(bytes.length);
        String name = filename.getFileName(0);
        name = name.substring(name.indexOf('/') + 1);

        System.out.println("writing to : " + name);
        PutObjectResult putResult = conn.putObject(profilesBucket.getName(), name, new ByteArrayInputStream(bytes), meta);

        System.out.println("len: " + bytes.length + ", md5: " + putResult.getContentMd5());
    }
}
