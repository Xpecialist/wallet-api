package com.example.wallet.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * A request whose first body bytes were read ahead by a filter ({@link #prefix}); the controller gets the same bytes
 * back, followed by whatever was not read yet ({@link #rest}, empty when the whole body was read). Every call to
 * {@code getInputStream()} starts again at the beginning of the prefix.
 */
final class ReplayableRequest extends HttpServletRequestWrapper {
    final byte[] prefix;
    private final InputStream rest;

    /**
     * Reads up to {@code limit} bytes of the body.
     *
     * @param request the request
     * @param limit   most bytes to read ahead
     * @throws IOException if reading fails
     */
    ReplayableRequest(HttpServletRequest request, int limit) throws IOException {
        super(request);
        InputStream in = request.getInputStream();
        this.prefix = in.readNBytes(limit);
        this.rest = in;
    }


    @Override
    public ServletInputStream getInputStream() {
        InputStream replay = new SequenceInputStream(new ByteArrayInputStream(prefix), rest);
        return new ServletInputStream() {
            private boolean finished;

            @Override
            public int read() throws IOException {
                int b = replay.read();
                if (b < 0) finished = true;
                return b;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                int n = replay.read(buf, off, len);
                if (n < 0) finished = true;
                return n;
            }

            @Override
            public boolean isFinished() { return finished; }

            @Override
            public boolean isReady() { return true; }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new IllegalStateException("async reads are not supported");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        String enc = getCharacterEncoding();
        Charset cs = enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
        return new BufferedReader(new InputStreamReader(getInputStream(), cs));
    }
}
