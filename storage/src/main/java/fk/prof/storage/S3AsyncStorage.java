package fk.prof.storage;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * AsyncStorage impl backed by S3 Object store. It uses an executorService to offload the request.
 * @author gaurav.ashok
 */
public class S3AsyncStorage implements AsyncStorage {
    private static final String DELIMITER = "/";
    private static Logger LOGGER = LoggerFactory.getLogger(S3AsyncStorage.class);
    private static String NO_SUCH_KEY = "NoSuchKey";
    private static String NO_SUCH_BUCKET = "NoSuchBucket";
    private AmazonS3 client;
    private final ExecutorService executorService;
    private final long listObjectsTimeoutInMs;

    public S3AsyncStorage(AmazonS3 client, ExecutorService executorService, long listObjectsTimeoutInMs) {
        assert client != null : "S3Client cannot be null";

        this.client = client;
        this.executorService = executorService;
        this.listObjectsTimeoutInMs = listObjectsTimeoutInMs;
    }

    @Override
    public CompletableFuture<Void> storeAsync(String path, InputStream content, long length) {
        S3ObjectPath objectPath = new S3ObjectPath(path);
        return CompletableFuture.runAsync(() -> {
            try {
                ObjectMetadata meta = new ObjectMetadata();
                meta.setContentLength(length);
                client.putObject(objectPath.bucket, objectPath.fileName, content, meta);
            } catch (AmazonClientException e) { // content InputStream is by default closed by the S3Client, so need to close it.
                throw mapClientException(e);
            } catch (Exception ex) {
                throw new StorageException("Unexpected error during S3 PUT for path: " + path, ex);
            } finally {
                try {
                    content.close();
                } catch (IOException e) {
                    LOGGER.error("Failed to close inputStream for path: {}", path, e);
                }
            }
        }, executorService);
    }

    @Override
    public CompletableFuture<InputStream> fetchAsync(String path) {
        S3ObjectPath objectPath = new S3ObjectPath(path);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return client.getObject(objectPath.bucket, objectPath.fileName).getObjectContent();
            } catch (AmazonServiceException svcEx) {
                LOGGER.error("S3 getObject failed: {}", path, svcEx);
                throw mapServiceException(svcEx);
            } catch (AmazonClientException clientEx) {
                LOGGER.error("S3 getObject failed: {}", path, clientEx);
                throw mapClientException(clientEx);
            }
        }, executorService);
    }

    @Override
    public CompletableFuture<Set<String>> listAsync(String prefixPath, boolean recursive) {
        S3ObjectPath objectPath = new S3ObjectPath(prefixPath);
        return CompletableFuture.supplyAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                Set<String> allObjects = new HashSet<>();
                ObjectListing objects = null;
                ListObjectsRequest request = new ListObjectsRequest().withBucketName(objectPath.bucket).withPrefix(objectPath.fileName);
                if(!recursive) {
                    request.withDelimiter(DELIMITER);
                }

                do {
                    if(objects != null) {
                        request.setMarker(objects.getNextMarker());
                    }
                    objects = client.listObjects(request);

                    for (S3ObjectSummary objSummary : objects.getObjectSummaries()) {
                        allObjects.add(objectPath.bucket + "/" + objSummary.getKey());                    //All files with prefix with complete path
                    }
                    for (String commonPrefix : objects.getCommonPrefixes()) {                             //Folders in current dir (if delimiter passed)
                        allObjects.add(objectPath.bucket + "/" + commonPrefix);                           //with complete path
                    }

                    if(startTime + listObjectsTimeoutInMs < System.currentTimeMillis()) {
                        LOGGER.warn("Timeout while listing objects for prefix: {}, isRecursive: {}, fileCount: {}", prefixPath, recursive, allObjects.size());
                        break;
                    }
                } while (objects.isTruncated());
                // TODO add metric to track the time taken by this method.
                return allObjects;
            } catch (AmazonServiceException svcEx) {
                LOGGER.error("S3 ListObjects failed: {}", prefixPath, svcEx);
                throw mapServiceException(svcEx);
            } catch (AmazonClientException clientEx) {
                LOGGER.error("S3 ListObjects failed: {}", prefixPath, clientEx);
                throw mapClientException(clientEx);
            }
        }, executorService);
    }

    private StorageException mapClientException(AmazonClientException ex) {
        return new StorageException(ex.getMessage(), ex, ex.isRetryable());
    }

    private StorageException mapServiceException(AmazonServiceException ex) {
        // map non existent bucket and non existent path to PathNotFound exception specifically
        if (NO_SUCH_BUCKET.equals(ex.getErrorCode())) {
            return new ObjectNotFoundException(NO_SUCH_BUCKET + ": " + ex.getMessage(), ex);
        } else if (NO_SUCH_KEY.equals(ex.getErrorCode())) {
            return new ObjectNotFoundException(NO_SUCH_KEY + ": " + ex.getMessage(), ex);
        }

        return mapClientException(ex);
    }

    private class S3ObjectPath {
        final String bucket;
        final String fileName;

        S3ObjectPath(String fullPath) {
            int bucketEnd = fullPath.indexOf(DELIMITER);
            if (bucketEnd == -1) {
                throw new IllegalArgumentException("expecting object path in the format of {bucket}/{fileName}");
            }

            this.bucket = fullPath.substring(0, bucketEnd);
            this.fileName = fullPath.substring(bucketEnd + 1);
        }
    }
}
