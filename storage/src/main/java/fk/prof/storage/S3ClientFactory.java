package fk.prof.storage;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AnonymousAWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.S3ClientOptions;
import com.amazonaws.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by gaurav.ashok on 02/05/17.
 */
public class S3ClientFactory {

    private static Logger LOGGER = LoggerFactory.getLogger(S3ClientFactory.class);

    public static AmazonS3 create(String endpoint, String accessKey, String secretKey) {
        assert StringUtils.isNullOrEmpty(endpoint) : "s3 endpoint cannot be null";

        ClientConfiguration clientConfig = new ClientConfiguration();
        clientConfig.setProtocol(Protocol.HTTP);

        AWSCredentials credentials;
        if(StringUtils.isNullOrEmpty(accessKey) || StringUtils.isNullOrEmpty(secretKey)) {
            LOGGER.warn("S3 access key | secret key is empty. Trying anonymous credentials");
            credentials = new AnonymousAWSCredentials();
        }
        else {
            credentials = new BasicAWSCredentials(accessKey, secretKey);
        }

        AmazonS3 client = new AmazonS3Client(credentials, clientConfig);
        client.setEndpoint(endpoint);
        client.setS3ClientOptions(new S3ClientOptions().withPathStyleAccess(true));

        return client;
    }
}
