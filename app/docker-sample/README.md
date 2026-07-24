# Twake Calendar Dev Environment (Docker Compose)

This directory provides a complete `docker-compose` setup to run the **Twake Calendar** backend and dependencies locally for development and testing.

It includes:

- Twake Calendar frontend: [twake-calendar-frontend](https://github.com/linagora/twake-calendar-frontend)
- Openpaas ESN frontend
- Twake Calendar side service
- SabreDAV server for calendar storage
- RabbitMQ, MongoDB
- Authentication with OIDC (Dex)
- Web reverse proxy (Nginx)
- Mock SMTP server for email testing

---

## 🚀 How to Start

📝 **Before you start**, make sure to edit your `/etc/hosts` file and add the following line:
```
127.0.0.1 tcalendar-side-service.linagora.local sso.linagora.local sabre-dav.linagora.local
127.0.0.1 calendar-ng.linagora.local calendar.linagora.local contacts.linagora.local account.linagora.local excal.linagora.local
```
Then start the default stack:
```bash
docker-compose up -d
```
This starts the Twake Calendar frontend, side service, and backend dependencies.

To also start the OpenPaas ESN frontend services (`calendar.linagora.local`,
`excal.linagora.local`, `contacts.linagora.local`, and `account.linagora.local`),
enable the `openpaas` profile:
```bash
docker-compose --profile openpaas up -d
```
After running the Docker Compose setup, open your browser and visit:

👉 https://calendar-ng.linagora.local
👉 https://calendar.linagora.local (when the `openpaas` profile is enabled)
At this address, the Twake Calendar frontend app will automatically redirect you to the SSO login page (OIDC login via Dex).

You can log in using the following preconfigured accounts:

- bob@linagora.local / bob
- alice@linagora.local / alice
- cedric@linagora.local / cedric
