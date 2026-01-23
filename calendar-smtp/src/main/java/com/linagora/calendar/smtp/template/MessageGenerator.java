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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import jakarta.mail.internet.InternetAddress;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.filesystem.api.FileSystem;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.BodyPartBuilder;
import org.apache.james.mime4j.message.MultipartBuilder;
import org.apache.james.mime4j.stream.RawField;

import com.github.fge.lambdas.Throwing;
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
                Path templatePath = Paths.get(configuration.templateLocationPath(), templateType.value());
                File templateFileDirectory = fileSystem.getFile(templatePath.toString());
                if (!templateFileDirectory.exists() || !templateFileDirectory.isDirectory()) {
                    throw new FileNotFoundException("Template directory not found: " + templateFileDirectory.getAbsolutePath());
                }

                I18NTranslator i18NTranslator = getI18NTranslator(templateType, language.locale());

                HtmlBodyRenderer htmlBodyRenderer = HtmlBodyRenderer.forPath(templateFileDirectory.getAbsolutePath());
                return new MessageGenerator(i18NTranslator, htmlBodyRenderer, userDAO);
            }

            public Factory cached() {
                return new Cached(this);
            }

            private I18NTranslator getI18NTranslator(TemplateType templateType, Locale locale) throws FileNotFoundException {
                Path translationsPath = Path.of(configuration.templateLocationPath(), templateType.value(), "translations");
                File translationsFileDirectory = fileSystem.getFile(translationsPath.toString());
                return new PropertiesI18NTranslator.Factory(translationsFileDirectory).forLocale(locale);
            }
        }

        record CacheEntry(Language language, TemplateType templateType) {

        }

        class Cached implements Factory {
            private final Factory factory;
            private final Cache<CacheEntry, MessageGenerator> loadingCache;

            public Cached(Factory factory) {
                this.factory = factory;
                this.loadingCache = CacheBuilder.newBuilder().build();
            }

            @Override
            public MessageGenerator forLocalizedFeature(Language language, TemplateType templateType) throws IOException {
                try {
                    return loadingCache.get(new CacheEntry(language, templateType), () -> factory.forLocalizedFeature(language, templateType));
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof IOException) {
                        throw (IOException) e.getCause();
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

    private final HtmlBodyRenderer htmlBodyRenderer;
    private final I18NTranslator i18nTranslator;
    private final OpenPaaSUserDAO userDAO;

    public MessageGenerator(I18NTranslator i18nTranslator,
                            HtmlBodyRenderer htmlBodyRenderer,
                            OpenPaaSUserDAO userDAO) {
        this.i18nTranslator = i18nTranslator;
        this.htmlBodyRenderer = htmlBodyRenderer;
        this.userDAO = userDAO;
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
            .flatMap(fullName -> Mono.fromCallable(() -> new InternetAddress(username.asString(), fullName)))
            .switchIfEmpty(Mono.fromCallable(() -> new InternetAddress(username.asString())));
    }

    public Mono<Message> generate(InternetAddress recipient, InternetAddress fromAddress, Map<String, Object> scopedVariable, List<MimeAttachment> mimeAttachments) {
        return Mono.fromCallable(() -> {
            Map<String, Object> scopedVariableFinal = ImmutableMap.<String, Object>builder()
                .putAll(scopedVariable)
                .put(TRANSLATOR_FUNCTION_NAME, i18nTranslator)
                .build();

            String htmlBodyText = htmlBodyRenderer.render(scopedVariableFinal);

            BodyPartBuilder htmlBodyPart = BodyPartBuilder.create()
                .setContentTransferEncoding("base64")
                .setBody(htmlBodyText, "html", StandardCharsets.UTF_8);
            htmlBodyPart.addField(new RawField("Content-Language", i18nTranslator.associatedLocale().getLanguage()));

            MultipartBuilder multipartBuilder = buildMultipart(htmlBodyPart, mimeAttachments);

            MailAddress fromAsMailAddress = new MailAddress(fromAddress.getAddress());

            return Message.Builder.of()
                .setMessageId("<" + UUID.randomUUID() + "@" + Optional.of(fromAsMailAddress).map(MailAddress::getDomain).map(Domain::asString).orElse("") + ">")
                .setSubject(subject(scopedVariableFinal))
                .setBody(multipartBuilder.build())
                .setFrom(fromAddress.toString())
                .setTo(recipient.toString())
                .build();
        });
    }

    private MultipartBuilder buildMultipart(BodyPartBuilder htmlBodyPart, List<MimeAttachment> attachments) {
        List<MimeAttachment> inlineAttachments = attachments.stream()
            .filter(MimeAttachment::inline)
            .toList();

        List<MimeAttachment> nonInlineAttachments = attachments.stream()
            .filter(attachment -> !attachment.inline())
            .toList();

        if (!inlineAttachments.isEmpty() && !nonInlineAttachments.isEmpty()) {
            return buildMixedWithRelatedAndAttachments(htmlBodyPart, inlineAttachments, nonInlineAttachments);
        }

        if (!inlineAttachments.isEmpty()) {
            return buildRelatedWithAttachments(htmlBodyPart, inlineAttachments);
        }

        if (!nonInlineAttachments.isEmpty()) {
            return buildMixedWithAttachments(htmlBodyPart, nonInlineAttachments);
        }

        return MultipartBuilder.create(MULTIPART_MIXED)
            .addBodyPart(htmlBodyPart.build());
    }

    private MultipartBuilder buildMixedWithAttachments(BodyPartBuilder htmlBodyPart, List<MimeAttachment> attachments) {
        MultipartBuilder builder = MultipartBuilder.create(MULTIPART_MIXED)
            .addBodyPart(htmlBodyPart.build());
        attachments.forEach(Throwing.consumer(a -> builder.addBodyPart(a.asBodyPart())));
        return builder;
    }

    private MultipartBuilder buildRelatedWithAttachments(BodyPartBuilder htmlBodyPart, List<MimeAttachment> inlineAttachments) {
        MultipartBuilder builder = MultipartBuilder.create(MULTIPART_RELATED)
            .addBodyPart(htmlBodyPart.build());
        inlineAttachments.forEach(Throwing.consumer(a -> builder.addBodyPart(a.asBodyPart())));
        return builder;
    }

    private MultipartBuilder buildMixedWithRelatedAndAttachments(BodyPartBuilder htmlBodyPart,
                                                               List<MimeAttachment> inlineAttachments,
                                                               List<MimeAttachment> nonInlineAttachments) {
        MultipartBuilder mixed = MultipartBuilder.create(MULTIPART_MIXED)
            .addBodyPart(BodyPartBuilder.create().setBody(buildRelatedWithAttachments(htmlBodyPart, inlineAttachments).build()));
        nonInlineAttachments.forEach(Throwing.consumer(attachment -> mixed.addBodyPart(attachment.asBodyPart())));
        return mixed;
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
