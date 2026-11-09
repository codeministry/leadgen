/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.llm;

import java.io.IOException;
import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;

/**
 * Makes closing a model response cancel its HTTP call before the body is closed, so a stopped
 * stream ends the request on the wire.
 *
 * <p><b>Why.</b> A chat turn is stopped, deleted or past its deadline by closing its stream from a
 * thread other than the one blocked reading it. From openai-java 4.66.0 on (openai/openai-java#1027)
 * that close shuts the OkHttp response before the reader, under the read still in flight: okio
 * throws "Unbalanced enter/exit", the socket stays open, and the model keeps generating an answer
 * nobody reads, paid for in full. {@link Call#cancel()} is OkHttp's own thread-safe way to end a
 * call from outside: it closes the socket, the blocked read fails, and the closes after it find
 * nothing left to race.
 *
 * <p>Cancelling a call whose body was read to its end changes nothing, so the calls that are not
 * streamed, the judge's and the classifier's, close as before.
 */
final class CancelOnClose implements Interceptor {

    /** Hands the interceptor to Spring AI's OkHttp client, for the sync and the async one alike. */
    static final OpenAiHttpClientBuilderCustomizer CUSTOMIZER = builder -> builder.interceptor(new CancelOnClose());

    @Override
    public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        ResponseBody body = response.body();
        if (body == null) {
            return response;
        }
        return response.newBuilder().body(new Cancelling(body, chain.call())).build();
    }

    /** The response body as it was, except that closing it cancels the call first. */
    private static final class Cancelling extends ResponseBody {

        private final ResponseBody delegate;
        private final Call call;

        Cancelling(ResponseBody delegate, Call call) {
            this.delegate = delegate;
            this.call = call;
        }

        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() {
            return delegate.contentLength();
        }

        @Override
        public BufferedSource source() {
            return delegate.source();
        }

        @Override
        public void close() {
            try {
                call.cancel();
            } finally {
                delegate.close();
            }
        }
    }
}
