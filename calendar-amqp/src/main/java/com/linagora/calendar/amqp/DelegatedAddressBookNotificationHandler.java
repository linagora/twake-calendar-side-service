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

package com.linagora.calendar.amqp;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.mailbox.model.Cid;
import org.apache.james.mailbox.model.ContentType;
import org.apache.james.mime4j.dom.Message;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.amqp.AddressBookDelegatedNotificationConsumer.AddressBookDelegatedCreatedMessage;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.smtp.EventEmailFilter;
import com.linagora.calendar.smtp.Mail;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.i18n.I18NTranslator;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.template.MimeAttachment;
import com.linagora.calendar.smtp.template.SubjectRenderer;
import com.linagora.calendar.smtp.template.TemplateType;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.ResolvedSettings;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class DelegatedAddressBookNotificationHandler {
    private static final TemplateType MAIL_TEMPLATE = new TemplateType("addressbook-delegate-created");
    private static final String USER_PRINCIPAL_PREFIX = "principals/users/";
    private static final String SUBSCRIPTION_TYPE_PROPERTY = "openpaas:subscription-type";
    private static final String DELEGATION_SUBSCRIPTION_TYPE = "delegation";
    private static final String ADDRESS_BOOK_SOURCE_PROPERTY = "openpaas:source";
    private static final String ADDRESS_BOOK_NAME_PROPERTY = "dav:name";
    private static final String JSON_EXTENSION = ".json";
    private static final String DEFAULT_ADDRESS_BOOK_DAV_NAME = "#default";
    private static final String DEFAULT_ADDRESS_BOOK_NAME_KEY = "default_addressbook_name";
    private static final String LOGO_CONTENT_TYPE = "image/png";
    private static final String LOGO_CONTENT_ID = "logo";
    private static final String LOGO_FILE_NAME = "logo.png";

    private final OpenPaaSUserDAO userDAO;
    private final CardDavClient cardDavClient;
    private final SettingsBasedResolver settingsResolver;
    private final MailSender.Factory mailSenderFactory;
    private final MaybeSender sender;
    private final MailAddress senderAddress;
    private final MessageGenerator.Factory messageGeneratorFactory;
    private final EventEmailFilter eventEmailFilter;
    private final URI contactsBaseUrl;
    private final MimeAttachment logoAttachment;

    @Inject
    public DelegatedAddressBookNotificationHandler(OpenPaaSUserDAO userDAO,
                                                   CardDavClient cardDavClient,
                                                   @Named("language") SettingsBasedResolver settingsResolver,
                                                   MailSender.Factory mailSenderFactory,
                                                   MailTemplateConfiguration templateConfiguration,
                                                   MessageGenerator.Factory messageGeneratorFactory,
                                                   EventEmailFilter eventEmailFilter,
                                                   @Named("spaContactsUrl") URL contactsBaseUrl,
                                                   @Named("contacts-logo") byte[] contactsLogo) throws URISyntaxException {
        this.userDAO = userDAO;
        this.cardDavClient = cardDavClient;
        this.settingsResolver = settingsResolver;
        this.mailSenderFactory = mailSenderFactory;
        this.sender = templateConfiguration.sender();
        this.senderAddress = sender.asOptional()
            .orElseThrow(() -> new IllegalArgumentException("Sender address must not be empty"));
        this.messageGeneratorFactory = messageGeneratorFactory;
        this.eventEmailFilter = eventEmailFilter;
        this.contactsBaseUrl = contactsBaseUrl.toURI();
        this.logoAttachment = MimeAttachment.builder()
            .contentType(ContentType.of(LOGO_CONTENT_TYPE))
            .cid(Cid.from(LOGO_CONTENT_ID))
            .inline()
            .content(contactsLogo)
            .fileName(LOGO_FILE_NAME)
            .build();
    }

    public Mono<Void> handle(AddressBookDelegatedCreatedMessage message) {
        AddressBookURL copyURL = message.addressBookURL();
        // Restoring one's own hidden copy also emits created, but does not grant a new delegation.
        if ((USER_PRINCIPAL_PREFIX + copyURL.baseId().value()).equals(message.connectedUser())) {
            return Mono.empty();
        }
        return buildNotificationData(copyURL)
            .filter(Throwing.predicate(data -> eventEmailFilter.shouldProcess(data.recipient().username().asMailAddress())))
            .flatMap(data -> settingsResolver.resolveOrDefault(data.recipient().username())
                .flatMap(settings -> generateMessage(data, settings))
                .flatMap(mail -> mailSenderFactory.send(new Mail(sender,
                    List.of(Throwing.supplier(data.recipient().username()::asMailAddress).get()), mail))))
            .onErrorMap(error -> new IllegalStateException("Failed to send address book delegation email for " + message.path(), error));
    }

    private Mono<NotificationData> buildNotificationData(AddressBookURL copyURL) {
        return userDAO.retrieve(copyURL.baseId())
            .flatMap(recipient -> cardDavClient.fetchAddressBookMetadata(recipient.username(), copyURL)
                // CardDAV uses the same exchange for delegation and self-subscription; only the former sends mail.
                .filter(metadata -> DELEGATION_SUBSCRIPTION_TYPE.equals(metadata.path(SUBSCRIPTION_TYPE_PROPERTY).asText()))
                .flatMap(metadata -> {
                    String sourcePath = URI.create(metadata.path(ADDRESS_BOOK_SOURCE_PROPERTY).asText()).getRawPath();
                    AddressBookURL sourceURL = AddressBookURL.parse(Strings.CS.removeEnd(sourcePath, JSON_EXTENSION));
                    return userDAO.retrieve(sourceURL.baseId())
                        .flatMap(owner -> cardDavClient.fetchAddressBookMetadata(owner.username(), sourceURL)
                            .map(source -> new NotificationData(owner.fullName(), source.path(ADDRESS_BOOK_NAME_PROPERTY).asText(), recipient)));
                }));
    }

    private Mono<Message> generateMessage(NotificationData data, ResolvedSettings settings) {
        Username recipient = data.recipient().username();
        return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language(settings.locale()), MAIL_TEMPLATE))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(generator -> Mono.fromCallable(() -> data.toPugModel(contactsBaseUrl, generator.getI18nTranslator()))
                .flatMap(model -> generator.generate(recipient, senderAddress, model, List.of(logoAttachment))));
    }

    private record NotificationData(String ownerName, String addressBookName, OpenPaaSUser recipient) {
        Map<String, Object> toPugModel(URI contactsBaseUrl, I18NTranslator translator) throws IOException {
            String displayName = StringUtils.isBlank(addressBookName) || DEFAULT_ADDRESS_BOOK_DAV_NAME.equals(addressBookName)
                ? SubjectRenderer.of(translator.get(DEFAULT_ADDRESS_BOOK_NAME_KEY)).render(Map.of("delegatorName", ownerName))
                : addressBookName;
            return Map.of(
                "content", Map.of("delegatorName", ownerName, "addressBookName", displayName,
                    "contactsUrl", contactsBaseUrl.toASCIIString()),
                "delegatorName", ownerName,
                "addressBookName", displayName);
        }
    }
}
