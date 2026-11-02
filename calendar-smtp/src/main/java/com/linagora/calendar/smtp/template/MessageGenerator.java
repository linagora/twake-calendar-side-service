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

package com.linagora.calendar.smtp.template;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import jakarta.mail.internet.InternetAddress;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.filesystem.api.FileSystem;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.BodyPart;
import org.apache.james.mime4j.message.BodyPartBuilder;
import org.apache.james.mime4j.message.MultipartBuilder;
import org.apache.james.mime4j.stream.NameValuePair;
import org.apache.james.mime4j.stream.RawField;

import com.github.fge.lambdas.Throwing;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.smtp.i18n.I18NTranslator;
import com.linagora.calendar.smtp.i18n.I18NTranslator.PropertiesI18NTranslator;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

import reactor.core.publisher.Mono;

public class MessageGenerator {
    public interface Factory {
        MessageGenerator forLocalizedFeature(Language language, TemplateType templateType) throws IOException;

        class Default implements Factory {

            private final MailTemplateConfiguration configuration;
            private final FileSystem fileSystem;
            private final OpenPaaSUserDAO userDAO;

            public Default(MailTemplateConfiguration configuration,
                           FileSystem fileSystem,
                           OpenPaaSUserDAO userDAO) {
                this.configuration = configuration;
                this.fileSystem = fileSystem;
                this.userDAO = userDAO;
            }

            public MessageGenerator forLocalizedFeature(Language language, TemplateType templateType) throws IOException {
                return forTranslator(translatorFactory(templateType).forLocale(language.locale()), templateType);
            }

            MessageGenerator forTranslator(I18NTranslator i18NTranslator, TemplateType templateType) throws IOException {
                File templateFileDirectory = templateDirectory(templateType);
                HtmlBodyRenderer htmlBodyRenderer = HtmlBodyRenderer.forPath(templateFileDirectory.getAbsolutePath());
                Optional<TextBodyRenderer> textBodyRenderer = TextBodyRenderer.forPath(templateFileDirectory.getAbsolutePath());
                return new MessageGenerator(i18NTranslator, htmlBodyRenderer, textBodyRenderer, userDAO);
            }

            PropertiesI18NTranslator.Factory translatorFactory(TemplateType templateType) throws IOException {
                File translationsFileDirectory = new File(templateDirectory(templateType), "translations");
                return new PropertiesI18NTranslator.Factory(translationsFileDirectory);
            }

            private File templateDirectory(TemplateType templateType) throws IOException {
                Path templatePath = Paths.get(configuration.templateLocationPath(), templateType.value());
                File templateFileDirectory = fileSystem.getFile(templatePath.toString());
                if (!templateFileDirectory.exists() || !templateFileDirectory.isDirectory()) {
                    throw new FileNotFoundException("Template directory not found: " + templateFileDirectory.getAbsolutePath());
                }
                return templateFileDirectory;
            }

            public Factory cached() {
                return new Cached(this);
            }
        }

        record CacheEntry(Locale locale, TemplateType templateType) {

        }

        /**
         * The language is user input: generators are cached by the locale their translator resolves to, English for any
         * language without translations. The number of entries is thus bounded by the translation files.
         */
        class Cached implements Factory {
            private static final long MAXIMUM_SIZE = 1000;

            private final Default factory;
            private final Cache<TemplateType, PropertiesI18NTranslator.Factory> translatorFactories;
            private final Cache<CacheEntry, MessageGenerator> loadingCache;

            public Cached(Default factory) {
                this.factory = factory;
                this.translatorFactories = CacheBuilder.newBuilder().maximumSize(MAXIMUM_SIZE).build();
                this.loadingCache = CacheBuilder.newBuilder().maximumSize(MAXIMUM_SIZE).build();
            }

            @Override
            public MessageGenerator forLocalizedFeature(Language language, TemplateType templateType) throws IOException {
                I18NTranslator translator = get(translatorFactories, templateType, () -> factory.translatorFactory(templateType))
                    .forLocale(language.locale());
                return get(loadingCache, new CacheEntry(translator.associatedLocale(), templateType),
                    () -> factory.forTranslator(translator, templateType));
            }

            @VisibleForTesting
            long cacheSize() {
                return loadingCache.size();
            }

            private static <K, V> V get(Cache<K, V> cache, K key, Callable<V> loader) throws IOException {
                try {
                    return cache.get(key, loader);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof IOException ioException) {
                        throw ioException;
                    }
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public static Factory.Default factory(MailTemplateConfiguration configuration,
                                          FileSystem fileSystem,
                                          OpenPaaSUserDAO userDAO) {
        return new Factory.Default(configuration, fileSystem, userDAO);
    }

    private static final String SUBJECT_KEY_NAME = "mail_subject";
    private static final String TRANSLATOR_FUNCTION_NAME = "translator";
    private static final String MULTIPART_MIXED = "mixed";
    private static final String MULTIPART_RELATED = "related";
    private static final String MULTIPART_ALTERNATIVE = "alternative";
    private static final String CONTENT_LANGUAGE = "Content-Language";

    private final HtmlBodyRenderer htmlBodyRenderer;
    private final Optional<TextBodyRenderer> textBodyRenderer;
    private final I18NTranslator i18nTranslator;
    private final OpenPaaSUserDAO userDAO;

    public MessageGenerator(I18NTranslator i18nTranslator,
                            HtmlBodyRenderer htmlBodyRenderer,
                            Optional<TextBodyRenderer> textBodyRenderer,
                            OpenPaaSUserDAO userDAO) {
        this.i18nTranslator = i18nTranslator;
        this.htmlBodyRenderer = htmlBodyRenderer;
        this.textBodyRenderer = textBodyRenderer;
        this.userDAO = userDAO;
    }

    public Mono<Message> generate(Username recipient, MailAddress fromAddress, Map<String, Object> scopedVariable) {
        return generate(recipient, fromAddress, scopedVariable, List.of());
    }

    public Mono<Message> generate(Username recipient, MailAddress fromAddress, Map<String, Object> scopedVariable, List<MimeAttachment> mimeAttachments) {
        return resolveInternetAddress(Username.fromMailAddress(fromAddress))
            .flatMap(fromAsInternetAddress -> generate(recipient, fromAsInternetAddress, scopedVariable, mimeAttachments));
    }

    public Mono<Message> generate(Username recipient, InternetAddress fromAddress, Map<String, Object> scopedVariable, List<MimeAttachment> mimeAttachments) {
        return resolveInternetAddress(recipient)
            .flatMap(recipientAsInternetAddress -> generate(recipientAsInternetAddress, fromAddress, scopedVariable, mimeAttachments));
    }

    public Mono<InternetAddress> resolveInternetAddress(Username username) {
        return userDAO.retrieve(username)
            .map(OpenPaaSUser::fullName)
            .flatMap(fullName -> Mono.fromCallable(() -> new InternetAddress(username.asString(),
                StringUtils.defaultIfEmpty(fullName, username.asString()))))
            .switchIfEmpty(Mono.fromCallable(() -> new InternetAddress(username.asString())));
    }

    public Mono<Message> generate(InternetAddress recipient, InternetAddress fromAddress, Map<String, Object> scopedVariable, List<MimeAttachment> mimeAttachments) {
        return Mono.fromCallable(() -> {
            Map<String, Object> scopedVariableFinal = ImmutableMap.<String, Object>builder()
                .putAll(scopedVariable)
                .put(TRANSLATOR_FUNCTION_NAME, i18nTranslator)
                .build();

            BodyPart htmlBodyPart = textualBodyPart(htmlBodyRenderer.render(scopedVariableFinal), "html", "base64");
            Optional<BodyPart> textBodyPart = textBodyRenderer
                .map(renderer -> renderer.render(scopedVariableFinal))
                .map(Throwing.function((String text) -> textualBodyPart(text, "plain", "quoted-printable")).sneakyThrow());

            MultipartBuilder multipartBuilder = buildMultipart(htmlBodyPart, textBodyPart, mimeAttachments);

            MailAddress fromAsMailAddress = new MailAddress(fromAddress.getAddress());

            return Message.Builder.of()
                .setMessageId("<" + UUID.randomUUID() + "@" + Optional.of(fromAsMailAddress).map(MailAddress::getDomain).map(Domain::asString).orElse("") + ">")
                .setDate(new Date())
                .setSubject(subject(scopedVariableFinal))
                .setBody(multipartBuilder.build())
                .setFrom(fromAddress.toString())
                .setTo(recipient.toString())
                .build();
        });
    }

    private BodyPart textualBodyPart(String content, String subtype, String transferEncoding) throws IOException {
        BodyPartBuilder bodyPart = BodyPartBuilder.create()
            .setContentTransferEncoding(transferEncoding)
            .setBody(content, subtype, StandardCharsets.UTF_8);
        bodyPart.addField(new RawField(CONTENT_LANGUAGE, i18nTranslator.associatedLocale().getLanguage()));
        return bodyPart.build();
    }

    /**
     * Builds multipart/mixed[ multipart/alternative[ text/plain, multipart/related[ text/html, inline attachments ] ], attachments ].
     * The alternative level is omitted when no text template is defined, the related level when there is no inline attachment.
     */
    private MultipartBuilder buildMultipart(BodyPart htmlBodyPart, Optional<BodyPart> textBodyPart, List<MimeAttachment> attachments) {
        List<MimeAttachment> inlineAttachments = attachments.stream()
            .filter(MimeAttachment::inline)
            .toList();

        List<MimeAttachment> nonInlineAttachments = attachments.stream()
            .filter(attachment -> !attachment.inline())
            .toList();

        BodyPart htmlContent = withInlineAttachments(htmlBodyPart, inlineAttachments);
        BodyPart content = textBodyPart
            .map(textPart -> asBodyPart(MultipartBuilder.create(MULTIPART_ALTERNATIVE)
                .addBodyPart(textPart)
                .addBodyPart(htmlContent)))
            .orElse(htmlContent);

        MultipartBuilder mixed = MultipartBuilder.create(MULTIPART_MIXED)
            .addBodyPart(content);
        nonInlineAttachments.forEach(Throwing.consumer(attachment -> mixed.addBodyPart(attachment.asBodyPart())));
        return mixed;
    }

    private BodyPart withInlineAttachments(BodyPart htmlBodyPart, List<MimeAttachment> inlineAttachments) {
        if (inlineAttachments.isEmpty()) {
            return htmlBodyPart;
        }
        MultipartBuilder related = MultipartBuilder.create(MULTIPART_RELATED)
            // RFC 2387 requires the root media type; without a start parameter the first part is the root.
            .addContentTypeParameter(new NameValuePair("type", htmlBodyPart.getMimeType()))
            .addBodyPart(htmlBodyPart);
        inlineAttachments.forEach(Throwing.consumer(attachment -> related.addBodyPart(attachment.asBodyPart())));
        return asBodyPart(related);
    }

    private BodyPart asBodyPart(MultipartBuilder multipart) {
        return BodyPartBuilder.create()
            .setBody(multipart.build())
            .build();
    }

    private String subject(Map<String, Object> scopedVariable) throws IOException {
        String subjectTemplate = i18nTranslator.get(SUBJECT_KEY_NAME);
        Preconditions.checkArgument(StringUtils.isNotBlank(subjectTemplate),
            "Subject is empty, please check your translations for key: " + SUBJECT_KEY_NAME);

        return SubjectRenderer.of(subjectTemplate).render(scopedVariable);
    }

    public I18NTranslator getI18nTranslator() {
        return i18nTranslator;
    }
}
