/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  This file is subject to the Affero Gnu Public License           *
 *  version 3.                                                      *
 ********************************************************************/

package com.linagora.calendar.restapi.routes;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linagora.calendar.dav.SyncToken;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.AddressBookListChangedEvent.ChangeType;
import com.linagora.calendar.storage.OpenPaaSId;

class WebSocketNotificationListenerTest {

    @ParameterizedTest
    @EnumSource(ChangeType.class)
    void addressBookListChangeMessageShouldSerializeWithoutSyncToken(ChangeType changeType) throws Exception {
        AddressBookURL addressBookURL = new AddressBookURL(new OpenPaaSId("base-id"), "contacts");

        String serialized = new DefaultWebSocketNotificationListener.AddressBookListChangeMessage(addressBookURL, changeType)
            .serialize();

        assertThatJson(serialized).isEqualTo("""
            {
                "addressBookList": {
                    "{change}": ["/addressbooks/base-id/contacts"]
                }
            }
            """.replace("{change}", changeType.name().toLowerCase(Locale.ROOT)));
    }

    @Test
    void addressBookChangeMessageShouldSerializeSyncToken() throws Exception {
        AddressBookURL addressBookURL = new AddressBookURL(new OpenPaaSId("base-id"), "contacts");

        String serialized = new WebSocketNotificationListener.AddressBookChangeMessage(addressBookURL, new SyncToken("2"))
            .serialize();

        assertThatJson(serialized).isEqualTo("""
            {
              "/addressbooks/base-id/contacts": {
                "syncToken": "2"
              }
            }
            """);
    }
}
