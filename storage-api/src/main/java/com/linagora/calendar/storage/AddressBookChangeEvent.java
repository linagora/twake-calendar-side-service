/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  This file is subject to the Affero Gnu Public License           *
 *  version 3.                                                      *
 ********************************************************************/

package com.linagora.calendar.storage;

import org.apache.james.core.Username;
import org.apache.james.events.Event;

public record AddressBookChangeEvent(Event.EventId eventId, AddressBookURL addressBookURL) implements Event {

    public static final Username USERNAME = Username.of("AddressBookChange");

    @Override
    public Username getUsername() {
        return USERNAME;
    }

    @Override
    public boolean isNoop() {
        return false;
    }

    @Override
    public EventId getEventId() {
        return eventId;
    }
}
