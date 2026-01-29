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

package com.linagora.calendar.saas;

import org.apache.james.core.Domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DomainSubscriptionMessage(
    @JsonProperty("domain") String domain,
    @JsonProperty("mailDnsConfigurationValidated") Boolean mailDnsConfigurationValidated,
    @JsonProperty("features") CalendarSaasFeatures features) {

    @JsonCreator
    public DomainSubscriptionMessage(
        @JsonProperty("domain") String domain,
        @JsonProperty("mailDnsConfigurationValidated") Boolean mailDnsConfigurationValidated,
        @JsonProperty("features") CalendarSaasFeatures features) {
        Preconditions.checkNotNull(domain, "domain cannot be null");
        this.domain = domain;
        this.mailDnsConfigurationValidated = mailDnsConfigurationValidated != null ? mailDnsConfigurationValidated : false;
        this.features = features;
    }

    public Domain domainObject() {
        return Domain.of(domain);
    }

    public boolean hasCalendarFeature() {
        return features != null && features.hasCalendarFeature();
    }
}
