package no.found.elasticsearch.transport.netty;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.netty.buffer.ChannelBuffer;
import org.elasticsearch.common.netty.buffer.ChannelBuffers;
import org.elasticsearch.common.netty.channel.*;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class FoundSwitchingChannelHandler extends SimpleChannelHandler {
    private final ESLogger logger;
    private final ChannelPipelineFactory originalFactory;
    private final String[] hostSuffixes;
    private final int[] sslPorts;
    private final String apiKey;
    private final boolean unsafeAllowSelfSigned;

    List<MessageEvent> pendingEvents = new ArrayList<MessageEvent>();
    ChannelBuffer buffered = ChannelBuffers.EMPTY_BUFFER;
    boolean isFoundCluster = false;

    public FoundSwitchingChannelHandler(ESLogger logger, ChannelPipelineFactory originalFactory, boolean unsafeAllowSelfSigned, String[] hostSuffixes, int[] sslPorts, String apiKey) {
        this.logger = logger;
        this.originalFactory = originalFactory;

        this.unsafeAllowSelfSigned = unsafeAllowSelfSigned;
        this.hostSuffixes = hostSuffixes;
        this.sslPorts = sslPorts;
        this.apiKey = apiKey;
    }

    @Override
    public synchronized void channelBound(final ChannelHandlerContext ctx, final ChannelStateEvent e) throws Exception {
        SocketAddress socketAddress = ctx.getChannel().getRemoteAddress();
        if(socketAddress instanceof InetSocketAddress) {
            InetSocketAddress inetSocketAddress = (InetSocketAddress)socketAddress;

            for(String suffix: hostSuffixes) isFoundCluster = isFoundCluster || inetSocketAddress.getHostString().endsWith(suffix);

            if(isFoundCluster) {
                for(int sslPort: sslPorts) {
                    if(inetSocketAddress.getPort() == sslPort) {
                        logger.info("Enabling SSL on transport layer with unsafeAllowSelfSigned=[{}].", unsafeAllowSelfSigned);
                        FoundSSLHandler handler = FoundSSLUtils.getSSLHandler(unsafeAllowSelfSigned, inetSocketAddress);
                        ctx.getPipeline().addFirst("ssl", handler);
                        break;
                    }
                }
            }
        }
        super.channelBound(ctx, e);
    }

    @Override
    public synchronized void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        super.channelConnected(ctx, e);
        if(isFoundCluster) {
            ctx.sendUpstream(e);

            logger.info("Authenticating with Found Elasticsearch at [{}]", ctx.getChannel().getRemoteAddress());
            String remoteHostString = ((InetSocketAddress)ctx.getChannel().getRemoteAddress()).getHostString();
            ChannelBuffer message = new FoundTransportHeader(remoteHostString, apiKey).getHeaderBuffer();

            ctx.sendDownstream(new DownstreamMessageEvent(ctx.getChannel(), Channels.future(ctx.getChannel()), message, ctx.getChannel().getRemoteAddress()));
        }
    }

    @Override
    public synchronized void writeRequested(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        pendingEvents.add(e);
    }

    @Override
    public synchronized void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        super.messageReceived(ctx, e);

        if(e.getMessage() instanceof ChannelBuffer) {
            ChannelBuffer newBuffer = (ChannelBuffer)e.getMessage();
            buffered = ChannelBuffers.copiedBuffer(buffered, newBuffer);

            if(buffered.readableBytes() < 8) {
                return;
            }
            int payloadLength = buffered.getInt(0);
            int revision = buffered.getInt(4);

            if(revision == 1) {
                if(buffered.readableBytes() < payloadLength + 4) {
                    return;
                }
                buffered.skipBytes(8);

                handleRevision1Response(ctx, payloadLength);

                for(MessageEvent event: pendingEvents) ctx.sendDownstream(event);
                pendingEvents.clear();
            } else {
                handleUnknownRevisionResponse(ctx);
                pendingEvents.clear();
            }
        }
    }

    private void handleUnknownRevisionResponse(ChannelHandlerContext ctx) {
        logger.error("Unknown revision response received.");
        ctx.getPipeline().remove(this);
        ctx.getChannel().close();
    }

    private void handleRevision1Response(ChannelHandlerContext ctx, int payloadLength) throws Exception {
        int code = buffered.readInt();

        int descriptionLength = buffered.readInt();
        byte[] descBytes = new byte[descriptionLength];
        buffered.readBytes(descBytes, 0, descBytes.length);

        String description = new String(descBytes, StandardCharsets.UTF_8);

        logger.debug("Decoded payload with length:[{}], code:[{}], descriptionLength:[{}], description:[{}]", payloadLength, code, descriptionLength, description);

        if(200 <= code && code <= 299) {
            logger.info("Connected to Found Elasticsearch: [{}]: [{}]", code, description);
        } else {
            logger.error("Unable to connect to Found Elasticsearch: [{}]: [{}]", code, description);
            ctx.getChannel().close();
            return;
        }

        ctx.getPipeline().remove(this);

        final ChannelPipeline pipeline = originalFactory.getPipeline();

        while(true) {
            ChannelHandler handler = pipeline.getFirst();
            if(handler == null) break;

            ChannelHandlerContext handlerContext = pipeline.getContext(handler);

            ctx.getPipeline().addLast(handlerContext.getName(), handler);
            pipeline.remove(handler);
        }

        ChannelBuffer remaining = buffered.slice();
        if(remaining.readableBytes() > 0)
            ctx.sendUpstream(new UpstreamMessageEvent(ctx.getChannel(), remaining, ctx.getChannel().getRemoteAddress()));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        if (e.getCause() instanceof ClosedChannelException) {
            // do nothing
        } else if(e.getCause() instanceof UnresolvedAddressException) {
            logger.error("Unable to resolve one of the server addresses.");
        } else if(e.getCause().getMessage() != null && e.getCause().getMessage().contains("Connection reset by peer")) {
            // still do nothing
        } else {
            super.exceptionCaught(ctx, e);
        }
    }
}