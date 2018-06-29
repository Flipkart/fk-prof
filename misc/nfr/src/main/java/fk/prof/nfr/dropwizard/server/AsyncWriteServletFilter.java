package fk.prof.nfr.dropwizard.server;

import fk.prof.nfr.RndGen;

import javax.servlet.*;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.Charset;

@WebServlet(
    urlPatterns = "/load-gen-app/jetty/async-write",
    asyncSupported = true
)
public class AsyncWriteServletFilter extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        AsyncContext asyncContext = req.startAsync();
        ServletOutputStream out = resp.getOutputStream();
        out.setWriteListener(new StandardDataStream(asyncContext, out));
    }

    private final class StandardDataStream implements WriteListener
    {
        private final AsyncContext async;
        private final ServletOutputStream out;
        private final RndGen rndm = new RndGen();

        private int counter = 0;

        private StandardDataStream(AsyncContext async, ServletOutputStream out)
        {
            this.async = async;
            this.out = out;
        }

        public void onWritePossible() throws IOException
        {
            byte[] buffer;

            // while we are able to write without blocking

            while(out.isReady())
            {
                if (counter == 4)
                {
                    System.out.println("Completing async");
                    async.complete();
                    return;
                }

                // write out the copy buffer.
                System.out.println("Writing to output Stream");

                String str = rndm.getString(4096);
                buffer = str.getBytes(Charset.forName("utf-8"));
                out.write(buffer, 0, buffer.length);
                counter++;
            }
        }

        public void onError(Throwable t)
        {
            getServletContext().log("Async Error",t);
            async.complete();
        }
    }
}
