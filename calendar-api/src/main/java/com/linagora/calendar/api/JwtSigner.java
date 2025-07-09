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

package com.linagora.calendar.api;


import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.Map;

import org.apache.james.metrics.api.MetricFactory;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.util.io.pem.PemReader;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.lang.Collections;
import io.jsonwebtoken.security.SecureDigestAlgorithm;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class JwtSigner {
    public static class Factory {
        public static PrivateKey loadPrivateKey(Path pemFilePath) throws Exception {
            // Read PEM file content
            try (PEMParser pemParser = new PEMParser(new PemReader(Files.newBufferedReader(pemFilePath)))) {
                Object o = pemParser.readObject();
                if (o instanceof PEMKeyPair keyPair) {
                    return new JcaPEMKeyConverter().getPrivateKey(keyPair.getPrivateKeyInfo());
                }
                if (o instanceof PrivateKeyInfo priveKey) {
                    return new JcaPEMKeyConverter().getPrivateKey(priveKey);
                }
                throw new RuntimeException("Invalid key of class " + o.getClass());
            }
        }

        private final Duration tokenValidity;
        private final Path privateKeyPath;
        private final Clock clock;
        private final MetricFactory metricFactory;

        public Factory(Clock clock, Duration tokenValidity, Path privateKeyPath, MetricFactory metricFactory) {
            this.clock = clock;
            this.tokenValidity = tokenValidity;
            this.privateKeyPath = privateKeyPath;
            this.metricFactory = metricFactory;
        }

        public JwtSigner instantiate() throws Exception {
            PrivateKey key = loadPrivateKey(privateKeyPath);
            return new JwtSigner(clock, tokenValidity, key, metricFactory);
        }
    }

    private static final String SUB_CLAIM = "sub";

    private final Clock clock;
    private final Duration tokenValidity;
    private final Key key;
    private final MetricFactory metricFactory;

    public JwtSigner(Clock clock, Duration tokenValidity, Key key, MetricFactory metricFactory) {
        this.clock = clock;
        this.tokenValidity = tokenValidity;
        this.key = key;
        this.metricFactory = metricFactory;
    }

    public Mono<String> generate(String sub) {
        return generate(ImmutableMap.of(SUB_CLAIM, sub));
    }

    public Mono<String> generate(Map<String, Object> claims) {
        Preconditions.checkArgument(!Collections.isEmpty(claims), "claims can't be empty");
        return Mono.from(metricFactory.decoratePublisherWithTimerMetric("jwt-signer", Mono.fromCallable(() -> Jwts.builder()
                .header().add("typ", "JWT").and()
                .claims(claims)
                .signWith(key, (SecureDigestAlgorithm) Jwts.SIG.RS256)
                .issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(clock.instant().plus(tokenValidity)))
                .compact())))
            .subscribeOn(Schedulers.parallel());
    }
}
