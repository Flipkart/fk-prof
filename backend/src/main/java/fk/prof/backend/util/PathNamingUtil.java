package fk.prof.backend.util;

import com.google.common.io.BaseEncoding;
import recording.Recorder;

import java.nio.charset.Charset;

import static fk.prof.backend.util.ZookeeperUtil.DELIMITER;

/**
 * Utility methods for encoding and decoding strings.
 * Created by rohit.patiyal on 22/05/17.
 */
public class PathNamingUtil {
    //To allow numbering on the child of a path e.g parent/000001 parent/000002,
    //if not added in path then CREATES on parent path
    //will result in parent000001, parent000002, ...
    private static final String POLICY_NODE_PREFIX = "0";

    public static String decode32(String str) {
        return new String(BaseEncoding.base32().decode(str), Charset.forName("utf-8"));
    }

    public static String encode32(String str) {
        return BaseEncoding.base32().encode(str.getBytes(Charset.forName("utf-8")));

    }

    public static String getPolicyNodePath(Recorder.ProcessGroup processGroup, String policyRootPath, String policyVersion) {
        return policyRootPath + DELIMITER + policyVersion +
                DELIMITER + encode32(processGroup.getAppId()) +
                DELIMITER + encode32(processGroup.getCluster()) +
                DELIMITER + encode32(processGroup.getProcName()) +
                DELIMITER + POLICY_NODE_PREFIX;
    }
}
