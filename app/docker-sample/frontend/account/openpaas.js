window.openpaas = {
    AUTH_PROVIDER: 'oidc',
    OPENPAAS_API_URL: 'https://tcalendar-side-service.local',
    BASE_URL: '',
    ACCOUNT_SPA_URL: 'https://account.linagora.local/account',
    MAILTO_SPA_URL:'https://tmail.linagora.local',
    APP_GRID_ITEMS: '[{ "name": "Calendar", "url": "https://calendar.linagora.local/calendar" }, { "name": "Contacts", "url": "https://contacts.linagora.local/contacts" }]',
    AUTH_PROVIDER_SETTINGS: {
        authority: 'https://sso.linagora.local',
        client_id: 'twake-calendar',
        redirect_uri: 'https://account.linagora.local/account/auth/oidc/callback',
        silent_redirect_uri: 'https://account.linagora.local/account/assets/auth/silent-renew.html',
        post_logout_redirect_uri: 'https://account.linagora.local/account',
        response_type: 'code',
        scope: 'openid email profile'
    }
};