/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  This file is subject to the Affero Gnu Public License           *
 *  version 3.                                                      *
 ********************************************************************/

package com.linagora.calendar.restapi.routes;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import org.junit.jupiter.api.Test;

import com.linagora.calendar.dav.SyncToken;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSId;

class WebSocketNotificationListenerTest {

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
