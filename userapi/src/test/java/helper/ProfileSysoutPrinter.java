package helper;

import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.model.IOTracingFrameNode;
import fk.prof.idl.Profile;
import fk.prof.idl.Recording;
import fk.prof.idl.WorkEntities;
import fk.prof.storage.AsyncStorage;
import fk.prof.storage.S3AsyncStorage;
import fk.prof.storage.S3ClientFactory;
import fk.prof.userapi.Configuration;
import fk.prof.userapi.UserapiConfigManager;
import fk.prof.userapi.api.AggregatedProfileLoader;
import fk.prof.userapi.model.AggregatedIOTraceData;
import fk.prof.userapi.model.AggregatedProfileInfo;
import fk.prof.userapi.model.AggregatedSamplesPerTraceCtx;
import fk.prof.userapi.model.StacktraceTreeIterable;
import io.vertx.core.Future;
import org.apache.commons.lang3.StringUtils;
import org.junit.Assert;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProfileSysoutPrinter {
  public static void main(String[] args) throws Exception {
    String appId = "a1", cluster = "c1", proc = "p1", trace = "~ OTHERS ~";
    int duration = 300;
    ZonedDateTime start = ZonedDateTime.parse("2018-05-02T16:47:41.863Z", DateTimeFormatter.ISO_ZONED_DATE_TIME);
    WorkEntities.WorkType workType = WorkEntities.WorkType.io_trace_work;

    UserapiConfigManager.setDefaultSystemProperties();
    Configuration config = UserapiConfigManager.loadConfig(ProfileSysoutPrinter.class.getClassLoader().getResource("config-e2e.json").getFile());
    ExecutorService execSvc = Executors.newSingleThreadExecutor();
    Configuration.S3Config s3Config = config.getStorageConfig().getS3Config();
    AsyncStorage storage = new S3AsyncStorage(S3ClientFactory.create(s3Config.getEndpoint(), s3Config.getAccessKey(), s3Config.getSecretKey()),
        execSvc, s3Config.getListObjectsTimeoutMs());
    AggregatedProfileLoader loader = new AggregatedProfileLoader(storage);

    Future<AggregatedProfileInfo> f =  Future.future();
    AggregatedProfileNamingStrategy file = new AggregatedProfileNamingStrategy(config.getProfilesBaseDir(), 1, appId, cluster, proc, start, duration, workType);
    loader.load(f, file);
    if(f.failed()) {
      throw new RuntimeException("Loading profile failed");
    }
    AggregatedProfileInfo p = f.result();

    printDecoratedHeader("HEADER");
    System.out.println(p.getHeader());

    printDecoratedHeader("TRACE\tSAMPLES");
    List<String> tracesLookup = (List<String>)(p.getTraces());
    for (Profile.TraceCtxDetail td: p.getTraceDetails()) {
      System.out.println(String.format("%s\t%d", tracesLookup.get(td.getTraceIdx()), td.getSampleCount()));
    }

    printDecoratedHeader("INDIVIDUAL PROFILES");
    for (Profile.ProfileWorkInfo wi: p.getProfiles()) {
      System.out.println("\nPROFILE: " + wi.getRecorderDetails().getIp() + "\n--------------");
      System.out.println(wi);
    }

    for(String t: tracesLookup) {
      printDecoratedHeader("AGGREGATED PROFILE FOR " + t);
      AggregatedSamplesPerTraceCtx ast = p.getAggregatedSamples(t);
      List<String> methodLookup = ast.getMethodLookup();
      AggregatedIOTraceData stdata = (AggregatedIOTraceData)ast.getAggregatedSamples();
      List<Profile.IOSource> ioSources = stdata.getIoSources();
      IOTraceNode root = traverseIONode(stdata.getFrameNodes().iterator(), methodLookup, ioSources);
      printTree(root, 1);
    }


    execSvc.shutdownNow();
  }

  private static IOTraceNode traverseIONode(Iterator<Profile.FrameNode> it, List<String> methods, List<Profile.IOSource> ioSources) {
    if(it.hasNext()) {
      Profile.FrameNode fn = it.next();
      IOTraceNode n = new IOTraceNode(methods.get(fn.getMethodId()), fn.getLineNo());
      for(Profile.IOTracingNodeProps p: fn.getIoTracingPropsList()) {
        n.props.putIfAbsent(p.getTraceType(), new HashMap<>());
        n.props.get(p.getTraceType()).putIfAbsent(ioSources.get(p.getSrcIdx()), p);
      }
      for(int i = 0; i < fn.getChildCount(); i++) {
        IOTraceNode c = traverseIONode(it, methods, ioSources);
        if(c == null) {
          throw new RuntimeException("Invalid tree, should not have happened");
        }
        n.children.add(c);
      }
      return n;
    }
    return null;
  }

  private static void printTree(IOTraceNode node, int depth) {
    int pad = node.stmt.length() + depth;
    System.out.println(padLeft(node.stmt, pad));

    for(Map.Entry<Recording.IOTraceType, Map<Profile.IOSource, Profile.IOTracingNodeProps>> e: node.props.entrySet()) {
      String itt = e.getKey().toString().toUpperCase();
      System.out.println(padLeft(itt, pad + itt.length()));

      for(Map.Entry<Profile.IOSource, Profile.IOTracingNodeProps> f: e.getValue().entrySet()) {
        Profile.IOSource is = f.getKey();
        String iss = is.getFdType().toString().toUpperCase() + " " + (is.hasUri() ? is.getUri().toUpperCase() : "");
        System.out.println(padLeft(iss, pad + iss.length()));

        Profile.IOTracingNodeProps p = f.getValue();
        String ps = String.format("samples=%d, l99=%f, l95=%f, mean=%f, bytes=%d, dropped=%b", p.hasSamples() ? p.getSamples() : 0,
            p.hasLatency99() ? p.getLatency99() : 0f,
            p.hasLatency95() ? p.getLatency95(): 0f,
            p.hasMean() ? p.getMean(): 0f,
            p.hasBytes() ? p.getBytes() : 0L,
            p.getDropped());
        System.out.println(padLeft(ps, pad + ps.length()));
      }
    }
    for(IOTraceNode c: node.children) {
      printTree(c, depth + 1);
    }
  }

  private static void printDecoratedHeader(String header) {
    char underline[] = new char[header.length()];
    for(int i = 0;i<header.length();i++) {
      underline[i] = '=';
    }
    System.out.println(underline);
    System.out.println(header);
    System.out.println(underline);
  }

  private static String padLeft(String s, int n) {
    return String.format("%1$" + n + "s", s);
  }

  public static class IOTraceNode {
    public final String stmt;
    private final List<IOTraceNode> children = new ArrayList<>();
    public final Map<Recording.IOTraceType, Map<Profile.IOSource, Profile.IOTracingNodeProps>> props = new HashMap<>();

    public IOTraceNode(String method, int line) {
      this.stmt = method + ":" + line;
    }
  }

}
