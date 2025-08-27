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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.smtp.i18n.I18NTranslator;
import com.linagora.calendar.smtp.template.content.model.AlarmContentModelBuilder;
import com.linagora.calendar.smtp.template.content.model.PersonModel;

public class HtmlEmailAlarmRenderTest {
    private HtmlBodyRenderer htmlBodyRenderer;
    private I18NTranslator.PropertiesI18NTranslator.Factory i18nFactory;

    @BeforeEach
    void setUp() throws Exception {
        Path templateDirectory = Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
            "app", "src", "main", "resources", "templates", "event-alarm");

        htmlBodyRenderer = HtmlBodyRenderer.forPath(templateDirectory.toAbsolutePath().toString());
        i18nFactory = new I18NTranslator.PropertiesI18NTranslator.Factory(templateDirectory.resolve("translations").toFile());
    }

    private ImmutableMap<String, Object> sampleModel(I18NTranslator translator) {
        Map<String, Object> contentModel = AlarmContentModelBuilder.builder()
            .duration(Duration.ofMinutes(5))
            .summary("Team Meeting")
            .location(Optional.of("Conference Room"))
            .organizer(new PersonModel("alice@domain.tld", "Alice Organizer"))
            .attendees(List.of(
                new PersonModel("bob@domain.tld", "Bob Attendee"),
                new PersonModel("carol@domain.tld", "Carol Attendee")
            ))
            .resources(List.of(
                new PersonModel("projector1", "Projector"),
                new PersonModel("roomA", "Room A")
            ))
            .description(Optional.of("Discuss project updates."))
            .videoconference(Optional.empty())
            .translator(translator)
            .buildAsMap();

        return ImmutableMap.<String, Object>builder()
            .putAll(contentModel)
            .put("translator", translator)
            .build();
    }

    @Test
    void renderEventAlarmShouldSucceed() {
        I18NTranslator translator = i18nFactory.forLocale(Locale.ENGLISH);

        ImmutableMap<String, Object> model = sampleModel(translator);

        String result = htmlBodyRenderer.render(model);

        assertThat(result).isEqualToIgnoringNewLines("""
            <!DOCTYPE html><head><title></title><!-- [if !mso] <!--><meta http-equiv="X-UA-Compatible" content="IE=edge"/><!-- <![endif]--><meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/><style type="text/css">#outlook a {
            padding: 0;
            }
            body {
            margin: 0;
            padding: 0;
            -webkit-text-size-adjust: 100%;
            -ms-text-size-adjust: 100%;
            }
            table,
            td {
            border-collapse: collapse;
            mso-table-lspace: 0pt;
            mso-table-rspace: 0pt;
            }
            img {
            border: 0;
            height: auto;
            line-height: 100%;
            outline: none;
            text-decoration: none;
            -ms-interpolation-mode: bicubic;
            }
            p {
            display: block;
            margin: 13px 0;
            }</style><!--if msoxml
              o:officedocumentsettings
                o:allowpng
                  o:pixelsperinch 96--><!--if lte mso 11style(type='text/css').
              .mj-outlook-group-fix { width:100% !important; }--><!-- [if !mso] <!--><link href="https://fonts.googleapis.com/css?family=Roboto:300,400,500,700" rel="stylesheet" type="text/css"/><style type="text/css">@import url(https://fonts.googleapis.com/css?family=Roboto:300,400,500,700);</style><!-- <![endif]--><style type="text/css">@media only screen and (min-width:480px) {
            .mj-column-per-100 {
            width: 100% !important;
            max-width: 100%;
            }
            }</style><style type="text/css"></style></head><div style=""><!--if mso | IEtable(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
              tr
                td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;padding-bottom:32px;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
              tr
                td(width='600px')
                  table(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
                    tr
                      td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;padding-bottom:0;padding-top:0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
              tr
                td.alarm-title-container-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix alarm-title-container" style="background: #eaeefe; border-radius: 4px; border: 1px solid #eaeefe; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" width="100%"><tbody><tr><td style="vertical-align:top;padding-top:4px;padding-bottom:4px;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:0;padding-top:8px;padding-right:20px;padding-bottom:8px;padding-left:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:16px;line-height:1;text-align:left;color:#222222;">This event is about to begin in in 5 minutes</div></td></tr></tbody></table></td></tr></tbody></table></div><!--if mso | IEtd.content-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix content" style="border: 1px solid #ccc; border-radius: 4px; margin-top: 24px; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="vertical-align:top;" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:20px;font-weight:500;line-height:1;text-align:left;color:#434343;">Team Meeting</div></td></tr><tr><td align="left" style="font-size:0px;padding:20px;padding-top:0px;word-break:break-word;"><table cellpadding="4px" cellspacing="0" width="100%" border="0" style="color:#434343;font-family:Roboto;font-size:14px;line-height:22px;table-layout:auto;width:100%;border:none;"><tr><td style="min-width: 96px;" valign="top"><strong>Location</strong></td><td>Conference Room (<a href="https://www.openstreetmap.org/search?query=Conference Room">See in Map</a>)</td></tr><tr><td valign="top"><strong>Attendees</strong></td><td><ul style="padding-inline-start: 16px; margin: 0;"><li><span style="font-weight: 500">alice@domain.tld</span><span style="color: #787878;">&nbsp;&lt;Alice Organizer&gt;</span><span style="font-weight: 500">&nbsp;(Organizer)</span></li><li><span style="font-weight: 500">bob@domain.tld</span><span style="color: #787878;">&nbsp;&lt;Bob Attendee&gt;</span></li><li><span style="font-weight: 500">carol@domain.tld</span><span style="color: #787878;">&nbsp;&lt;Carol Attendee&gt;</span></li></ul></td></tr><tr><td valign="top"><strong>Resources</strong></td><td><span>projector1,&nbsp;roomA</span></td></tr><tr><td valign="top"><strong>Notes</strong></td><td>Discuss project updates.</td></tr></table></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></div>""");
    }

    @ParameterizedTest(name = "{index} => {1}")
    @MethodSource("localeWithNotification")
    void renderEventShouldContainNotificationI18n(Locale locale, String notificationExpected) {
        I18NTranslator translator = i18nFactory.forLocale(locale);

        ImmutableMap<String, Object> model = sampleModel(translator);

        String result = htmlBodyRenderer.render(model);
        assertThat(result)
            .contains(notificationExpected);
    }

    static Stream<Arguments> localeWithNotification() {
        return Stream.of(
            Arguments.of(Locale.ENGLISH, "This event is about to begin in in 5 minutes"),
            Arguments.of(Locale.of("ru"), "Это событие скоро начнется через через 5 минут")
        );
    }

    @Test
    void renderWithValidLocationURLShouldGenerateHyperlink() {

        I18NTranslator translator = i18nFactory.forLocale(Locale.ENGLISH);

        Map<String, Object> contentModel = AlarmContentModelBuilder.builder()
            .duration(Duration.ofMinutes(5))
            .summary("Team Meeting")
            .location(Optional.of("https://meet.example.com/room"))
            .organizer(new PersonModel("alice@domain.tld", "Alice Organizer"))
            .attendees(List.of(
                new PersonModel("bob@domain.tld", "Bob Attendee"),
                new PersonModel("carol@domain.tld", "Carol Attendee")
            ))
            .resources(List.of(
                new PersonModel("projector1", "Projector"),
                new PersonModel("roomA", "Room A")
            ))
            .description(Optional.of("Discuss project updates."))
            .videoconference(Optional.empty())
            .translator(translator)
            .buildAsMap();

        ImmutableMap<String, Object> model = ImmutableMap.<String, Object>builder()
            .putAll(contentModel)
            .put("translator", translator)
            .build();

        String result = htmlBodyRenderer.render(model);

        assertThat(result)
            .contains("""
                <a class="link" href="https://meet.example.com/room" style="text-decoration: none; color: #4d91c9;">https://meet.example.com/room</a>""".trim());
    }

    @Test
    void renderWithVideoConferenceShouldGenerateHyperlink() {
        I18NTranslator translator = i18nFactory.forLocale(Locale.ENGLISH);

        Map<String, Object> contentModel = AlarmContentModelBuilder.builder()
            .duration(Duration.ofMinutes(5))
            .summary("Team Meeting")
            .location(Optional.empty())
            .organizer(new PersonModel("alice@domain.tld", "Alice Organizer"))
            .attendees(List.of(
                new PersonModel("bob@domain.tld", "Bob Attendee"),
                new PersonModel("carol@domain.tld", "Carol Attendee")
            ))
            .resources(List.of(
                new PersonModel("projector1", "Projector"),
                new PersonModel("roomA", "Room A")
            ))
            .description(Optional.of("Discuss project updates."))
            .videoconference(Optional.of("https://jitsi.linagora.com/11bb0e17-9b2d-433e-992f-1d797b8c9a5d"))
            .translator(translator)
            .buildAsMap();

        ImmutableMap<String, Object> model = ImmutableMap.<String, Object>builder()
            .putAll(contentModel)
            .put("translator", translator)
            .build();

        String result = htmlBodyRenderer.render(model);

        assertThat(result).isEqualToIgnoringNewLines("""
            <!DOCTYPE html><head><title></title><!-- [if !mso] <!--><meta http-equiv="X-UA-Compatible" content="IE=edge"/><!-- <![endif]--><meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/><style type="text/css">#outlook a {
            padding: 0;
            }
            body {
            margin: 0;
            padding: 0;
            -webkit-text-size-adjust: 100%;
            -ms-text-size-adjust: 100%;
            }
            table,
            td {
            border-collapse: collapse;
            mso-table-lspace: 0pt;
            mso-table-rspace: 0pt;
            }
            img {
            border: 0;
            height: auto;
            line-height: 100%;
            outline: none;
            text-decoration: none;
            -ms-interpolation-mode: bicubic;
            }
            p {
            display: block;
            margin: 13px 0;
            }</style><!--if msoxml
              o:officedocumentsettings
                o:allowpng
                  o:pixelsperinch 96--><!--if lte mso 11style(type='text/css').
              .mj-outlook-group-fix { width:100% !important; }--><!-- [if !mso] <!--><link href="https://fonts.googleapis.com/css?family=Roboto:300,400,500,700" rel="stylesheet" type="text/css"/><style type="text/css">@import url(https://fonts.googleapis.com/css?family=Roboto:300,400,500,700);</style><!-- <![endif]--><style type="text/css">@media only screen and (min-width:480px) {
            .mj-column-per-100 {
            width: 100% !important;
            max-width: 100%;
            }
            }</style><style type="text/css"></style></head><div style=""><!--if mso | IEtable(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
              tr
                td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;padding-bottom:32px;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
              tr
                td(width='600px')
                  table(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
                    tr
                      td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;padding-bottom:0;padding-top:0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
              tr
                td.alarm-title-container-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix alarm-title-container" style="background: #eaeefe; border-radius: 4px; border: 1px solid #eaeefe; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" width="100%"><tbody><tr><td style="vertical-align:top;padding-top:4px;padding-bottom:4px;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:0;padding-top:8px;padding-right:20px;padding-bottom:8px;padding-left:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:16px;line-height:1;text-align:left;color:#222222;">This event is about to begin in in 5 minutes</div></td></tr></tbody></table></td></tr></tbody></table></div><!--if mso | IEtd.content-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix content" style="border: 1px solid #ccc; border-radius: 4px; margin-top: 24px; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="vertical-align:top;" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:20px;font-weight:500;line-height:1;text-align:left;color:#434343;">Team Meeting</div></td></tr><tr><td align="left" style="font-size:0px;padding:20px;padding-top:0px;word-break:break-word;"><table cellpadding="4px" cellspacing="0" width="100%" border="0" style="color:#434343;font-family:Roboto;font-size:14px;line-height:22px;table-layout:auto;width:100%;border:none;"><tr><td style="min-width: 96px;" valign="top"><strong>Link</strong></td><td><a class="link" href="https://jitsi.linagora.com/11bb0e17-9b2d-433e-992f-1d797b8c9a5d" style="text-decoration: none; color: #4d91c9;">https://jitsi.linagora.com/11bb0e17-9b2d-433e-992f-1d797b8c9a5d</a></td></tr><tr><td valign="top"><strong>Attendees</strong></td><td><ul style="padding-inline-start: 16px; margin: 0;"><li><span style="font-weight: 500">alice@domain.tld</span><span style="color: #787878;">&nbsp;&lt;Alice Organizer&gt;</span><span style="font-weight: 500">&nbsp;(Organizer)</span></li><li><span style="font-weight: 500">bob@domain.tld</span><span style="color: #787878;">&nbsp;&lt;Bob Attendee&gt;</span></li><li><span style="font-weight: 500">carol@domain.tld</span><span style="color: #787878;">&nbsp;&lt;Carol Attendee&gt;</span></li></ul></td></tr><tr><td valign="top"><strong>Resources</strong></td><td><span>projector1,&nbsp;roomA</span></td></tr><tr><td valign="top"><strong>Notes</strong></td><td>Discuss project updates.</td></tr></table></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></div>""".trim());
    }

}
