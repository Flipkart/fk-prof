/**
 * Copyright (c) 2014 Richard Warburton (richard.warburton@gmail.com)
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 **/
package fk.prof.recorder.utils;

import fk.prof.Platforms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class AgentRunner {
    private static final Logger logger = LoggerFactory.getLogger(AgentRunner.class);
    public static final String DEFAULT_AGENT_INTERVAL = "interval=100";
    private static final String PERFCTX_JAR_BASE_NAME_PATTERN = "^perfctx-.+\\.jar$";
    private static final String RECORDER_BUILD_DIR = "../recorder/build";
    private static final String EXT = Platforms.getDynamicLibraryExtension();

    private final String fqdn;
    private final String args;

    private Process process;
    private int processId;

    public AgentRunner(final String fqdn, final String args) {
        this.fqdn = fqdn;
        this.args = args;
    }

    public static void run(final String className, final Consumer<AgentRunner> handler) throws IOException {
        run(className, (String) null, handler);
    }

    public static void run(final String className,
                           final String[] args,
                           final Consumer<AgentRunner> handler) throws IOException {
        run(className, String.join(",", args), handler);
    }

    public static void run(final String className,
                           final String args,
                           final Consumer<AgentRunner> handler) throws IOException {
        AgentRunner runner = new AgentRunner(className, args);
        runner.start();
        try {
            handler.accept(runner);
        } finally {
            runner.stop();
        }
    }

    public void start() throws IOException {
        startProcess();
        //readProcessId();
    }
    
    private void startProcess() throws IOException {
        String finalArgs = (args == null) ? perfCtxArgFrag() : args + "," + perfCtxArgFrag(); 
        String agentArg = "-agentpath:" + RECORDER_BUILD_DIR + "/libfkpagent" + EXT + "=" + finalArgs;

        List<String> classpath = Util.discoverClasspath(getClass());
        //System.out.println("AGENT ARG: " + agentArg);
        //System.out.println("classpath = " + classpath);
        ProcessBuilder pb = new ProcessBuilder();
        populateEnvVars(pb);
        process = pb
                .command("java", "-XX:+PreserveFramePointer", agentArg, "-verbose:gc", "-XX:+PrintGCDateStamps", "-XX:+PrintGCTimeStamps", "-XX:+PrintGCDetails", "-Xloggc:/tmp/fkprof_gc.log", "-cp", String.join(":", classpath), fqdn)
                .redirectError(new File("/tmp/fkprof_stderr.log"))
                .redirectOutput(new File("/tmp/fkprof_stdout.log"))
                .start();
    }

    private String perfCtxArgFrag() {
        Collection<Path> files = FileResolver.findFile("../perfctx/target", PERFCTX_JAR_BASE_NAME_PATTERN);
        if (files.size() != 1) throw new IllegalStateException(String.format("Confused about the correct perf-ctx labeling jar. Expected 1 but found %s files matching the pattern '%s'", files.size(), PERFCTX_JAR_BASE_NAME_PATTERN));
        return "pctx_jar_path=" + files.iterator().next().toAbsolutePath();
    }

    private void populateEnvVars(ProcessBuilder pb) throws IOException {
        Map<String, String> env = pb.environment();
        Properties prop = new Properties();
        URL envPropUrl = this.getClass().getClassLoader().getResource("recorder_env.properties");
        if (envPropUrl != null) {
            try (InputStream envPropIS = envPropUrl.openStream()) {
                prop.load(envPropIS);
            }
        }
        for (Map.Entry<Object, Object> envProp : prop.entrySet()) {
            env.put(envProp.getKey().toString(), envProp.getValue().toString());
        }
        env.put("LD_PRELOAD", RECORDER_BUILD_DIR + "/libprefkp.so");
    }

    public boolean stop() {
        process.destroy();
        while (true) {
            try {
                if (process.waitFor(5, TimeUnit.SECONDS)) return true;
                else { process.destroyForcibly(); }
            } catch (InterruptedException e) {
                logger.info(e.getMessage(), e);
            }
        }
    }
    
    public int exitCode() {
        return process.exitValue();
    }

    public int getProcessId() {
        return processId;
    }

    public void startProfiler() {
        messageProcess('S');
    }

    public void stopProfiler() {
        messageProcess('s');
    }

    private void messageProcess(final char message) {
        try {
            final OutputStream outputStream = process.getOutputStream();
            outputStream.write(message);
            outputStream.write('\n');
            outputStream.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
