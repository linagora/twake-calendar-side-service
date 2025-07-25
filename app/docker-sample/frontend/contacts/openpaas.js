window.openpaas = {
    AUTH_PROVIDER: 'oidc',
    OPENPAAS_API_URL: 'https://tcalendar-side-service.local',
    BASE_URL: '',
    ACCOUNT_SPA_URL: 'https://account.linagora.local/account',
    MAILTO_SPA_URL: 'https://tmail.linagora.local',
    APP_GRID_ITEMS: '[{ "name": "Calendar", "url": "https://calendar.linagora.local/calendar" }]',
    AUTH_PROVIDER_SETTINGS: {
        authority: 'https://sso.linagora.local',
        client_id: 'twake-calendar',
        redirect_uri: 'https://contacts.linagora.local/contacts/auth/oidc/callback',
        silent_redirect_uri: 'https://contacts.linagora.local/contacts/assets/auth/silent-renew.html',
        post_logout_redirect_uri: 'https://contacts.linagora.local/contacts',
        response_type: 'code',
        scope: 'openid email profile'
    }
};