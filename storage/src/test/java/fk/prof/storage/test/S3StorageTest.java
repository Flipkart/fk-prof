package fk.prof.storage.test;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import fk.prof.storage.S3AsyncStorage;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by gaurav.ashok on 28/04/17.
 */
public class S3StorageTest {

    public final static String baseS3Bucket = "profiles";

    @Test
    public void testListObjects_shouldReturnAllObjectsWhenTruncated() throws Exception {
        ExecutorService execSvc = Executors.newSingleThreadExecutor();
        AmazonS3 client = Mockito.mock(AmazonS3.class);
        mockTruncatedResponse(client, 0);

        S3AsyncStorage storage = new S3AsyncStorage(client, execSvc, 500);

        Set<String> result = storage.listAsync(baseS3Bucket + "/file", true).get();

        Assert.assertEquals(3, result.size());
    }

    @Test
    public void testListObjects_shouldReturnPartialListInCaseOfTimeout() throws Exception {
        ExecutorService execSvc = Executors.newSingleThreadExecutor();
        AmazonS3 client = Mockito.mock(AmazonS3.class);
        mockTruncatedResponse(client, 1000);

        S3AsyncStorage storage = new S3AsyncStorage(client, execSvc, 500);

        Set<String> result = storage.listAsync(baseS3Bucket + "/file", true).get();

        Assert.assertEquals(2, result.size());
    }

    @Test
    public void testListObjects_shouldReturnAllObjectsWhenNonTruncated() throws Exception {
        ExecutorService execSvc = Executors.newSingleThreadExecutor();
        AmazonS3 client = Mockito.mock(AmazonS3.class);
        mockNonTruncatedResponse(client);

        S3AsyncStorage storage = new S3AsyncStorage(client, execSvc, 500);

        Set<String> result = storage.listAsync(baseS3Bucket + "/file", true).get();

        Assert.assertEquals(2, result.size());
    }

    private void mockTruncatedResponse(AmazonS3 client, int takeTimeInSec) {
        S3ObjectSummary summary1 = getObjSummary("file/1");
        S3ObjectSummary summary2 = getObjSummary("file/2");
        S3ObjectSummary summary3 = getObjSummary("file/3");

        Mockito.when(client.listObjects(ArgumentMatchers.<ListObjectsRequest>any())).thenReturn(getObjectListing(true, summary1))
            .thenAnswer(inv -> {
                Thread.sleep(takeTimeInSec);
                return getObjectListing(true, summary2);
            }).thenReturn(getObjectListing(false, summary3));
    }

    private void mockNonTruncatedResponse(AmazonS3 client) {
        S3ObjectSummary summary1 = getObjSummary("file/1");
        S3ObjectSummary summary2 = getObjSummary("file/2");

        Mockito.when(client.listObjects(ArgumentMatchers.<ListObjectsRequest>any())).thenReturn(getObjectListing(false, summary1, summary2));
    }

    private S3ObjectSummary getObjSummary(String filename) {
        S3ObjectSummary summary = new S3ObjectSummary();
        summary.setBucketName(baseS3Bucket);
        summary.setKey(String.valueOf(filename));
        return summary;
    }

    private ObjectListing getObjectListing(boolean truncated, S3ObjectSummary... summaries) {
        ObjectListing objects = new ObjectListing();
        objects.setBucketName(baseS3Bucket);
        objects.getObjectSummaries().addAll(Arrays.asList(summaries));
        objects.setTruncated(truncated);
        objects.setMarker(summaries[summaries.length - 1].getKey());
        return objects;
    }
}
