# Twake Calendar Dev Environment (Docker Compose)

This directory provides a complete `docker-compose` setup to run the **Twake Calendar** backend and dependencies locally for development and testing.

It includes:

- Twake Calendar frontend: [twake-calendar-frontend](https://github.com/linagora/twake-calendar-frontend)
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
127.0.0.1 oidc.local tcalendar-side-service.local
```
Then:
```bash
docker-compose up -d
```
After running the Docker Compose setup, open your browser and visit:

👉 http://localhost:3000

At this address, the Twake Calendar frontend app will automatically redirect you to the SSO login page (OIDC login via Dex).

You can log in using the following preconfigured accounts:

- bob@linagora.com / bob
- alice@linagora.com / alice
- cedric@linagora.com / cedric
