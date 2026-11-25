/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  https://www.gnu.org/licenses/agpl-3.0.en.html                   *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                                                   *
 ********************************************************************/

package com.linagora.calendar.restapi.routes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostMultipartRequestDecoder;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.EndOfDataDecoderException;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.util.ReferenceCountUtil;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.server.HttpServerRequest;

/**
 * Decodes a multipart request with a size limit per kind of part, enforced while each part is received: reactor-netty's
 * {@code receiveForm} applies a single limit to every part, so ordinary fields could grow up to the file limit.
 */
final class BoundedMultipartForm {

    /**
     * A part could not be decoded, for instance because it exceeds its limit.
     */
    static final class PartDecodingException extends DecoderException {
        private final boolean filePart;

        PartDecodingException(boolean filePart, DecoderException cause) {
            super(cause);
            this.filePart = filePart;
        }

        boolean filePart() {
            return filePart;
        }
    }

    /**
     * Gives the file parts and the other fields their own limit.
     */
    static final class PartBoundingDataFactory extends DefaultHttpDataFactory {
        private final long fieldMaxSize;

        PartBoundingDataFactory(long fileMaxSize, long fieldMaxSize) {
            super(MINSIZE);
            setMaxLimit(fileMaxSize);
            this.fieldMaxSize = fieldMaxSize;
        }

        @Override
        public Attribute createAttribute(HttpRequest request, String name) {
            return boundField(super.createAttribute(request, name));
        }

        @Override
        public Attribute createAttribute(HttpRequest request, String name, long definedSize) {
            return boundField(super.createAttribute(request, name, definedSize));
        }

        /**
         * Also creates the header parameters of every part, such as its file name: bounded before the value is set, they
         * are checked against the field limit and not the file one.
         */
        @Override
        public Attribute createAttribute(HttpRequest request, String name, String value) {
            Attribute attribute = createAttribute(request, name);
            try {
                attribute.setValue(value);
            } catch (IOException e) {
                throw new IllegalArgumentException("Attribute bigger than maxSize allowed", e);
            }
            return attribute;
        }

        private Attribute boundField(Attribute attribute) {
            attribute.setMaxSize(fieldMaxSize);
            return attribute;
        }
    }

    /**
     * Serializes the decoder use: parts are decoded on a worker thread while the request cleanup may run on another one.
     */
    private static final class SerializedDecoder {
        private final HttpPostMultipartRequestDecoder decoder;
        private boolean destroyed;

        SerializedDecoder(PartBoundingDataFactory factory, HttpRequest request) {
            this.decoder = new HttpPostMultipartRequestDecoder(factory, request);
        }

        /**
         * Decodes {@code content}, then releases it: the decoder copies the bytes it is offered.
         */
        synchronized List<InterfaceHttpData> offerAndRelease(HttpContent content) {
            try {
                if (destroyed) {
                    return List.of();
                }
                offer(content);
                return completedParts();
            } finally {
                ReferenceCountUtil.release(content);
            }
        }

        synchronized void destroy() {
            destroyed = true;
            decoder.destroy();
        }

        private void offer(HttpContent content) {
            try {
                decoder.offer(content);
            } catch (DecoderException e) {
                throw new PartDecodingException(decoder.currentPartialHttpData() instanceof FileUpload, e);
            }
        }

        private List<InterfaceHttpData> completedParts() {
            List<InterfaceHttpData> completedParts = new ArrayList<>();
            try {
                while (decoder.hasNext()) {
                    completedParts.add(decoder.next());
                }
            } catch (EndOfDataDecoderException e) {
                // The last part is decoded.
            }
            return completedParts;
        }
    }

    private BoundedMultipartForm() {
    }

    /**
     * Applies {@code partsHandler} to the completed parts of the request, then releases them and their temporary files.
     */
    static <T> Mono<T> decode(HttpServerRequest request, PartBoundingDataFactory factory,
                              Function<Flux<InterfaceHttpData>, Mono<T>> partsHandler) {
        return Mono.using(() -> new SerializedDecoder(factory, asNettyRequest(request)),
            decoder -> partsHandler.apply(parts(request, decoder)),
            SerializedDecoder::destroy);
    }

    private static HttpRequest asNettyRequest(HttpServerRequest request) {
        return new DefaultHttpRequest(request.version(), request.method(), request.uri(), request.requestHeaders());
    }

    /**
     * Decodes the received chunks in order on a worker thread, as the decoder writes large parts to disk. The chunks are
     * retained until decoded, and released if the decoding stops before reaching them.
     */
    private static Flux<InterfaceHttpData> parts(HttpServerRequest request, SerializedDecoder decoder) {
        return Flux.<HttpContent>concat(
                request.receive().retain().map(DefaultHttpContent::new),
                Mono.just(LastHttpContent.EMPTY_LAST_CONTENT))
            .publishOn(Schedulers.boundedElastic())
            .map(decoder::offerAndRelease)
            .doOnDiscard(HttpContent.class, ReferenceCountUtil::release)
            .flatMapIterable(Function.identity());
    }
}
