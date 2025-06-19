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
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.field.ParseException;
import org.apache.james.mime4j.message.BodyPartBuilder;
import org.apache.james.mime4j.message.MultipartBuilder;
import org.apache.james.mime4j.stream.RawField;
import org.apache.james.utils.PropertiesProvider;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.restapi.routes.ImportProcessor.ImportResult;
import com.linagora.calendar.restapi.routes.ImportProcessor.ImportType;
import com.linagora.calendar.smtp.Mail;

import de.neuland.pug4j.Pug4J;
import de.neuland.pug4j.PugConfiguration;
import de.neuland.pug4j.template.FileTemplateLoader;
import de.neuland.pug4j.template.PugTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class ImportMailReportRender {

    private final ImportICSReportRender importICSReportRender;
    private final ImportVCARDReportRender importVCARDReportRender;

    @Inject
    @Singleton
    public ImportMailReportRender(PropertiesProvider propertiesProvider) throws ConfigurationException, IOException {
        Configuration configuration = propertiesProvider.getConfiguration("configuration");
        MailTemplateConfiguration icsMailTemplateConfiguration = MailTemplateConfiguration.calendarFrom(configuration);
        MailTemplateConfiguration vcardMailTemplateConfiguration = MailTemplateConfiguration.contactFrom(configuration);
        this.importVCARDReportRender = new ImportVCARDReportRender(vcardMailTemplateConfiguration);
        this.importICSReportRender = new ImportICSReportRender(icsMailTemplateConfiguration);
    }

    public ImportMailReportRender(MailTemplateConfiguration icsMailTemplateConfiguration,
                                  MailTemplateConfiguration vcardMailTemplateConfiguration) throws IOException {
        this.importICSReportRender = new ImportICSReportRender(icsMailTemplateConfiguration);
        this.importVCARDReportRender = new ImportVCARDReportRender(vcardMailTemplateConfiguration);
    }

    public Mono<Mail> generateMail(ImportType importType, ImportResult importResult, Username username) {
        Render render = switch (importType) {
            case ICS -> importICSReportRender;
            case VCARD -> importVCARDReportRender;
        };

        return Mono.fromCallable(() -> render.mail(importResult, username.asMailAddress()))
            .subscribeOn(Schedulers.boundedElastic());
    }

    public record MailTemplateConfiguration(String templateLocationPath,
                                            MaybeSender sender,
                                            URL baseUrl,
                                            byte[] logoAsBytes) {
        private static final String PREFIX_CONFIGURATION = "import.mailReport.";

        public static final String SENDER_PROPERTY = PREFIX_CONFIGURATION + "sender";
        public static final String TEMPLATE_LOCATION_PROPERTY = PREFIX_CONFIGURATION + "templateLocation";

        public static final String CALENDAR_BASE_URL_PROPERTY = PREFIX_CONFIGURATION + "calendar.baseUrl";
        public static final String CALENDAR_LOGO_PATH_PROPERTY = PREFIX_CONFIGURATION + "calendar.logo";
        public static final String CONTACT_BASE_URL_PROPERTY = PREFIX_CONFIGURATION + "contact.baseUrl";
        public static final String CONTACT_LOGO_PATH_PROPERTY = PREFIX_CONFIGURATION + "contact.logo";

        public static MailTemplateConfiguration calendarFrom(Configuration configuration) throws IOException {
            return from(configuration, CALENDAR_BASE_URL_PROPERTY, CALENDAR_LOGO_PATH_PROPERTY);
        }

        public static MailTemplateConfiguration contactFrom(Configuration configuration) throws IOException {
            return from(configuration, CONTACT_BASE_URL_PROPERTY, CONTACT_LOGO_PATH_PROPERTY);
        }

        private static MailTemplateConfiguration from(Configuration configuration,
                                                      String baseUrlProperty,
                                                      String logoPathProperty) throws IOException {
            String templateLocation = getRequired(configuration, TEMPLATE_LOCATION_PROPERTY);
            String senderString = getRequired(configuration, SENDER_PROPERTY);
            String baseUrlString = getRequired(configuration, baseUrlProperty);
            String logoPathString = getRequired(configuration, logoPathProperty);

            MaybeSender sender = MaybeSender.getMailSender(senderString);
            URL baseUrl = URI.create(baseUrlString).toURL();

            Path logoPath = Paths.get(templateLocation, logoPathString);
            byte[] logoAsBytes = Files.readAllBytes(logoPath);

            return new MailTemplateConfiguration(templateLocation, sender, baseUrl, logoAsBytes);
        }

        private static String getRequired(Configuration configuration, String key) {
            String value = configuration.getString(key, null);
            Preconditions.checkArgument(StringUtils.isNotEmpty(value), "`%s` must not be empty".formatted(key));
            return value;
        }
    }

    public abstract static class Render {

        private static final String MAIL_SUBJECT = "Import reporting";

        private final MailTemplateConfiguration mailTemplateConfiguration;
        private final PugTemplate pugTemplate;

        protected Render(MailTemplateConfiguration mailTemplateConfiguration) throws IOException {
            this.mailTemplateConfiguration = mailTemplateConfiguration;

            FileTemplateLoader fileLoader = new FileTemplateLoader(mailTemplateConfiguration.templateLocationPath());
            fileLoader.setBase("");

            PugConfiguration pugConfiguration = new PugConfiguration();
            pugConfiguration.setTemplateLoader(fileLoader);

            this.pugTemplate = pugConfiguration.getTemplate(templatePath());
        }

        protected abstract String templatePath();

        protected String mailSubject() {
            return MAIL_SUBJECT;
        }

        public Mail mail(ImportResult importResult, MailAddress recipient) throws IOException, ParseException {
            Message message = mimeMessage(importResult, recipient);
            return new Mail(mailTemplateConfiguration.sender(), List.of(recipient), message);
        }

        public Message mimeMessage(ImportResult importResult, MailAddress recipient) throws IOException, ParseException {
            String htmlBodyText = htmlBody(importResult);

            MultipartBuilder multipartBuilder = MultipartBuilder.create("related")
                .addBodyPart(BodyPartBuilder.create()
                    .setContentTransferEncoding("base64")
                    .setBody(htmlBodyText, "html", StandardCharsets.UTF_8))
                .addBodyPart(BodyPartBuilder.create()
                    .setContentDisposition("inline; filename=\"logo.png\"")
                    .setField(new RawField("Content-ID", "logo"))
                    .setContentTransferEncoding("base64")
                    .setBody(mailTemplateConfiguration.logoAsBytes(), "image/png; name=\"logo.png\""));

            return Message.Builder.of()
                .setSubject(mailSubject())
                .setBody(multipartBuilder.build())
                .setFrom(mailTemplateConfiguration.sender().asString())
                .setTo(recipient.asString())
                .build();
        }

        private String htmlBody(ImportResult importResult) {
            Map<String, Object> model = ImmutableMap.of(
                "content", Map.of(
                    "baseUrl", mailTemplateConfiguration.baseUrl(),
                    "jobFailedList", importResult.failed(),
                    "jobSucceedCount", importResult.succeedCount(),
                    "jobFailedCount", importResult.failed().size()));

            return Pug4J.render(pugTemplate, model);
        }
    }

    static class ImportICSReportRender extends Render {
        private static final String TEMPLATE_PATH = "event.import/ics/html.pug";

        protected ImportICSReportRender(MailTemplateConfiguration mailTemplateConfiguration) throws IOException {
            super(mailTemplateConfiguration);
        }

        @Override
        protected String templatePath() {
            return TEMPLATE_PATH;
        }
    }

    static class ImportVCARDReportRender extends Render {
        private static final String TEMPLATE_PATH = "event.import/vcard/html.pug";

        protected ImportVCARDReportRender(MailTemplateConfiguration mailTemplateConfiguration) throws IOException {
            super(mailTemplateConfiguration);
        }

        @Override
        protected String templatePath() {
            return TEMPLATE_PATH;
        }
    }
}

