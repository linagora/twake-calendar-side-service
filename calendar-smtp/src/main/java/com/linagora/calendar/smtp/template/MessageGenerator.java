package com.linagora.calendar.smtp.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.apache.commons.io.IOUtils;
import org.apache.james.core.Username;
import org.apache.james.filesystem.api.FileSystem;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.BodyPartBuilder;
import org.apache.james.mime4j.message.MultipartBuilder;

import com.github.fge.lambdas.Throwing;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import reactor.core.publisher.Mono;

public class MessageGenerator {
    public interface Factory {
        MessageGenerator forLocalizedFeature(Language language, TemplateType templateType) throws IOException;

        class Default implements Factory {
            private final MailTemplateConfiguration configuration;
            private final FileSystem fileSystem;

            public Default(MailTemplateConfiguration configuration, FileSystem fileSystem) {
                this.configuration = configuration;
                this.fileSystem = fileSystem;
            }

            public MessageGenerator forLocalizedFeature(Language language, TemplateType templateType) throws IOException {
                String templatePath = configuration.templateLocationPath()
                    .replace(":language", language.value())
                    .replace(":feature", templateType.value());

                HtmlBodyRenderer htmlBodyRenderer = HtmlBodyRenderer.forPath(templatePath + "/html.pug");
                String subject = IOUtils.toString(fileSystem.getResource(templatePath + "/subject.txt"), StandardCharsets.UTF_8);

                return new MessageGenerator(configuration, subject, htmlBodyRenderer);
            }

            public Factory cached() {
                return new Cached(this);
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

    public static Factory.Default factory(MailTemplateConfiguration configuration, FileSystem fileSystem) {
        return new Factory.Default(configuration, fileSystem);
    }

    private final MailTemplateConfiguration configuration;
    private final String subject;
    private final HtmlBodyRenderer htmlBodyRenderer;

    public MessageGenerator(MailTemplateConfiguration configuration, String subject, HtmlBodyRenderer htmlBodyRenderer) {
        this.configuration = configuration;
        this.subject = subject;
        this.htmlBodyRenderer = htmlBodyRenderer;
    }

    public Mono<Message> generate(Username recipient, Map<String, Object> scopedVariable, List<InlinedAttachment> inlinedAttachments) {
        return Mono.fromCallable(() -> {
            String htmlBodyText = htmlBodyRenderer.render(scopedVariable);

            MultipartBuilder multipartBuilder = MultipartBuilder.create("related")
                .addBodyPart(BodyPartBuilder.create()
                    .setContentTransferEncoding("base64")
                    .setBody(htmlBodyText, "html", StandardCharsets.UTF_8));

            inlinedAttachments.forEach(Throwing.consumer(attachment -> multipartBuilder.addBodyPart(attachment.asBodyPart())));

            return Message.Builder.of()
                .setSubject(subject)
                .setBody(multipartBuilder.build())
                .setFrom(configuration.sender().asString())
                .setTo(recipient.asString())
                .build();
        });
    }
}
