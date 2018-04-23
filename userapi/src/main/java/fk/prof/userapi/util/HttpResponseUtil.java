package fk.prof.userapi.util;

import fk.prof.storage.StreamTransformer;
import fk.prof.userapi.http.ServiceUnavailableException;
import fk.prof.userapi.cache.CachedProfileNotFoundException;
import fk.prof.userapi.cache.ProfileLoadInProgressException;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.RoutingContext;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Created by gaurav.ashok on 11/08/17.
 */
public class HttpResponseUtil {

    private static Logger logger = LoggerFactory.getLogger(HttpResponseUtil.class);

    public static <T> void setResponse(AsyncResult<T> result, RoutingContext routingContext) {
        setResponse(result, routingContext, false);
    }

    public static <T> void setResponse(AsyncResult<T> result, RoutingContext routingContext, boolean gzipped) {
        if(routingContext.response().ended()) {
            return;
        }

        HttpServerResponse response = routingContext.response();

        if(result.failed()) {
            Throwable cause = result.cause();
            logger.error(routingContext.request().uri(), cause);

            if(cause instanceof ProfileLoadInProgressException) {
                // 202 for notifying that profile loading is in progress, and the request can be tried again after some time.
                endResponseWithError(response, cause, 202);
            }
            else if(cause instanceof FileNotFoundException) {
                if(cause instanceof CachedProfileNotFoundException) {
                    CachedProfileNotFoundException ex = (CachedProfileNotFoundException) cause;
                    if(ex.isCachedRemotely()) {
                        response.putHeader("location", "http://" + ex.getIp() + ":" + ex.getPort() + "/");
                        endResponse(response, 307);
                        return;
                    }
                    else if(ex.getCause() != null) {
                        // something went wrong while loading it. send 500
                        endResponseWithError(response, ex.getCause(), 500);
                        return;
                    }
                }
                endResponseWithError(response, cause, 404);
            }
            else if(cause instanceof IllegalArgumentException) {
                endResponseWithError(response, cause, 400);
            }
            else if(cause instanceof ServiceUnavailableException) {
                endResponseWithError(response, cause, 503);
            }
            else {
                endResponseWithError(response, cause, 500);
            }
        }
        else {
            String encodedResponse = Json.encode(result.result());

            response.putHeader("content-type", "application/json");
            if(gzipped && safeContains(routingContext.request().getHeader("Accept-Encoding"), "gzip")) {
                Buffer compressedBuf;
                try {
                    compressedBuf = Buffer.buffer(StreamTransformer.compress(encodedResponse.getBytes(Charset.forName("utf-8"))));
                }
                catch(IOException e) {
                    setResponse(Future.failedFuture(e), routingContext, false);
                    return;
                }

                response.putHeader("Content-Encoding", "gzip");
                response.end(compressedBuf);
            }
            else {
                response.end(encodedResponse);
            }
        }
    }

    private static void endResponseWithError(HttpServerResponse response, Throwable error, int statusCode) {
        response.setStatusCode(statusCode).end(buildHttpErrorObject(error.getMessage(), statusCode).encode());
    }

    private static void endResponse(HttpServerResponse response, int statusCode) {
        response.setStatusCode(statusCode).end();
    }

    private static boolean safeContains(String str, String subStr) {
        if(str == null || subStr == null) {
            return false;
        }
        return str.toLowerCase().contains(subStr.toLowerCase());
    }

    private static JsonObject buildHttpErrorObject(String msg, int statusCode) {
        final JsonObject error = new JsonObject()
            .put("timestamp", System.currentTimeMillis())
            .put("status", statusCode);

        switch (statusCode) {
            case 400: error.put("error", "BAD_REQUEST");
                break;
            case 404: error.put("error", "NOT_FOUND");
                break;
            case 500: error.put("error", "INTERNAL_SERVER_ERROR");
                break;
            default:  error.put("error", "SOMETHING_WENT_WRONG");
        }

        if (msg != null) {
            error.put("message", msg);
        }
        return error;
    }
}
