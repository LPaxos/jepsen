package client;

import client.proto.*;

import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.Status;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class Client implements AutoCloseable {
    public class DbException extends Exception {
        public DbException(String m) {
            super(m);
        }

        public DbException() {
        }
    }

    public class RedirectException extends DbException {
        public final Long to;

        public RedirectException(Long to) {
            super(String.valueOf(to));
            //super("redirect " + to);
            this.to = to;
        }
    }

    public class NodesUnreachableException extends DbException {
        public NodesUnreachableException() {
            //super("nodes unreachable");
        }
    }

    public class LostLeadershipException extends DbException {
        public final Long to; // nullable

        // couldHandle == this request could not have been handled by this attempt
        // due to early leadership loss. Meaningful only if there are no retries
        // (i.e. a request with given ID is received at most once by the server)
        public final boolean couldHandle;

        public LostLeadershipException(Long to, boolean couldHandle) {
            super("leader: " + (to == null ? "unknown leader" : to.toString()) + ", could handle: " + couldHandle);
            //super("lost leadership to " + (to == null ? "unknown leader" : to.toString()));
            this.to = to;
            this.couldHandle = couldHandle;
        }
    }

    public class ClosingException extends DbException {
        public ClosingException() {
            //super("node closing");
        }
    }

    public class TimeoutException extends DbException {
        public final boolean internal;

        public TimeoutException(boolean internal) {
            super(internal ? "internal " : "external");
            //super((internal ? "internal " : "external") + "timeout");
            this.internal = internal;
        }
    }

    public class TooManyRequestsException extends DbException {
        public TooManyRequestsException() {
        }
    }

    public class CompileErrorException extends DbException {
        public final String error;

        public CompileErrorException(String e) {
            super(e);
            //super("compile error: " + e);
            error = e;
        }
    }

    public class BadResponseFormatException extends Exception {
        public BadResponseFormatException(String m) {
            super(m);
        }
    }

    private static final Logger logger = Logger.getLogger(Client.class.getName());

    private final KVGrpc.KVBlockingStub blockingStub;
    private final ManagedChannel channel;
    private final int timeout;

    public Client(String target, int timeoutMs) {
        channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        blockingStub = KVGrpc.newBlockingStub(channel);
        timeout = timeoutMs;
    }

    public void close() {
        try {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.warning("channel shutdown interrupted");
        }
    }

    public Map<String, String> execute(String body)
            throws RedirectException, NodesUnreachableException, LostLeadershipException,
               ClosingException, TimeoutException, TooManyRequestsException, CompileErrorException,
               BadResponseFormatException {
        //logger.info("Executing " + body + " ...");
        Request req = Request.newBuilder()
            .setId(ThreadLocalRandom.current().nextLong())
            .setTimeout(timeout)
            .setBody(body)
            .build();
        Response resp;
        try {
            resp = blockingStub
                .withDeadlineAfter(timeout, TimeUnit.MILLISECONDS)
                .execute(req);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
                throw new TimeoutException(false);
            }
            throw e;
        }

        switch (resp.getResultCase()) {
            case READ:
                ReadResult r = resp.getRead();
                Map<String, String> m = r.getValueMap();
                //logger.info("map");
                //m.forEach((k, v) -> logger.info(k + ": " + v));
                return m;
            case REDIRECT_TO:
                RedirectTo rt = resp.getRedirectTo();
                Long to = null;
                if (rt.getMaybeIdCase() == RedirectTo.MaybeIdCase.ID) {
                    to = rt.getId();
                }
                throw new RedirectException(to);
            case NODES_UNREACHABLE:
                throw new NodesUnreachableException();
            case LOST_LEADERSHIP:
                LostLeadership ll = resp.getLostLeadership();
                Long to_ = null;
                if (ll.getMaybeIdCase() == LostLeadership.MaybeIdCase.ID) {
                    to_ = ll.getId();
                }
                throw new LostLeadershipException(to_, ll.getCouldHandle());
            case CLOSING:
                throw new ClosingException();
            case TIMEOUT:
                throw new TimeoutException(true);
            case TOO_MANY_REQUESTS:
                throw new TooManyRequestsException();
            case COMPILE_ERROR:
                throw new CompileErrorException(resp.getCompileError());
            default:
                logger.warning("result not set");
                throw new BadResponseFormatException("missing result");
        }
    }
}
