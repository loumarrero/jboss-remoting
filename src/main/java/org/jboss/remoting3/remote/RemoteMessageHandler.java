/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.remoting3.remote;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import org.jboss.marshalling.ByteInput;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.NioByteInput;
import org.jboss.marshalling.Unmarshaller;
import org.jboss.marshalling.util.IntKeyMap;
import org.jboss.remoting3.ReplyException;
import org.jboss.remoting3.ServiceNotFoundException;
import org.jboss.remoting3.ServiceOpenException;
import org.jboss.remoting3.ServiceURI;
import org.jboss.remoting3.spi.SpiUtils;
import org.xnio.Buffers;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.Pool;
import org.jboss.logging.Logger;

final class RemoteMessageHandler extends AbstractMessageHandler implements org.xnio.channels.MessageHandler {

    private final RemoteConnection connection;
    private final RemoteConnectionHandler remoteConnectionHandler;

    private static final Logger log = Loggers.main;

    RemoteMessageHandler(final RemoteConnectionHandler remoteConnectionHandler, final RemoteConnection connection) {
        super(connection);
        this.remoteConnectionHandler = remoteConnectionHandler;
        this.connection = connection;
    }

    public void handleMessage(final ByteBuffer buffer) {
        final byte cmd = buffer.get();
        final RemoteConnectionHandler connectionHandler = remoteConnectionHandler;
        switch (cmd) {
            case RemoteProtocol.SERVICE_REQUEST: {
                final int id = buffer.getInt();
                final String serviceType = Buffers.getModifiedUtf8Z(buffer);
                final String groupName = Buffers.getModifiedUtf8Z(buffer);
                final Pool<ByteBuffer> bufferPool = connectionHandler.getBufferPool();
                final ByteInput input = Marshalling.createByteInput(buffer);
                final OptionMap optionMap;
                final ByteBuffer outBuf = bufferPool.allocate();
                try {
                    try {
                        final Unmarshaller unmarshaller = remoteConnectionHandler.getMarshallerFactory().createUnmarshaller(remoteConnectionHandler.getMarshallingConfiguration());
                        try {
                            unmarshaller.start(input);
                            optionMap = (OptionMap) unmarshaller.readObject();
                            unmarshaller.finish();
                        } finally {
                            IoUtils.safeClose(unmarshaller);
                        }
                    } catch (Exception e) {
                        log.error("Failed to unmarshall service request option map: %s", e);
                        outBuf.putInt(RemoteConnectionHandler.LENGTH_PLACEHOLDER);
                        outBuf.put(RemoteProtocol.SERVICE_ERROR);
                        outBuf.putInt(id);
                        outBuf.flip();
                        try {
                            connection.sendBlocking(outBuf, true);
                        } catch (IOException e1) {
                            // the channel has suddenly failed
                            log.trace("Send failed: %s", e);
                        }
                        return;
                    }
                    final LocalRequestHandler handler;
                    handler = connectionHandler.getConnectionContext().openService(serviceType, groupName);
                    outBuf.putInt(RemoteConnectionHandler.LENGTH_PLACEHOLDER);
                    if (handler == null) {
                        // no matching service found
                        outBuf.put(RemoteProtocol.SERVICE_NOT_FOUND);
                    } else {
                        // service opened locally, now register the success
                        final InboundClient inboundClient = new InboundClient(connectionHandler, handler, id);
                        final IntKeyMap<InboundClient> inboundClients = connectionHandler.getInboundClients();
                        synchronized (inboundClients) {
                            inboundClients.put(id, inboundClient);
                        }
                        outBuf.put(RemoteProtocol.SERVICE_CLIENT_OPENED);
                    }
                    outBuf.putInt(id);
                    outBuf.flip();
                    try {
                        connection.sendBlocking(outBuf, true);
                    } catch (IOException e) {
                        // the channel has suddenly failed
                        log.trace("Send failed: %s", e);
                    }
                    return;
                } finally {
                    bufferPool.free(outBuf);
                }
                // not reached
            }
            case RemoteProtocol.SERVICE_NOT_FOUND: {
                final int id = buffer.getInt();
                final OutboundClient client;
                final IntKeyMap<OutboundClient> outboundClients = connectionHandler.getOutboundClients();
                synchronized (outboundClients) {
                    client = outboundClients.remove(id);
                }
                if (client == null) {
                    log.trace("Received service-not-found for unknown client %d", Integer.valueOf(id));
                    return;
                }
                synchronized (client) {
                    // todo assert client state == waiting
                    client.getResult().setException(new ServiceNotFoundException(ServiceURI.create(client.getServiceType(), client.getGroupName(), null)));
                    client.setState(OutboundClient.State.CLOSED);
                }
                return;
            }
            case RemoteProtocol.SERVICE_ERROR: {
                final int id = buffer.getInt();
                final OutboundClient client;
                final IntKeyMap<OutboundClient> outboundClients = connectionHandler.getOutboundClients();
                synchronized (outboundClients) {
                    client = outboundClients.remove(id);
                }
                if (client == null) {
                    log.trace("Received service-error for unknown client %d", Integer.valueOf(id));
                    return;
                }
                synchronized (client) {
                    // todo assert client state == waiting
                    client.getResult().setException(new ServiceOpenException("Remote side failed to open service"));
                    client.setState(OutboundClient.State.CLOSED);
                }
                return;
            }
            case RemoteProtocol.SERVICE_CLIENT_OPENED: {
                final int id = buffer.getInt();
                final OutboundClient client;
                final IntKeyMap<OutboundClient> outboundClients = connectionHandler.getOutboundClients();
                synchronized (outboundClients) {
                    client = outboundClients.get(id);
                }
                if (client == null) {
                    log.trace("Received service-client-opened for unknown client %d", Integer.valueOf(id));
                    return;
                }
                synchronized (client) {
                    // todo assert client state == waiting
                    client.setState(OutboundClient.State.ESTABLISHED);
                    client.setResult(new OutboundRequestHandler(client));
                }
                return;
            }
            case RemoteProtocol.CHANNEL_CLOSE: {
                final int id = buffer.getInt();

                final InboundClient client;
                final IntKeyMap<InboundClient> inboundClients = connectionHandler.getInboundClients();
                synchronized (inboundClients) {
                    client = inboundClients.remove(id);
                }
                if (client == null) {
                    log.trace("Received client-closed for unknown client %d", Integer.valueOf(id));
                    return;
                }
                synchronized (client) {
                    IoUtils.safeClose(client.getHandler());
                }
                return;
            }
            case RemoteProtocol.CLIENT_ASYNC_CLOSE: {
                final int id = buffer.getInt();

                final OutboundClient client;
                final IntKeyMap<OutboundClient> outboundClients = connectionHandler.getOutboundClients();
                synchronized (outboundClients) {
                    client = outboundClients.remove(id);
                }
                if (client == null) {
                    log.trace("Received client-closed for unknown client %d", Integer.valueOf(id));
                    return;
                }
                synchronized (client) {
                    IoUtils.safeClose(client.getRequestHandler());
                }
                return;
            }
            case RemoteProtocol.REQUEST: {
                final int rid = buffer.getInt();
                final byte flags = buffer.get();
                final InboundRequest inboundRequest;
                final NioByteInput byteInput;
                final IntKeyMap<InboundRequest> inboundRequests = connectionHandler.getInboundRequests();
                final int cid;
                boolean start = false;
                synchronized (inboundRequests) {
                    if ((flags & RemoteProtocol.MSG_FLAG_FIRST) != 0) {
                        cid = buffer.getInt();
                        inboundRequest = new InboundRequest(connectionHandler, rid);
                        start = true;
                        // todo - check for duplicate
                        inboundRequests.put(rid, inboundRequest);
                        log.trace("Received first request message %s for %s", buffer, inboundRequest);
                    } else {
                        cid = 0;
                        inboundRequest = inboundRequests.get(rid);
                        log.trace("Received subsequent request message %s for %s", buffer, inboundRequest);
                    }
                    if (inboundRequest == null) {
                        log.trace("Received request for unknown request ID %d", Integer.valueOf(rid));
                    }
                }
                synchronized (inboundRequest) {
                    if (start) {
                        connectionHandler.getConnectionContext().getConnectionProviderContext().getExecutor().execute(new InboundRequestTask(connectionHandler, inboundRequest, rid, cid));
                    }
                    byteInput = inboundRequest.getByteInput();
                }
                byteInput.push(buffer);
                return;
            }
            case RemoteProtocol.REQUEST_ABORT: {
                final int rid = buffer.getInt();
                final InboundRequest inboundRequest;
                final IntKeyMap<InboundRequest> inboundRequests = connectionHandler.getInboundRequests();
                synchronized (inboundRequests) {
                    inboundRequest = inboundRequests.remove(rid);
                }
                if (inboundRequest == null) {
                    log.trace("Received request-abort for unknown request ID %d", Integer.valueOf(rid));
                    return;
                }
                synchronized (inboundRequest) {
                    // as long as the last message hasn't been received yet, this will disrupt the request and prevent a reply
                    inboundRequest.getReplyHandler().setDone();
                    inboundRequest.getByteInput().pushException(new InterruptedIOException("Request aborted"));
                }
                return;
            }
            case RemoteProtocol.REQUEST_ACK_CHUNK: {
                final int rid = buffer.getInt();
                final OutboundRequest outboundRequest;
                final IntKeyMap<OutboundRequest> outboundRequests = connectionHandler.getOutboundRequests();
                synchronized (outboundRequests) {
                    outboundRequest = outboundRequests.get(rid);
                }
                if (outboundRequest == null) {
                    log.trace("Received request-ack-chunk for unknown request ID %d", Integer.valueOf(rid));
                    return;
                }
                synchronized (outboundRequest) {
                    outboundRequest.ack();
                }
                return;
            }
            case RemoteProtocol.REPLY: {
                final int rid = buffer.getInt();
                final byte flags = buffer.get();
                final OutboundRequest outboundRequest;
                final NioByteInput byteInput;
                final IntKeyMap<OutboundRequest> outboundRequests = connectionHandler.getOutboundRequests();
                synchronized (outboundRequests) {
                    outboundRequest = outboundRequests.get(rid);
                }
                if (outboundRequest == null) {
                    log.trace("Received reply for unknown request ID %d", Integer.valueOf(rid));
                    return;
                }
                synchronized (outboundRequest) {
                    if ((flags & RemoteProtocol.MSG_FLAG_FIRST) != 0) {
                        log.trace("Received first reply message %s for %s", buffer, outboundRequest);
                        // todo - check for duplicate
                        outboundRequest.setByteInput(byteInput = new NioByteInput(new InboundReplyInputHandler(outboundRequest, rid)));
                        connectionHandler.getConnectionContext().getConnectionProviderContext().getExecutor().execute(new InboundReplyTask(connectionHandler, outboundRequest));
                    } else {
                        log.trace("Received subsequent reply message %s for %s", buffer, outboundRequest);
                        byteInput = outboundRequest.getByteInput();
                    }
                }
                byteInput.push(buffer);
                return;
            }
            case RemoteProtocol.REPLY_ACK_CHUNK: {
                final int rid = buffer.getInt();
                final InboundRequest inboundRequest;
                final IntKeyMap<InboundRequest> inboundRequests = connectionHandler.getInboundRequests();
                synchronized (inboundRequests) {
                    inboundRequest = inboundRequests.get(rid);
                }
                if (inboundRequest == null) {
                    log.trace("Received reply-ack-chunk for unknown request ID %d", Integer.valueOf(rid));
                    return;
                }
                synchronized (inboundRequest) {
                    inboundRequest.ack();
                }
                return;
            }
            case RemoteProtocol.REPLY_EXCEPTION: {
                final int rid = buffer.getInt();
                final byte flags = buffer.get();
                final OutboundRequest outboundRequest;
                final NioByteInput byteInput;
                final IntKeyMap<OutboundRequest> outboundRequests = connectionHandler.getOutboundRequests();
                synchronized (outboundRequests) {
                    outboundRequest = outboundRequests.get(rid);
                }
                if (outboundRequest == null) {
                    log.trace("Received reply-exception for unknown request ID %d", Integer.valueOf(rid));
                    return;
                }
                synchronized (outboundRequest) {
                    if ((flags & RemoteProtocol.MSG_FLAG_FIRST) != 0) {
                        // todo - check for duplicate
                        outboundRequest.setByteInput(byteInput = new NioByteInput(new InboundReplyInputHandler(outboundRequest, rid)));
                        connectionHandler.getConnectionContext().getConnectionProviderContext().getExecutor().execute(new InboundReplyExceptionTask(connectionHandler, outboundRequest));
                    } else {
                        byteInput = outboundRequest.getByteInput();
                    }
                }
                byteInput.push(buffer);
                return;
            }
            case RemoteProtocol.REPLY_EXCEPTION_ABORT: {
                final int rid = buffer.getInt();
                final OutboundRequest outboundRequest;
                final IntKeyMap<OutboundRequest> outboundRequests = connectionHandler.getOutboundRequests();
                synchronized (outboundRequests) {
                    outboundRequest = outboundRequests.get(rid);
                }
                if (outboundRequest == null) {
                    log.warn("Received reply-exception-abort for unknown request ID %d", Integer.valueOf(rid));
                    return;
                }
                final NioByteInput byteInput;
                final LocalReplyHandler replyHandler;
                synchronized (outboundRequest) {
                    byteInput = outboundRequest.getByteInput();
                    replyHandler = outboundRequest.getInboundReplyHandler();
                }
                final ReplyException re = new ReplyException("Reply exception was aborted");
                if (byteInput != null) {
                    byteInput.pushException(re);
                }
                if (replyHandler != null) {
                    SpiUtils.safeHandleException(replyHandler, re);
                }
                return;
            }
            case RemoteProtocol.ALIVE: {
                // todo - mark the time
                return;
            }
            case RemoteProtocol.STREAM_ACK: {
                final int sid = buffer.getInt();
                final IntKeyMap<OutboundStream> outboundStreams = connectionHandler.getOutboundStreams();
                final OutboundStream outboundStream;
                synchronized (outboundStreams) {
                    outboundStream = outboundStreams.get(sid);
                }
                if (outboundStream == null) {
                    log.warn("Received stream-ack for unknown stream ID %d", Integer.valueOf(sid));
                    return;
                }
                outboundStream.ack();
                return;
            }
            case RemoteProtocol.STREAM_ASYNC_CLOSE: {
                final int sid = buffer.getInt();
                final IntKeyMap<OutboundStream> outboundStreams = connectionHandler.getOutboundStreams();
                final OutboundStream outboundStream;
                synchronized (outboundStreams) {
                    outboundStream = outboundStreams.get(sid);
                }
                if (outboundStream == null) {
                    log.warn("Received stream-ack for unknown stream ID %d", Integer.valueOf(sid));
                    return;
                }
                outboundStream.asyncClose();
                return;
            }
            case RemoteProtocol.STREAM_ASYNC_EXCEPTION: {
                final int sid = buffer.getInt();
                final IntKeyMap<OutboundStream> outboundStreams = connectionHandler.getOutboundStreams();
                final OutboundStream outboundStream;
                synchronized (outboundStreams) {
                    outboundStream = outboundStreams.get(sid);
                }
                if (outboundStream == null) {
                    log.warn("Received stream-async-exception for unknown stream ID %d", Integer.valueOf(sid));
                    return;
                }
                outboundStream.asyncException();
                return;
            }
            case RemoteProtocol.STREAM_ASYNC_START: {
                final int sid = buffer.getInt();
                final IntKeyMap<OutboundStream> outboundStreams = connectionHandler.getOutboundStreams();
                final OutboundStream outboundStream;
                synchronized (outboundStreams) {
                    outboundStream = outboundStreams.get(sid);
                }
                if (outboundStream == null) {
                    log.warn("Received stream-async-start for unknown stream ID %d", Integer.valueOf(sid));
                    return;
                }
                outboundStream.asyncStart();
                return;
            }
            case RemoteProtocol.STREAM_CLOSE: {
                final int sid = buffer.getInt();
                final IntKeyMap<InboundStream> inboundStreams = connectionHandler.getInboundStreams();
                final InboundStream inboundStream;
                synchronized (inboundStreams) {
                    inboundStream = inboundStreams.get(sid);
                }
                if (inboundStream == null) {
                    log.warn("Received stream-close for unknown stream ID %d", Integer.valueOf(sid));
                    return;
                }
                inboundStream.getReceiver().pushEof();
                return;
            }
            case RemoteProtocol.STREAM_DATA: {
                final int sid = buffer.getInt();
                final IntKeyMap<InboundStream> inboundStreams = connectionHandler.getInboundStreams();
                final InboundStream inboundStream;
                synchronized (inboundStreams) {
                    inboundStream = inboundStreams.get(sid);
                }
                if (inboundStream == null) {
                    log.warn("Received stream-data for unknown stream ID %d", Integer.valueOf(sid));
                    return;
                }
                inboundStream.getReceiver().push(buffer);
                return;
            }
            case RemoteProtocol.STREAM_EXCEPTION: {
                final int sid = buffer.getInt();
                final IntKeyMap<InboundStream> inboundStreams = connectionHandler.getInboundStreams();
                final InboundStream inboundStream;
                synchronized (inboundStreams) {
                    inboundStream = inboundStreams.get(sid);
                }
                if (inboundStream == null) {
                    log.warn("Received stream-exception for unknown stream ID %d", Integer.valueOf(sid));
                    return;
                }
                inboundStream.getReceiver().pushException();
                return;
            }
            default: {
                log.error("Received invalid packet type on %s, closing", connectionHandler);
                IoUtils.safeClose(connectionHandler);
            }
        }
    }
}
