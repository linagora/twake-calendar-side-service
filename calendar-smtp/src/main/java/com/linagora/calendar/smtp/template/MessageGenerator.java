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

import reactor.core.publisher.Mono;

public class MessageGenerator {
    public interface Factory {
        MessageGenerator forLocalizedFeature(Language language, TemplateType templateType) throws IOException;

        class Default implements Factory {
            private static final String SUBJECT_FILE_NAME = "subject.txt";

            private final MailTemplateConfiguration configuration;
            private final FileSystem fileSystem;

            public Default(MailTemplateConfiguration configuration, FileSystem fileSystem) {
                this.configuration = configuration;
                this.fileSystem = fileSystem;
            }

            public MessageGenerator forLocalizedFeature(Language language, TemplateType templateType) throws IOException {
                Path templatePath = Paths.get(configuration.templateLocationPath(), language.value(), templateType.value());
                File templateFileDirectory = fileSystem.getFile(templatePath.toString());

                if (!templateFileDirectory.exists() || !templateFileDirectory.isDirectory()) {
                    throw new FileNotFoundException("Template directory not found: " + templateFileDirectory.getAbsolutePath());
                }

                Path subjectPath = templatePath.resolve(SUBJECT_FILE_NAME);
                File subjectFile = fileSystem.getFile(subjectPath.toString());
                if (!subjectFile.exists() || !subjectFile.isFile()) {
                    throw new FileNotFoundException("Subject file not found: " + subjectFile.getAbsolutePath());
                }

                HtmlBodyRenderer htmlBodyRenderer = HtmlBodyRenderer.forPath(templateFileDirectory.getAbsolutePath());
                String subject = IOUtils.toString(subjectFile.toURI(), StandardCharsets.UTF_8);
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
