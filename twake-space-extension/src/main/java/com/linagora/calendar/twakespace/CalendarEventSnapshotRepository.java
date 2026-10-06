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

package com.linagora.calendar.twakespace;

import static com.mongodb.client.model.Filters.eq;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.bson.Document;

import com.linagora.calendar.storage.model.TeamCalendarId;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;

public class CalendarEventSnapshotRepository {
    public static final String COLLECTION = "twake_space_events";
    private static final String ID_FIELD = "_id";
    private static final String TEAM_CALENDAR_FIELD = "teamCalendar";
    private static final String TITLE_FIELD = "title";
    private static final String START_FIELD = "start";
    private static final String END_FIELD = "end";
    private static final String ALL_DAY_FIELD = "allDay";
    private static final String LOCATION_FIELD = "location";
    private static final String ORGANIZER_FIELD = "organizer";
    private static final String ATTENDEES_FIELD = "attendees";
    private static final String EMAIL_FIELD = "email";
    private static final String PART_STAT_FIELD = "partStat";

    private final MongoCollection<Document> collection;

    @Inject
    public CalendarEventSnapshotRepository(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
    }

    public Mono<Void> save(CalendarEventSnapshot snapshot) {
        Document document = new Document(ID_FIELD, snapshot.uid())
            .append(TEAM_CALENDAR_FIELD, snapshot.teamCalendarId().value())
            .append(TITLE_FIELD, snapshot.title())
            .append(START_FIELD, Date.from(snapshot.start()))
            .append(END_FIELD, snapshot.end().map(Date::from).orElse(null))
            .append(ALL_DAY_FIELD, snapshot.allDay())
            .append(LOCATION_FIELD, snapshot.location().orElse(null))
            .append(ORGANIZER_FIELD, snapshot.organizer().orElse(null))
            .append(ATTENDEES_FIELD, snapshot.attendees().entrySet().stream()
                .map(attendee -> new Document(EMAIL_FIELD, attendee.getKey()).append(PART_STAT_FIELD, attendee.getValue()))
                .toList());
        return Mono.from(collection.replaceOne(eq(ID_FIELD, snapshot.uid()), document, new ReplaceOptions().upsert(true)))
            .then();
    }

    public Mono<CalendarEventSnapshot> retrieve(String uid) {
        return Mono.from(collection.find(eq(ID_FIELD, uid)).first())
            .map(document -> new CalendarEventSnapshot(document.getString(ID_FIELD),
                new TeamCalendarId(document.getString(TEAM_CALENDAR_FIELD)),
                document.getString(TITLE_FIELD),
                document.getDate(START_FIELD).toInstant(),
                Optional.ofNullable(document.getDate(END_FIELD)).map(Date::toInstant),
                document.getBoolean(ALL_DAY_FIELD),
                Optional.ofNullable(document.getString(LOCATION_FIELD)),
                Optional.ofNullable(document.getString(ORGANIZER_FIELD)),
                document.getList(ATTENDEES_FIELD, Document.class, List.of()).stream()
                    .collect(Collectors.toMap(attendee -> attendee.getString(EMAIL_FIELD), attendee -> attendee.getString(PART_STAT_FIELD)))));
    }
}
