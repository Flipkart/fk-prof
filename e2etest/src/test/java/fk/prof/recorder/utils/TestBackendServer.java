package fk.prof.recorder.utils;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.function.Function;
/**
 * @understands running a mock backend-server to support integration-testing recorder
 */
public class TestBackendServer {
    private static class HandlerStub {
        final CompletableFuture<Void> fut;
        private final Function<byte[], byte[]>[] handlers;
        private final int idx;
        private final boolean waitForReqBytes;

        public HandlerStub(Function<byte[], byte[]>[] handlers, int idx, boolean waitForReqBytes) {
            this.handlers = handlers;
            this.idx = idx;
            this.waitForReqBytes = waitForReqBytes;
            this.fut = new CompletableFuture<Void>();
        }

        public HandlerStub(Function<byte[], byte[]>[] handlers, int idx) {
            this(handlers, idx, true);
        }

        public void handle(Request req, OutputStream resStrm) throws IOException {
            byte[] reqBytes = null;
            if (waitForReqBytes) {
                ServletInputStream inputStream = req.getInputStream();
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                IOUtils.copy(inputStream, os);
                reqBytes = os.toByteArray();
            }
            try {
                byte[] resBytes = handlers[idx].apply(reqBytes);
                resStrm.write(resBytes);
            } catch (RuntimeException | Error e) {
                System.err.println("Failed to handle request for: " + req.getPathInfo());
                e.printStackTrace();
                throw e;
            } finally {
                fut.complete(null);
            }
        }
    }

    private final Map<String, Queue<HandlerStub>> actions;
    private Server server;
    private MutableBoolean stopped = new MutableBoolean(true);

    public TestBackendServer(final int port) {
        server = new Server(port);
        actions = new ConcurrentHashMap<>();
        Queue<HandlerStub> helloWorldQueue = new ConcurrentLinkedQueue<>();
        actions.put("/hello", helloWorldQueue);
        helloWorldQueue.offer(makeHelloWorldStub(helloWorldQueue));

        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[]{new StubCallingHandler()});
        server.setHandler(handlers);

        start();
    }

    public void start() {
        try {
            server.start();
            stopped.setValue(false);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private HandlerStub makeHelloWorldStub(Queue<HandlerStub> helloWorldQueue) {
        Function<byte[], byte[]> fn = (req) -> {
            helloWorldQueue.offer(makeHelloWorldStub(helloWorldQueue));
            System.out.println("req = [" + new String(req) + "]");
            byte[] bytes = "Hello World\n".getBytes(Charset.defaultCharset());
            return bytes;
        };
        return new HandlerStub(new Function[] {fn}, 0);
    }

    class StubCallingHandler extends AbstractHandler {
        private Logger logger = LoggerFactory.getLogger(StubCallingHandler.class);
        @Override
        public void handle(String path, Request request, HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws IOException, ServletException {
            if (request.getDispatcherType().equals(DispatcherType.ERROR)) {
                logger.debug("Request Handler - Error: " + path);
                request.setHandled(true);
                return;
            }
            logger.debug("Request Handler - Normal: " + path);
            Queue<HandlerStub> handlerStubs = actions.get(path);
            HandlerStub stub = handlerStubs.poll();
            ServletOutputStream os = httpServletResponse.getOutputStream();
            stub.handle(request, os);
            os.flush();
        }
    }

    public void stop() {
        if (stopped.getValue()) {
            return;
        }
        try {
            server.stop();
            server.join();
            stopped.setValue(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Future[] register(String path, Function<byte[], byte[]>[] handler) {
        return register(path, handler, true);
    }

    public Future[] register(String path, Function<byte[], byte[]>[] handler, boolean waitForReqBytes) {
        Queue<HandlerStub> handlerStubs = actions.get(path);
        if (handlerStubs == null) {
            handlerStubs = new ConcurrentLinkedQueue<>();
            actions.putIfAbsent(path, handlerStubs);
            handlerStubs = actions.get(path);
        }
        Future[] arr = new Future[handler.length];
        for (int i = 0; i < handler.length; i++) {
            HandlerStub hStub = new HandlerStub(handler, i, waitForReqBytes);
            handlerStubs.offer(hStub);
            arr[i] = hStub.fut;
        }
        return arr;
    }
}
