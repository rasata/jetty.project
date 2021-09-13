//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.internal.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.ErrorCode;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.FrameType;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The HTTP/3 protocol parser.</p>
 * <p>This parser makes use of the {@link HeaderParser} and of
 * {@link BodyParser}s to parse HTTP/3 frames.</p>
 */
public class Parser
{
    private static final Logger LOG = LoggerFactory.getLogger(Parser.class);

    private final HeaderParser headerParser;
    private final BodyParser[] bodyParsers = new BodyParser[FrameType.maxType()];
    private final BodyParser unknownBodyParser;
    private final long streamId;
    private final Listener listener;
    private State state = State.HEADER;

    public Parser(long streamId, Listener listener, QpackDecoder decoder)
    {
        this.streamId = streamId;
        this.headerParser = new HeaderParser();
        this.bodyParsers[FrameType.DATA.type()] = new DataBodyParser(streamId, headerParser, listener);
        this.bodyParsers[FrameType.HEADERS.type()] = new HeadersBodyParser(streamId, headerParser, listener, decoder);
        this.bodyParsers[FrameType.CANCEL_PUSH.type()] = new CancelPushBodyParser(headerParser, listener);
        this.bodyParsers[FrameType.SETTINGS.type()] = new SettingsBodyParser(headerParser, listener);
        this.bodyParsers[FrameType.PUSH_PROMISE.type()] = new PushPromiseBodyParser(headerParser, listener);
        this.bodyParsers[FrameType.GOAWAY.type()] = new GoAwayBodyParser(headerParser, listener);
        this.bodyParsers[FrameType.MAX_PUSH_ID.type()] = new MaxPushIdBodyParser(headerParser, listener);
        this.unknownBodyParser = new UnknownBodyParser(headerParser, listener);
        this.listener = listener;
    }

    private void reset()
    {
        headerParser.reset();
        state = State.HEADER;
    }

    /**
     * <p>Parses the given {@code buffer} bytes and emit events to a {@link Listener}.</p>
     *
     * @param buffer the buffer to parse
     */
    public void parse(ByteBuffer buffer)
    {
        try
        {
            while (true)
            {
                switch (state)
                {
                    case HEADER:
                    {
                        if (headerParser.parse(buffer))
                        {
                            state = State.BODY;
                            break;
                        }
                        return;
                    }
                    case BODY:
                    {
                        BodyParser bodyParser = null;
                        int frameType = headerParser.getFrameType();
                        if (frameType >= 0 && frameType < bodyParsers.length)
                            bodyParser = bodyParsers[frameType];

                        if (bodyParser == null)
                        {
                            // Unknown frame types must be ignored.
                            if (LOG.isDebugEnabled())
                                LOG.debug("Ignoring unknown frame type {}", Integer.toHexString(frameType));
                            if (!unknownBodyParser.parse(buffer))
                                return;
                            reset();
                        }
                        else
                        {
                            if (headerParser.getFrameLength() == 0)
                            {
                                bodyParser.emptyBody(buffer);
                            }
                            else
                            {
                                if (!bodyParser.parse(buffer))
                                    return;
                            }
                            if (LOG.isDebugEnabled())
                                LOG.debug("Parsed {} frame body from {}", FrameType.from(frameType), buffer);
                            reset();
                        }
                        break;
                    }
                    default:
                    {
                        throw new IllegalStateException();
                    }
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Parse failed", x);
            buffer.clear();
            connectionFailure(buffer, ErrorCode.INTERNAL_ERROR.code(), "parser_error");
        }
    }

    private void connectionFailure(ByteBuffer buffer, int error, String reason)
    {
        unknownBodyParser.sessionFailure(buffer, error, reason);
    }

    public interface Listener
    {
        public default void onHeaders(HeadersFrame frame)
        {
        }

        public default void onData(DataFrame frame)
        {
        }

        public default void onStreamFailure(long streamId, int error, String reason)
        {
        }

        public default void onSessionFailure(int error, String reason)
        {
        }
    }

    private enum State
    {
        HEADER, BODY
    }
}